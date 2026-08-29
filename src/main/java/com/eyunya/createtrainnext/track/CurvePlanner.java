package com.eyunya.createtrainnext.track;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNRegistry;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Works out the curve a click would draw, without drawing it.
 *
 * <p>One function, run identically on both sides: the client calls it every frame to render a
 * ghost and the server calls it once to decide what to build. That is the whole reason it is a
 * separate class from {@link CurveWandItem} and holds no state — a preview that is computed by
 * different code from the result is a preview that eventually lies about it.
 *
 * <p>What the player is asked for is two clicks. Everything else — which side of a track block the
 * curve leaves from, where the anchors go, which way each one points, whether Create's own track
 * can dock onto the result — is derived here.
 *
 * <h2>How the tangents are chosen</h2>
 *
 * <p>An end of a curve is either <em>fixed</em> or <em>free</em>. It is fixed when something
 * already decides which way the route runs there: a piece of Create track being docked onto, or an
 * anchor that a curve already arrives at. It is free when the click landed on open ground, or on an
 * anchor with nothing attached to it yet.
 *
 * <p>A free end is aimed at the tangent that makes the curve a <em>circular arc</em>, which is the
 * reflection of the fixed end's tangent about the straight line between the two ends. That is worth
 * being precise about because it is what makes the tool usable without any angle input at all: the
 * chord of a circular arc makes equal angles with the tangents at both of its ends, so reflecting
 * one about the chord gives exactly the other. Two clicks and no adjustment produce the curve a
 * railway would actually have been built with.
 *
 * <p>The player's own adjustment is then an offset applied on top of that, and only ever to the
 * free end — turning a fixed one would put a kink in whatever it is fixed to.
 */
public final class CurvePlanner {

	/** Which end of the curve an endpoint is, which decides which way "downstream" points. */
	public enum Role {
		START,
		END
	}

	private static final Vec3 UP = new Vec3(0, 1, 0);

	/**
	 * How closely a curve has to line up with whatever it meets at its ends, as a dot product.
	 *
	 * <p>This is Create's own rule for whether a train may cross a node — {@code TrackEdge}
	 * refuses one where the two edges differ in direction by more than {@code 7/8}, about 29
	 * degrees — with a little headroom on top, since the curve shifts very slightly between being
	 * previewed and being built.
	 *
	 * <p>The subtlety is that Create measures an edge's direction as the chord over a quarter of
	 * it, not as the tangent at its end, so two edges meeting perfectly tangentially can still be
	 * refused because each bends away from the shared tangent. An earlier version answered that by
	 * holding every end of every curve to half the allowance, on the grounds that two such halves
	 * always fit. They do, but it is far too careful: it assumes the neighbour uses its whole half,
	 * and it spends the same budget against a dead straight piece of Create track as against
	 * another long curve. Curves that a train could take perfectly well were refused for it.
	 *
	 * <p>So the neighbour is measured instead of assumed. See {@link #adjoiningDirection}.
	 */
	private static final double MIN_JOINT_DOT = 0.90;

	/**
	 * The most the route may turn across one curve.
	 *
	 * <p>More than Create manages on its own, which tops out at 90 — its track only meets at
	 * multiples of 45. Past this a single curve is a hairpin, and {@link #MIN_RADIUS} is the check
	 * that decides whether it is a buildable one.
	 */
	private static final double MAX_TURN_DEGREES = 135;

	/** Below this the curve has no room to be a curve, and the two anchors would overlap. */
	private static final double MIN_SPAN = 1.5;

	/**
	 * The tightest arc a curve may be, in blocks.
	 *
	 * <p>Not the same question as {@link #MIN_JOINT_DOT}, and the reason is worth spelling out:
	 * how far a bezier's chord strays from its tangent depends only on how far the curve turns, not
	 * on how long it is, so a quarter turn taken over two blocks and one taken over twenty are
	 * identical by that measure. Only the radius tells them apart.
	 *
	 * <p>Two, because that is exactly the tightest turn Create will place — its own curves are
	 * standardised to two blocks of run either side of the corner, which for a right angle is a
	 * radius of two. Free angles are the point of this mod; tighter track than the base game allows
	 * is not.
	 */
	private static final double MIN_RADIUS = 2.0;

	private CurvePlanner() {}

	// --------------------------------------------------------------- results

	/**
	 * One end of a planned curve: the anchor it will use, and what is already decided about it.
	 *
	 * @param error        why this end cannot be used, or null if it can.
	 * @param anchorPos    the anchor this end will use, whether or not it exists yet.
	 * @param mustPlace    whether an anchor has to be put there first.
	 * @param fixedHeading the heading this end is obliged to have, or null if it is free to be
	 *                     aimed.
	 * @param interior     whether the anchor will have a route running through it once linked,
	 *                     which is what decides whether its node sits at the block's centre or on
	 *                     its boundary.
	 * @param docked       whether this end shares its node with a piece of Create's own track, which
	 *                     is a thing the route must not later be extended through.
	 */
	public record Endpoint(@Nullable String error, BlockPos anchorPos, boolean mustPlace,
		@Nullable Vec3 fixedHeading, boolean interior, boolean docked) {

		static Endpoint failed(String error) {
			return new Endpoint(error, BlockPos.ZERO, false, null, false, false);
		}

		/** Whether this end's heading has to be written to the anchor when the curve is built. */
		boolean needsAiming() {
			return mustPlace || fixedHeading == null;
		}
	}

	/**
	 * A curve that could be built, or the reason it could not.
	 *
	 * <p>{@link #curve} is present even for most refusals, because a preview that vanishes the
	 * moment it becomes invalid tells the player nothing about which way to move to fix it. Only
	 * failures with no geometry at all — a click that resolves to no anchor — leave it null.
	 */
	public record Plan(@Nullable String error, @Nullable Endpoint start, @Nullable Endpoint end,
		@Nullable Vec3 startHeading, @Nullable Vec3 endHeading, @Nullable BezierConnection curve,
		boolean adjustable, double turnDegrees) {

		static Plan failed(String error) {
			return new Plan(error, null, null, null, null, null, false, 0);
		}

		Plan withError(String newError) {
			return new Plan(newError, start, end, startHeading, endHeading, curve, adjustable, turnDegrees);
		}

		public boolean valid() {
			return error == null;
		}

		public double length() {
			return curve == null ? 0 : curve.getLength();
		}
	}

	// ---------------------------------------------------------------- entry

	/**
	 * Whether a block is something a curve can be started from, which is what the first click has
	 * to check before it stores a selection.
	 */
	public static boolean canStartFrom(Level level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor)
			return anchor.getNext() == null;

		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ITrackBlock track))
			return false;

		// The same two refusals resolve() makes, asked here so that a junction or a slope is
		// refused by the click that picked it rather than by every click after it.
		List<Vec3> axes = track.getTrackAxes(level, pos, state);
		return axes.size() == 1 && Math.abs(axes.get(0).y) <= 1.0E-4;
	}

	/**
	 * The curve that clicking {@code hitPos} would draw, given a selection made at {@code startClick}.
	 *
	 * @param angleOffsetDegrees the player's hand adjustment to the free end's tangent.
	 */
	public static Plan plan(Level level, BlockPos startClick, BlockPos hitPos, Direction hitFace,
		float angleOffsetDegrees) {

		// Which side of a track block the curve should leave from depends on where the other end
		// is, and the other end has the same question about this one. Block centres break the
		// circle: they are within half a block of the answer, and the two only disagree when the
		// ends are close enough together that the curve is refused anyway.
		Vec3 roughStart = Vec3.atBottomCenterOf(startClick);
		Vec3 roughEnd = Vec3.atBottomCenterOf(hitPos);

		Endpoint start = resolve(level, startClick, null, roughEnd, Role.START);
		if (start.error() != null)
			return Plan.failed(start.error());

		Endpoint end = resolve(level, hitPos, hitFace, roughStart, Role.END);
		if (end.error() != null)
			return Plan.failed(end.error());

		if (start.anchorPos()
			.equals(end.anchorPos()))
			return Plan.failed("same_anchor");

		return solve(level, start, end, angleOffsetDegrees);
	}

	/** Builds what {@code plan} described. Server side only; the client draws it instead. */
	public static void apply(Level level, Plan plan) {
		if (!plan.valid())
			return;

		place(level, plan.start(), plan.startHeading());
		place(level, plan.end(), plan.endHeading());

		if (!(level.getBlockEntity(plan.start()
			.anchorPos()) instanceof AngledTrackAnchorBlockEntity from))
			return;
		if (!(level.getBlockEntity(plan.end()
			.anchorPos()) instanceof AngledTrackAnchorBlockEntity to))
			return;

		// Aiming an end that was already aimed would only feed its own heading back through a yaw
		// and out again, losing a little of it to float each time a route was extended.
		from.linkTo(to, plan.start()
			.needsAiming() ? plan.startHeading() : null,
			plan.end()
				.needsAiming() ? plan.endHeading() : null);
	}

	private static void place(Level level, Endpoint endpoint, Vec3 heading) {
		if (!endpoint.mustPlace())
			return;

		BlockPos pos = endpoint.anchorPos();
		clearFor(level, pos);

		BlockState state = CTNRegistry.ANGLED_TRACK_ANCHOR.get()
			.defaultBlockState()
			.setValue(AngledTrackAnchorBlock.AXIS, AnchorAxis.nearestTo(heading));
		level.setBlock(pos, state, Block.UPDATE_ALL);
	}

	/**
	 * Empties a block so an anchor can go in it.
	 *
	 * <p>The spot was checked as replaceable, which covers grass and snow as well as air. Breaking
	 * it properly rather than overwriting it is what makes the wand behave like placing a block by
	 * hand: the flowers a curve is run through drop rather than ceasing to have existed.
	 *
	 * <p>A curve's own invisible floor is the exception, and is taken out silently. It has no
	 * texture to break into, so destroying one would throw up a puff of missing-texture squares,
	 * and it is this mod's own scaffolding rather than anything the player put there.
	 */
	private static void clearFor(Level level, BlockPos pos) {
		BlockState existing = level.getBlockState(pos);
		if (existing.isAir())
			return;
		if (existing.is(CTNRegistry.CURVE_FILLER.get())) {
			level.removeBlock(pos, false);
			return;
		}
		level.destroyBlock(pos, true);
	}

	// ----------------------------------------------------------- resolution

	/**
	 * Turns a clicked block into the anchor a curve would use there.
	 *
	 * @param face    the face that was clicked, used only to decide where a new anchor goes on open
	 *                ground. Null for the stored selection, whose face was not kept.
	 * @param towards roughly where the other end of the curve is, which decides which side of a
	 *                track block to work from.
	 */
	private static Endpoint resolve(Level level, BlockPos hitPos, @Nullable Direction face, Vec3 towards, Role role) {
		// An anchor was clicked outright.
		if (level.getBlockEntity(hitPos) instanceof AngledTrackAnchorBlockEntity anchor)
			return fromExisting(anchor, role);

		BlockState state = level.getBlockState(hitPos);

		// Create's own track. The anchor goes in the block beside it so that the two share a node:
		// an end anchor puts its node half a block along its axis from its own centre, and so does
		// Create's track, so placing one directly alongside the other lands both on the same point
		// and the graph merges them. Anywhere else and the curve simply stops next to the track.
		if (state.getBlock() instanceof ITrackBlock track) {
			List<Vec3> axes = track.getTrackAxes(level, hitPos, state);
			if (axes.size() > 1)
				return Endpoint.failed("junction");

			Vec3 axis = axes.get(0);
			if (Math.abs(axis.y) > 1.0E-4)
				return Endpoint.failed("slope");

			// The side of the track that faces the rest of the curve. An axis serves either way
			// along itself, so this is the only thing that picks between the two.
			Vec3 toOther = flat(towards.subtract(Vec3.atBottomCenterOf(hitPos)));
			Vec3 side = axis.dot(toOther) < 0 ? axis.scale(-1) : axis;

			BlockPos anchorPos = hitPos.offset(BlockPos.containing(side));
			if (level.getBlockEntity(anchorPos) instanceof AngledTrackAnchorBlockEntity existing)
				return fromExisting(existing, role);
			if (!level.getBlockState(anchorPos)
				.canBeReplaced())
				return Endpoint.failed("no_room");

			// Headings always point downstream, so a route leaving a track points away from it and
			// one arriving points into it. Both put the anchor's shared node on the track's side.
			Vec3 heading = (role == Role.START ? side : side.scale(-1)).normalize();
			return new Endpoint(null, anchorPos, true, heading, false, true);
		}

		// Open ground: the anchor goes where a block would have gone. Only ever the far end of a
		// curve — a selection is only made on track, so reaching here as the start means whatever
		// was selected has been mined since, and putting an anchor above the hole is not what
		// anyone asked for.
		if (role == Role.START)
			return Endpoint.failed("invalid_start");

		BlockPos placePos = state.canBeReplaced() ? hitPos : hitPos.relative(face == null ? Direction.UP : face);
		if (level.getBlockEntity(placePos) instanceof AngledTrackAnchorBlockEntity existing)
			return fromExisting(existing, role);
		if (!level.getBlockState(placePos)
			.canBeReplaced())
			return Endpoint.failed("no_room");

		return new Endpoint(null, placePos, true, null, false, false);
	}

	/**
	 * What an anchor that already exists allows.
	 *
	 * <p>Each anchor holds one link in each direction, so a route runs through it rather than
	 * branching at it. A curve therefore has to leave by the side that is still free, and the side
	 * that is taken is what fixes the heading: it belongs to the curve already there.
	 */
	private static Endpoint fromExisting(AngledTrackAnchorBlockEntity anchor, Role role) {
		BlockPos pos = anchor.getBlockPos();
		boolean taken = role == Role.START ? anchor.getNext() != null : anchor.getPrev() != null;
		if (taken)
			return Endpoint.failed(role == Role.START ? "start_taken" : "end_taken");

		// Linked on the far side, so the route will pass through rather than end here.
		boolean interior = role == Role.START ? anchor.getPrev() != null : anchor.getNext() != null;
		return new Endpoint(null, pos, false, interior ? anchor.heading() : null, interior, false);
	}

	// ------------------------------------------------------------- geometry

	private static Plan solve(Level level, Endpoint start, Endpoint end, float angleOffsetDegrees) {
		Vec3 fixedStart = start.fixedHeading() == null ? null : flatUnit(start.fixedHeading());
		Vec3 fixedEnd = end.fixedHeading() == null ? null : flatUnit(end.fixedHeading());

		Vec3 seed = flat(Vec3.atBottomCenterOf(end.anchorPos())
			.subtract(Vec3.atBottomCenterOf(start.anchorPos())));
		if (seed.lengthSqr() < 1.0E-6)
			return Plan.failed("too_close");
		seed = seed.normalize();

		Vec3 startHeading = fixedStart != null ? fixedStart : seed;
		Vec3 endHeading = fixedEnd != null ? fixedEnd : seed;

		// Where a node lands depends on the heading, and the heading we want depends on where the
		// nodes land, so this settles the two against each other. Three passes is well past enough
		// for an offset of half a block; what matters more is that it is deterministic, since the
		// client's preview and the server's placement have to agree exactly.
		Vec3 startPoint = null;
		Vec3 endPoint = null;
		for (int pass = 0; pass < 3; pass++) {
			startPoint = AngledTrackAnchorBlockEntity.exitNode(start.anchorPos(), startHeading, start.interior());
			endPoint = AngledTrackAnchorBlockEntity.entryNode(end.anchorPos(), endHeading, end.interior());

			Vec3 chord = flat(endPoint.subtract(startPoint));
			if (chord.lengthSqr() < 1.0E-6)
				break;
			Vec3 direction = chord.normalize();

			if (fixedStart == null && fixedEnd == null) {
				// Nothing to answer to at either end, so the straight line between them it is.
				startHeading = direction;
				endHeading = direction;
			} else if (fixedEnd == null) {
				endHeading = reflect(startHeading, direction);
			} else if (fixedStart == null) {
				startHeading = reflect(endHeading, direction);
			} else {
				break;
			}
		}

		boolean adjustable = fixedStart == null || fixedEnd == null;
		if (adjustable && Math.abs(angleOffsetDegrees) > 1.0E-4) {
			if (fixedEnd == null)
				endHeading = rotateY(endHeading, angleOffsetDegrees);
			else
				startHeading = rotateY(startHeading, angleOffsetDegrees);
			startPoint = AngledTrackAnchorBlockEntity.exitNode(start.anchorPos(), startHeading, start.interior());
			endPoint = AngledTrackAnchorBlockEntity.entryNode(end.anchorPos(), endHeading, end.interior());
		}

		double turn = Math.toDegrees(Math.acos(Mth.clamp(startHeading.dot(endHeading), -1, 1)));

		// Both tangents point into the curve: ours forwards along the route, the far end's
		// backwards along it, matching how an anchor builds the curve it owns.
		BezierConnection curve = new BezierConnection(Couple.create(start.anchorPos(), end.anchorPos()),
			Couple.create(startPoint, endPoint), Couple.create(startHeading, endHeading.scale(-1)),
			Couple.create(UP, UP), true, false, TrackMaterial.ANDESITE);

		Plan plan = new Plan(null, start, end, startHeading, endHeading, curve, adjustable, turn);
		String error = validate(level, plan, startPoint, endPoint, startHeading, endHeading, turn);
		return error == null ? plan : plan.withError(error);
	}

	@Nullable
	private static String validate(Level level, Plan plan, Vec3 startPoint, Vec3 endPoint, Vec3 startHeading,
		Vec3 endHeading, double turn) {

		Vec3 span = endPoint.subtract(startPoint);
		double horizontal = flat(span).length();
		double ascend = Math.abs(span.y);

		if (horizontal < MIN_SPAN)
			return "too_close";

		int maxLength = AllConfigs.server().trains.maxTrackPlacementLength.get();
		if (span.length() > maxLength)
			return "too_far";

		Vec3 direction = flat(span).normalize();
		if (startHeading.dot(direction) <= 0 || endHeading.dot(direction) <= 0)
			return "doubles_back";

		if (turn > MAX_TURN_DEGREES)
			return "too_sharp";

		// Create's own rule for how much rise a length of track may carry, which is about how much
		// a train can climb rather than anything to do with the curve.
		if (ascend > 1.0E-4 && horizontal < Math.max(ascend * 3, 3))
			return "too_steep";

		BezierConnection curve = plan.curve();
		double length = curve.getLength();
		if (!Double.isFinite(length) || length <= 0 || length > maxLength * 4)
			return "too_sharp";

		// Zero means Create found no arc to measure, which is what a straight or an S curve is.
		// Those are not tight, they are just not turns, and the checks below cover them instead.
		double radius = curve.getRadius();
		if (radius > 0 && radius < MIN_RADIUS)
			return "too_sharp";

		// Whether a train may cross the nodes at either end, asked the way Create asks it: the
		// direction this curve leaves by against the direction of whatever is already there.
		Vec3 startChord = curve.getPosition(0.25)
			.subtract(curve.getPosition(0));
		Vec3 endChord = curve.getPosition(1)
			.subtract(curve.getPosition(0.75));
		if (startChord.lengthSqr() < 1.0E-8 || endChord.lengthSqr() < 1.0E-8)
			return "too_sharp";

		Vec3 arriving = adjoiningDirection(level, plan.start(), startHeading, Role.START);
		if (startChord.normalize()
			.dot(arriving == null ? startHeading : arriving) < MIN_JOINT_DOT)
			return "too_sharp";

		Vec3 leaving = adjoiningDirection(level, plan.end(), endHeading, Role.END);
		if (endChord.normalize()
			.dot(leaving == null ? endHeading : leaving) < MIN_JOINT_DOT)
			return "too_sharp";

		return null;
	}

	/**
	 * Which way the track already at one end of this curve is running, measured as Create measures
	 * it, or null where there is nothing there yet.
	 *
	 * <p>Null means the anchor's own span will be what meets the curve here, and a span is aimed
	 * along the heading by construction — so the heading is the honest thing to compare against,
	 * and the caller does. That covers every end that docks onto Create's track, which is exactly
	 * the case the old fixed allowance was hardest on: it spent half its budget guarding against a
	 * neighbour that is dead straight.
	 *
	 * <p>Otherwise the neighbour is another of this mod's curves, and it is rebuilt here rather
	 * than read off the anchor. Linking is about to move this end of it: an anchor with a curve on
	 * both sides puts its node at its block's centre, and the one being asked about has a curve on
	 * one side so far. Reading it would measure the shape it is about to stop having.
	 */
	@Nullable
	private static Vec3 adjoiningDirection(Level level, Endpoint endpoint, Vec3 heading, Role role) {
		if (!endpoint.interior())
			return null;
		if (!(level.getBlockEntity(endpoint.anchorPos()) instanceof AngledTrackAnchorBlockEntity anchor))
			return null;

		BlockPos neighbourPos = role == Role.START ? anchor.getPrev() : anchor.getNext();
		if (neighbourPos == null
			|| !(level.getBlockEntity(neighbourPos) instanceof AngledTrackAnchorBlockEntity neighbour))
			return null;

		BlockPos pos = endpoint.anchorPos();
		boolean start = role == Role.START;

		// The curve as it will be once this anchor has a route running through it. For a start the
		// neighbour is behind, so the curve runs into this node; for an end it is ahead, and the
		// curve runs out of it.
		Vec3 here = start ? AngledTrackAnchorBlockEntity.entryNode(pos, heading, true)
			: AngledTrackAnchorBlockEntity.exitNode(pos, heading, true);
		Vec3 there = start ? neighbour.exitNode() : neighbour.entryNode();
		if (here.distanceToSqr(there) < 1.0E-6)
			return null;

		BezierConnection adjoining = start
			? new BezierConnection(Couple.create(neighbourPos, pos), Couple.create(there, here),
				Couple.create(neighbour.heading(), heading.scale(-1)), Couple.create(UP, UP), true, false,
				TrackMaterial.ANDESITE)
			: new BezierConnection(Couple.create(pos, neighbourPos), Couple.create(here, there),
				Couple.create(heading, neighbour.heading()
					.scale(-1)),
				Couple.create(UP, UP), true, false, TrackMaterial.ANDESITE);

		// Arriving at this node for a start, leaving it for an end — the two halves of what
		// TrackEdge.canTravelTo compares.
		Vec3 chord = start ? adjoining.getPosition(1)
			.subtract(adjoining.getPosition(0.75))
			: adjoining.getPosition(0.25)
				.subtract(adjoining.getPosition(0));
		return chord.lengthSqr() < 1.0E-8 ? null : chord.normalize();
	}

	// ---------------------------------------------------------------- split

	/**
	 * A point part way along an existing curve, and everything needed to put an anchor there.
	 *
	 * @param owner    the anchor that owns the curve, which is always the one behind it.
	 * @param t        where along the curve, as a bezier parameter.
	 * @param point    where that is in the world.
	 * @param tangent  which way the route is going there, and so which way the new anchor points.
	 * @param anchorPos the block the new anchor would occupy.
	 * @param error    why it cannot be done there, or null if it can.
	 */
	public record SplitTarget(@Nullable AngledTrackAnchorBlockEntity owner, double t, Vec3 point, Vec3 tangent,
		BlockPos anchorPos, @Nullable String error) {

		public boolean valid() {
			return error == null && owner != null;
		}
	}

	/**
	 * The point on a nearby curve closest to {@code near}, or null if there is no curve near it.
	 *
	 * <p>Sampling rather than solving. The closest point on a cubic bezier is a fifth degree
	 * problem, and the answer only has to be good to about a block here — it decides which block an
	 * anchor goes in, and blocks are a block wide.
	 */
	@Nullable
	public static SplitTarget findCurveNear(Level level, Vec3 near, double maxDistance) {
		AngledTrackAnchorBlockEntity bestOwner = null;
		BezierConnection bestCurve = null;
		double bestLength = 0;
		double bestT = 0;
		double bestDistance = maxDistance * maxDistance;

		for (AngledTrackAnchorBlockEntity anchor : AnchorIndex.in(level)) {
			BezierConnection curve = anchor.buildCurveToNext();
			if (curve == null)
				continue;
			if (!curve.getBounds()
				.inflate(maxDistance)
				.contains(near))
				continue;

			double length = curve.getLength();
			int samples = Math.max(8, (int) (length * 4));

			for (int i = 0; i <= samples; i++) {
				double t = i / (double) samples;
				double distance = curve.getPosition(t)
					.distanceToSqr(near);
				if (distance >= bestDistance)
					continue;

				bestDistance = distance;
				bestOwner = anchor;
				bestCurve = curve;
				bestLength = length;
				bestT = t;
			}
		}

		// Only the winner is described. Everything in describeSplit reads the world, and doing that
		// for each sample that happened to be an improvement would mean hundreds of block lookups
		// per tick for one answer.
		return bestOwner == null ? null : describeSplit(level, bestOwner, bestCurve, bestT, bestLength);
	}

	private static SplitTarget describeSplit(Level level, AngledTrackAnchorBlockEntity owner, BezierConnection curve,
		double t, double length) {

		Vec3 point = curve.getPosition(t);

		// The tangent as a difference across a short step rather than the bezier's derivative, so
		// this needs nothing but the curve's public shape.
		double step = 1.0 / Math.max(8, (int) (length * 4));
		Vec3 tangent = flat(curve.getPosition(Math.min(1, t + step))
			.subtract(curve.getPosition(Math.max(0, t - step))));
		tangent = tangent.lengthSqr() < 1.0E-8 ? owner.heading() : tangent.normalize();

		BlockPos anchorPos = BlockPos.containing(point);

		// Both halves have to be a curve in their own right afterwards. The same MIN_SPAN the wand
		// holds a new curve to, measured along this one.
		if (t * length < MIN_SPAN + 1 || (1 - t) * length < MIN_SPAN + 1)
			return new SplitTarget(owner, t, point, tangent, anchorPos, "split_too_close");
		if (anchorPos.equals(owner.getBlockPos()) || anchorPos.equals(owner.getNext()))
			return new SplitTarget(owner, t, point, tangent, anchorPos, "split_too_close");
		if (level.getBlockEntity(anchorPos) instanceof AngledTrackAnchorBlockEntity)
			return new SplitTarget(owner, t, point, tangent, anchorPos, "split_occupied");
		if (!level.getBlockState(anchorPos)
			.canBeReplaced())
			return new SplitTarget(owner, t, point, tangent, anchorPos, "no_room");

		return new SplitTarget(owner, t, point, tangent, anchorPos, null);
	}

	/**
	 * Puts an anchor part way along a curve, leaving two curves where there was one.
	 *
	 * <p>What this is for is signals and stations. Create places those against a track block and a
	 * direction, and an anchor is a track block, so an anchor part way along a route is a place one
	 * can go — whereas the curve between two anchors is not, at least not without Create's own
	 * curve targeting, which only recognises its own block entities.
	 *
	 * <p>The route moves slightly here, and it has to: an anchor with a curve on either side puts
	 * its node at its block's centre, and the curve did not necessarily pass through the centre of
	 * the block it was in. The tangent is kept exactly, so the two halves still meet smoothly and
	 * the line either side of the new anchor is the shape it was.
	 */
	public static boolean split(Level level, SplitTarget target) {
		if (!target.valid())
			return false;

		AngledTrackAnchorBlockEntity owner = target.owner();
		BlockPos nextPos = owner.getNext();
		if (nextPos == null || !(level.getBlockEntity(nextPos) instanceof AngledTrackAnchorBlockEntity ahead))
			return false;

		BlockPos pos = target.anchorPos();
		clearFor(level, pos);
		level.setBlock(pos, CTNRegistry.ANGLED_TRACK_ANCHOR.get()
			.defaultBlockState()
			.setValue(AngledTrackAnchorBlock.AXIS, AnchorAxis.nearestTo(target.tangent())), Block.UPDATE_ALL);

		if (!(level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity inserted))
			return false;

		// The first link takes the far end off the owner, so the anchor ahead has to be caught
		// before it, not looked up again after.
		owner.linkTo(inserted, null, target.tangent());
		inserted.linkTo(ahead, null, null);
		return true;
	}

	// ----------------------------------------------------------------- math

	/**
	 * {@code tangent} mirrored about {@code chord}: the other tangent of the circular arc that
	 * joins the two ends of a curve whose chord runs that way.
	 */
	private static Vec3 reflect(Vec3 tangent, Vec3 chord) {
		return chord.scale(2 * tangent.dot(chord))
			.subtract(tangent)
			.normalize();
	}

	private static Vec3 rotateY(Vec3 vector, double degrees) {
		double radians = Math.toRadians(degrees);
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		return new Vec3(vector.x * cos - vector.z * sin, vector.y, vector.x * sin + vector.z * cos);
	}

	/** Headings are horizontal; a curve climbs by having its ends at different heights, not by tilting. */
	private static Vec3 flat(Vec3 vector) {
		return new Vec3(vector.x, 0, vector.z);
	}

	private static Vec3 flatUnit(Vec3 vector) {
		Vec3 flattened = flat(vector);
		return flattened.lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : flattened.normalize();
	}
}
