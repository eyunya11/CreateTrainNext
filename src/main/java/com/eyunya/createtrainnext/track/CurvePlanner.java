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

	/**
	 * Which of an anchor's two sides a new curve may attach to.
	 *
	 * <p>An anchor holds one link in each direction — a curve arriving and a curve leaving — so it
	 * has two sides and a new curve takes one of them. Which one is not a matter of taste: an
	 * anchor already carrying a curve has that side spoken for, and the free side is the only
	 * answer. An anchor carrying nothing yet can take either, and lets the other end of the curve
	 * decide.
	 */
	public enum Side {
		NEXT,
		PREV,
		EITHER;

		boolean allows(Side wanted) {
			return this == EITHER || this == wanted;
		}
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
	 * @param side         which of the anchor's two sides this curve may attach to.
	 */
	public record Endpoint(@Nullable String error, BlockPos anchorPos, boolean mustPlace,
		@Nullable Vec3 fixedHeading, boolean interior, boolean docked, Side side) {

		static Endpoint failed(String error) {
			return new Endpoint(error, BlockPos.ZERO, false, null, false, false, Side.EITHER);
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
	 *
	 * <p>{@link #forward} is which way the link between the two anchors runs: from the start's next
	 * to the end's prev, or from the end's next to the start's prev. {@link #reverseEnd} is whether
	 * the route the end belongs to has to be turned round first for that to be possible.
	 */
	public record Plan(@Nullable String error, @Nullable Endpoint start, @Nullable Endpoint end,
		@Nullable Vec3 startHeading, @Nullable Vec3 endHeading, @Nullable BezierConnection curve,
		boolean adjustable, double turnDegrees, boolean forward, boolean reverseEnd) {

		static Plan failed(String error) {
			return new Plan(error, null, null, null, null, null, false, 0, true, false);
		}

		Plan withError(String newError) {
			return new Plan(newError, start, end, startHeading, endHeading, curve, adjustable, turnDegrees,
				forward, reverseEnd);
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
	 *
	 * <p>An anchor qualifies while either of its sides is free. This used to ask only about its
	 * next, which is the side the <em>first</em> click always used before {@link #plan} learned to
	 * read the free side off the anchor instead — and left the anchor a route was begun from, whose
	 * next is taken and whose prev is not, as something a curve could arrive at but never leave.
	 */
	public static boolean canStartFrom(Level level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor)
			return anchor.hasFreeSide();

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

		// A curve is one link, and a link runs from somebody's next to somebody's prev. So one end
		// has to give up its next and the other its prev, and which way round that falls out of
		// which sides are still free rather than out of which end was clicked first. That is what
		// lets a route be continued backwards out of the anchor it started from: that anchor's next
		// is spoken for, its prev is not, so the new curve runs into it instead of out of it.
		boolean forward;
		boolean reverseEnd = false;
		if (start.side()
			.allows(Side.NEXT)
			&& end.side()
				.allows(Side.PREV))
			forward = true;
		else if (start.side()
			.allows(Side.PREV)
			&& end.side()
				.allows(Side.NEXT))
			forward = false;
		else {
			// Both ends want the same slot, which happens joining two routes that were laid away
			// from each other: two tails facing one another, or two heads. One of the routes has to
			// be turned round, and the one chosen is the one at the far end — the route being joined
			// onto, rather than the one being carried on. Turning it swaps which side of its end is
			// free, and that is all it takes: the geometry below already describes the curve between
			// the two ends as they stand, and turning a route does not move any of its track.
			//
			// Only these two pairs get here, because an end that is free on both sides allows either
			// slot. A route has one head and one tail, so they are two different routes, and turning
			// the far one cannot disturb the end that was clicked first.
			reverseEnd = true;
			forward = start.side() == Side.NEXT;
		}

		return solve(level, start, end, angleOffsetDegrees, forward, reverseEnd);
	}

	/**
	 * Builds what {@code plan} described. Server side only; the client draws it instead.
	 *
	 * @return why it could not be built, as the key of a wand message, or null once it has been.
	 *         Nothing is changed when a reason comes back.
	 */
	@Nullable
	public static String apply(Level level, Plan plan) {
		if (!plan.valid())
			return plan.error();

		// Before anything is placed, so that a route which cannot be turned leaves the world just as
		// it found it. Whether the whole of it can be reached is only known here: the ghost was drawn
		// from what the client has loaded, which can be less than the server does.
		if (plan.reverseEnd()) {
			if (!(level.getBlockEntity(plan.end()
				.anchorPos()) instanceof AngledTrackAnchorBlockEntity turning) || turning.reverseRoute() == 0)
				return "route_unavailable";
		}

		place(level, plan.start(), plan.startHeading());
		place(level, plan.end(), plan.endHeading());

		if (!(level.getBlockEntity(plan.start()
			.anchorPos()) instanceof AngledTrackAnchorBlockEntity startAnchor))
			return "no_room";
		if (!(level.getBlockEntity(plan.end()
			.anchorPos()) instanceof AngledTrackAnchorBlockEntity endAnchor))
			return "no_room";

		// Anchors store their heading pointing downstream, and downstream is whichever way the link
		// runs. Drawn backwards, the two headings are the negatives of the curve's own directions —
		// and for the anchor being continued backwards out of, that negation lands exactly on the
		// heading it already had, which is what leaves the curve on its other side undisturbed.
		AngledTrackAnchorBlockEntity from = plan.forward() ? startAnchor : endAnchor;
		AngledTrackAnchorBlockEntity to = plan.forward() ? endAnchor : startAnchor;
		Vec3 fromHeading = plan.forward() ? plan.startHeading()
			: plan.endHeading()
				.scale(-1);
		Vec3 toHeading = plan.forward() ? plan.endHeading()
			: plan.startHeading()
				.scale(-1);
		Endpoint fromEnd = plan.forward() ? plan.start() : plan.end();
		Endpoint toEnd = plan.forward() ? plan.end() : plan.start();

		// Aiming an end that was already aimed would only feed its own heading back through a yaw
		// and out again, losing a little of it to float each time a route was extended.
		//
		// An end that stops being one reports a different axis, and linkTo holds the sign it had, so
		// that whatever is standing on it stays facing the same way.
		from.linkTo(to, fromEnd.needsAiming() ? fromHeading : null, toEnd.needsAiming() ? toHeading : null);
		return null;
	}

	private static void place(Level level, Endpoint endpoint, Vec3 heading) {
		if (!endpoint.mustPlace())
			return;

		placeAnchor(level, endpoint.anchorPos(), heading);
	}

	/** Puts an anchor at {@code pos}, clearing whatever was there first. */
	private static void placeAnchor(Level level, BlockPos pos, Vec3 heading) {
		clearFor(level, pos);
		level.setBlock(pos, CTNRegistry.ANGLED_TRACK_ANCHOR.get()
			.defaultBlockState()
			.setValue(AngledTrackAnchorBlock.AXIS, AnchorAxis.nearestTo(heading)), Block.UPDATE_ALL);
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
			return new Endpoint(null, anchorPos, true, heading, false, true, Side.EITHER);
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

		return new Endpoint(null, placePos, true, null, false, false, Side.EITHER);
	}

	/**
	 * What an anchor that already exists allows.
	 *
	 * <p>Each anchor holds one link in each direction, so a route runs through it rather than
	 * branching at it. A curve therefore has to leave by the side that is still free, and the side
	 * that is taken is what fixes the heading: it belongs to the curve already there.
	 */
	private static Endpoint fromExisting(AngledTrackAnchorBlockEntity anchor, Role role) {
		boolean nextFree = anchor.getNext() == null;
		boolean prevFree = anchor.getPrev() == null;

		if (!nextFree && !prevFree)
			return Endpoint.failed(role == Role.START ? "start_taken" : "end_taken");

		Side side = nextFree && prevFree ? Side.EITHER : nextFree ? Side.NEXT : Side.PREV;

		// Which way a new curve leaves this anchor. An anchor already carrying one has its
		// direction settled — the route runs through it, so the new curve continues the line, and
		// whether that is forwards or backwards is decided by which side was left free rather than
		// by which of the two clicks landed here first.
		Vec3 outward = side == Side.EITHER ? null
			: side == Side.NEXT ? anchor.heading()
				: anchor.heading()
					.scale(-1);

		// solve() wants the curve's own direction of travel at each end: leaving it at the start,
		// carrying on past it at the end. Those are opposite ways round from "outward".
		Vec3 fixed = outward == null ? null : role == Role.START ? outward : outward.scale(-1);

		return new Endpoint(null, anchor.getBlockPos(), false, fixed, anchor.isLinked(), false, side);
	}

	// ------------------------------------------------------------- geometry

	private static Plan solve(Level level, Endpoint start, Endpoint end, float angleOffsetDegrees,
		boolean forward, boolean reverseEnd) {
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

		Plan plan = new Plan(null, start, end, startHeading, endHeading, curve, adjustable, turn, forward,
			reverseEnd);
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

		// Whichever side is already spoken for is the one with a curve on it. Exactly one is, or
		// the endpoint would not have got this far.
		boolean neighbourIsNext = anchor.getNext() != null;
		BlockPos neighbourPos = neighbourIsNext ? anchor.getNext() : anchor.getPrev();
		if (neighbourPos == null
			|| !(level.getBlockEntity(neighbourPos) instanceof AngledTrackAnchorBlockEntity neighbour))
			return null;

		BlockPos pos = endpoint.anchorPos();
		Vec3 anchorHeading = anchor.heading();

		// The existing curve, written from this node outwards, so both it and the new curve are
		// measured leaving the same place and the comparison is between like and like.
		Vec3 here = neighbourIsNext ? AngledTrackAnchorBlockEntity.exitNode(pos, anchorHeading, true)
			: AngledTrackAnchorBlockEntity.entryNode(pos, anchorHeading, true);
		Vec3 there = neighbourIsNext ? neighbour.entryNode() : neighbour.exitNode();
		if (here.distanceToSqr(there) < 1.0E-6)
			return null;

		Vec3 outward = neighbourIsNext ? anchorHeading : anchorHeading.scale(-1);
		Vec3 farTangent = neighbourIsNext ? neighbour.heading()
			.scale(-1) : neighbour.heading();

		BezierConnection adjoining = new BezierConnection(Couple.create(pos, neighbourPos),
			Couple.create(here, there), Couple.create(outward, farTangent), Couple.create(UP, UP), true, false,
			TrackMaterial.ANDESITE);

		Vec3 chord = adjoining.getPosition(0.25)
			.subtract(adjoining.getPosition(0));
		if (chord.lengthSqr() < 1.0E-8)
			return null;

		Vec3 outwards = chord.normalize();

		// What Create compares is an arrival with a departure, and the two ends of the new curve are
		// on opposite sides of that. The new curve leaves a start, so what has to match it is the way
		// the old one arrives — the opposite of the way it leaves, since two edges that continue one
		// another leave their shared node in opposite directions. The new curve arrives at an end, so
		// what has to match it is the way the old one leaves, as it is.
		//
		// This used to turn it round for both, which is right for a start and puts an end 180 degrees
		// out: any curve finishing at an anchor that already had a route through it was refused as
		// too sharp however gently it arrived.
		return role == Role.START ? outwards.scale(-1) : outwards;
	}

	// ------------------------------------------------------------- straight

	/**
	 * How far either side of the ideal endpoint to look for a block to finish on.
	 *
	 * <p>Three is enough to find a block centre within about a tenth of a block of the line at any
	 * useful length, which is a tenth of a degree over sixteen blocks. Widening it further only
	 * trades bearing accuracy the player cannot see for length they asked for and can.
	 */
	private static final int STRAIGHT_SEARCH = 3;

	/**
	 * How far the line may come out from the length asked for, in blocks.
	 *
	 * <p>A block and a half. Within that the search is free to pick whichever block centre sits
	 * closest to the bearing, which at sixteen blocks brings the average bearing error under half a
	 * degree and at thirty-two under a quarter. Allowing more length drift buys a little more
	 * bearing accuracy and costs a line that is visibly not the length that was typed.
	 */
	private static final double STRAIGHT_LENGTH_TOLERANCE = 1.5;

	/**
	 * A dead straight route at a bearing, and what that bearing turned out to be.
	 *
	 * <p>{@link #bearing} and {@link #length} are what was actually achieved, not what was asked
	 * for. Both anchors put their node at their block's centre, so the line can only run between
	 * two of those — and the one nearest the bearing asked for is rarely exactly on it. Aiming both
	 * ends at the line between the two centres is what keeps the track perfectly straight; the
	 * price is a bearing off by a fraction of a degree, which is reported rather than hidden.
	 */
	public record StraightPlan(@Nullable String error, BlockPos endPos, Vec3 heading, double bearing,
		double length) {

		static StraightPlan failed(String error) {
			return new StraightPlan(error, BlockPos.ZERO, Vec3.ZERO, 0, 0);
		}

		public boolean valid() {
			return error == null;
		}

		/** Whether there is a line to draw, which a refusal may still have. */
		public boolean hasGeometry() {
			return heading.lengthSqr() > 0;
		}
	}

	/**
	 * The straightest run of track at {@code requestedBearing} that the block grid allows.
	 *
	 * <p>Picks the block to finish on by how far its centre sits from the line, not by how close it
	 * lands to the requested length: the sideways miss is the bearing error, and the bearing is
	 * what was asked for. Length is a mild preference on top, enough to break ties and to stop the
	 * search wandering, and what it settles on is reported back.
	 */
	public static StraightPlan planStraight(Level level, BlockPos from, float requestedBearing, int requestedLength) {
		int maxLength = AllConfigs.server().trains.maxTrackPlacementLength.get();
		if (requestedLength > maxLength)
			return StraightPlan.failed("too_far");
		if (requestedLength < MIN_SPAN + 1)
			return StraightPlan.failed("too_close");

		Vec3 ideal = flatUnit(AngledTrackAnchorBlockEntity.directionFromYaw(requestedBearing));
		Vec3 start = Vec3.atBottomCenterOf(from);
		BlockPos around = BlockPos.containing(start.add(ideal.scale(requestedLength)));

		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;

		for (int x = -STRAIGHT_SEARCH; x <= STRAIGHT_SEARCH; x++) {
			for (int z = -STRAIGHT_SEARCH; z <= STRAIGHT_SEARCH; z++) {
				BlockPos candidate = around.offset(x, 0, z);
				if (candidate.equals(from))
					continue;

				Vec3 reach = flat(Vec3.atBottomCenterOf(candidate)
					.subtract(start));
				double along = reach.dot(ideal);
				if (along < MIN_SPAN)
					continue;

				// Length is a hard tolerance and the bearing is what is minimised inside it. Scoring
				// the two against each other instead lets a block a long way short of the length
				// win on bearing, and a line that comes out two thirds the length asked for is not
				// a rounding, it is the wrong line.
				if (Math.abs(along - requestedLength) > STRAIGHT_LENGTH_TOLERANCE)
					continue;

				double sideways = reach.subtract(ideal.scale(along))
					.length();
				double score = sideways + 0.02 * Math.abs(along - requestedLength);
				if (score >= bestScore)
					continue;

				bestScore = score;
				best = candidate;
			}
		}

		if (best == null)
			return StraightPlan.failed("too_close");

		Vec3 span = flat(Vec3.atBottomCenterOf(best)
			.subtract(start));
		Vec3 heading = span.normalize();

		// Blocked, but the line is still worked out — the preview draws it in red, which says far
		// more than the message does on its own: it is the difference between being told "no room"
		// and being shown where the obstruction is.
		String error = level.getBlockState(best)
			.canBeReplaced() || level.getBlockEntity(best) instanceof AngledTrackAnchorBlockEntity ? null
				: "no_room";

		return new StraightPlan(error, best, heading, AngledTrackAnchorBlockEntity.yawOf(heading), span.length());
	}

	/**
	 * The curve {@link #applyStraight} is going to build, so it can be drawn before it is.
	 *
	 * <p>Assembled the same way the anchors will assemble it once linked — same node positions,
	 * same tangents — rather than approximated, so the ghost is the track rather than a likeness of
	 * it. {@code exitNode} and {@code entryNode} decide between the block centre and its boundary
	 * on the heading, which is the one thing a straight line at an arbitrary bearing turns on.
	 */
	public static BezierConnection straightCurve(BlockPos from, StraightPlan plan) {
		Vec3 heading = plan.heading();
		return new BezierConnection(Couple.create(from, plan.endPos()),
			Couple.create(AngledTrackAnchorBlockEntity.exitNode(from, heading, false),
				AngledTrackAnchorBlockEntity.entryNode(plan.endPos(), heading, false)),
			Couple.create(heading, heading.scale(-1)), Couple.create(UP, UP), true, false, TrackMaterial.ANDESITE);
	}

	/** Builds what {@link #planStraight} described. Server side only. */
	public static boolean applyStraight(Level level, BlockPos from, StraightPlan plan) {
		if (!plan.valid())
			return false;

		// Both ends take the same heading, which is the line between the two block centres — so the
		// bezier between them has collinear tangents and is a straight line rather than nearly one.
		placeAnchor(level, from, plan.heading());
		placeAnchor(level, plan.endPos(), plan.heading());

		if (!(level.getBlockEntity(from) instanceof AngledTrackAnchorBlockEntity start))
			return false;
		if (!(level.getBlockEntity(plan.endPos()) instanceof AngledTrackAnchorBlockEntity end))
			return false;

		start.linkTo(end, plan.heading(), plan.heading());
		return true;
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
