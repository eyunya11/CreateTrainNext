package com.eyunya.createtrainnext.track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.CTNRegistry;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
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

	/**
	 * Whether an end of a curve is a branch leaving an anchor that a route already runs through, and on which
	 * side: alongside the curve to its next ({@code AHEAD}), or the one from its prev ({@code BEHIND}). See
	 * {@link AngledTrackAnchorBlockEntity#getBranches}.
	 */
	public enum Branch {
		NONE,
		AHEAD,
		BEHIND
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
	static final double MAX_TURN_DEGREES = 135;

	/** Below this the curve has no room to be a curve, and the two anchors would overlap. */
	static final double MIN_SPAN = 1.5;

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
	static final double MIN_RADIUS = 2.0;

	/**
	 * How far a curve may stray from itself read the other way round, in blocks.
	 *
	 * <p>Create builds the graph's edge for each direction of travel from the same curve, but not
	 * as the same curve: the reverse edge is {@code secondary()}, which works the curve out afresh
	 * from the other end, and the length of a bezier's handles is taken from the distance of its
	 * <em>second</em> end to where the normals at the two ends cross. That distance is the same from
	 * either end only when the tangents at the two ends make equal angles with the chord — a circular
	 * arc does, which is why Create's own curves never show it. Anything else comes out as two
	 * different shapes, up to several blocks apart: the one that is drawn, and the one trains take the
	 * other way.
	 *
	 * <p>A tenth of a block is well inside what anyone can see on a rail, and what is left once
	 * {@link Biarc} has split a curve in two.
	 */
	public static final double MAX_ORIENTATION_DEVIATION = 0.1;

	private static final int ORIENTATION_SAMPLES = 120;

	/**
	 * {@code curves.maxGrade} where the config cannot be read, in permille: 45 degrees, which is how steeply
	 * Create's own sloped track climbs, and so how steep a curve docked onto one is at that end.
	 */
	static final int DEFAULT_MAX_GRADE = 1000;

	/** {@code curves.snapAngle} where the config cannot be read, in degrees. */
	static final int DEFAULT_SNAP_ANGLE = 15;

	/**
	 * How near to an angle on the grid a heading has to be for the grid to win, as a fraction of the step:
	 * a quarter either side, so that half of all headings are drawn to one and the other half are left as
	 * they are, and the hand-turning notches of two and a half degrees stop on each angle three times.
	 */
	private static final double SNAP_WINDOW = 0.25;

	/** A heading this near to an angle on the grid is on it, in degrees. */
	private static final double SNAP_EXACT = 1.0E-6;

	/**
	 * How many blocks either way an aimed block may be moved to make its curve arrive at an angle on the
	 * grid exactly. Far enough to cover the window at the longest span, not so far that the curve ends
	 * somewhere the player was not looking.
	 */
	private static final int SNAP_SEARCH = 2;

	/** The least climb an end that is meant to climb is given: about a hundredth of a permille. */
	private static final double MIN_SLOPE = 1.0E-5;

	/** How many steps along a curve its steepest point is looked for in. */
	private static final int GRADE_SAMPLES = 48;

	/** How many steps a curve is measured in, to be told how long it is seen from above. */
	private static final int LENGTH_SAMPLES = 96;

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
	 * @param fixedSlope   the climb this end is obliged to have, in the direction the curve travels,
	 *                     where {@code fixedHeading} is set: zero for Create's flat track, one up or
	 *                     down for its sloped track. Not read where the end is free.
	 * @param dockNode     where this end's node is when it is docked onto the end of Create's sloped
	 *                     track: the slope's own end node, which the anchor will share. Null for every
	 *                     other end, whose node follows from its anchor's heading and links.
	 * @param branch       whether this end is a branch out of an anchor a route runs through, and which side
	 *                     of it. Such an end may be either end of the link, since the anchor keeps a branch
	 *                     apart from its prev and next, so its {@code side} is {@link Side#EITHER}.
	 */
	public record Endpoint(@Nullable String error, BlockPos anchorPos, boolean mustPlace,
		@Nullable Vec3 fixedHeading, boolean interior, boolean docked, Side side, double fixedSlope,
		@Nullable Vec3 dockNode, Branch branch) {

		/** An end that is not docked onto a slope. */
		public Endpoint(@Nullable String error, BlockPos anchorPos, boolean mustPlace, @Nullable Vec3 fixedHeading,
			boolean interior, boolean docked, Side side, double fixedSlope) {
			this(error, anchorPos, mustPlace, fixedHeading, interior, docked, side, fixedSlope, null);
		}

		/** An end that is not a branch. */
		public Endpoint(@Nullable String error, BlockPos anchorPos, boolean mustPlace, @Nullable Vec3 fixedHeading,
			boolean interior, boolean docked, Side side, double fixedSlope, @Nullable Vec3 dockNode) {
			this(error, anchorPos, mustPlace, fixedHeading, interior, docked, side, fixedSlope, dockNode, Branch.NONE);
		}

		static Endpoint failed(String error) {
			return new Endpoint(error, BlockPos.ZERO, false, null, false, false, Side.EITHER, 0.0);
		}

		/** This end moved to another height in the same column, for a grade to choose where it goes. */
		Endpoint atHeight(int y) {
			return new Endpoint(error, anchorPos.atY(y), mustPlace, fixedHeading, interior, docked, side, fixedSlope,
				dockNode, branch);
		}

		/** This end moved to another block at the same height, for an angle to choose where it goes. */
		Endpoint movedTo(BlockPos pos) {
			return new Endpoint(error, pos, mustPlace, fixedHeading, interior, docked, side, fixedSlope, dockNode,
				branch);
		}

		/** Whether this end is a branch out of an anchor a route already runs through. */
		public boolean branches() {
			return branch != Branch.NONE;
		}

		/**
		 * Whether this end's heading has to be written to the anchor when the curve is built. An end docked
		 * onto a slope always does: its anchor may be one that was standing there on its own.
		 */
		boolean needsAiming() {
			return mustPlace || fixedHeading == null || dockNode != null;
		}

		/** Whether this end is docked onto Create's sloped track, which holds the curve to the slope's line. */
		public boolean onSlope() {
			return dockNode != null;
		}

		/**
		 * The normal of the rails at this end of the curve: the slope's, for an end that shares a node with one,
		 * and straight up for every other. It is what the anchor will give the node
		 * ({@link AngledTrackAnchorBlockEntity#nodeNormal}), so the ghost and the curve built are alike.
		 */
		Vec3 normal() {
			return onSlope() ? AngledTrackAnchorBlockEntity.slopeNormal(fixedHeading, fixedSlope) : UP;
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
	 *
	 * <p>A curve whose ends are not at equal angles to its chord is a different shape read the other
	 * way round, so it is not built as one curve: {@link #junction} is where an anchor goes to make
	 * it two circular arcs, {@link #curve} is then the first of them and {@link #secondCurve} the
	 * second. See {@link Biarc}.
	 *
	 * <p>The headings are horizontal. How steeply the route climbs at each end is {@link #startSlope}
	 * and {@link #endSlope}, in the direction the curve was drawn, and {@link #junctionSlope} is the
	 * same at the anchor between two arcs.
	 *
	 * <p>{@link #grade} is the grade the curve was laid at, and is null for a curve that was not: either
	 * grade mode was off, or the far end was something that already has a height — an anchor, or Create's
	 * track — and the curve was worked out the way it is without the mode. When it is set, the far end
	 * of {@link #end} has been moved to the height the grade gives it.
	 */
	public record Plan(@Nullable String error, @Nullable Endpoint start, @Nullable Endpoint end,
		@Nullable Vec3 startHeading, @Nullable Vec3 endHeading, @Nullable BezierConnection curve,
		boolean adjustable, double turnDegrees, boolean forward, boolean reverseEnd,
		@Nullable Biarc.Junction junction, @Nullable BezierConnection secondCurve, double startSlope,
		double endSlope, double junctionSlope, @Nullable GradeSpec grade) {

		static Plan failed(String error) {
			return new Plan(error, null, null, null, null, null, false, 0, true, false, null, null, 0, 0, 0, null);
		}

		Plan withError(String newError) {
			return new Plan(newError, start, end, startHeading, endHeading, curve, adjustable, turnDegrees,
				forward, reverseEnd, junction, secondCurve, startSlope, endSlope, junctionSlope, grade);
		}

		public boolean valid() {
			return error == null;
		}

		/** What would be built, from the start to the end: one curve, or two with a point between. */
		public List<BezierConnection> curves() {
			if (curve == null)
				return List.of();
			return secondCurve == null ? List.of(curve) : List.of(curve, secondCurve);
		}

		public double length() {
			double length = 0;
			for (BezierConnection each : curves())
				length += each.getLength();
			return length;
		}

		/**
		 * How far the link goes up from its first node to its last, in blocks; minus for a descent. Whole
		 * blocks, because a node is at the height of its block.
		 */
		public double rise() {
			if (curve == null)
				return 0;
			BezierConnection last = secondCurve != null ? secondCurve : curve;
			return last.starts.getSecond().y - curve.starts.getFirst().y;
		}

		/** How far the link goes along the ground: the length of its curves seen from above. */
		public double horizontalLength() {
			double length = 0;
			for (BezierConnection each : curves())
				length += CurvePlanner.horizontalLength(each);
			return length;
		}

		/**
		 * The steepest the link is anywhere along it, as a fraction. This is what the limit is held to,
		 * and it can be well over {@code rise / horizontalLength}: a curve that eases into a climb is
		 * steeper than its average where it finishes.
		 */
		public double steepestGrade() {
			double steepest = 0;
			for (BezierConnection each : curves())
				steepest = Math.max(steepest, maxLocalGrade(each));
			return steepest;
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
			return anchor.hasFreeSide() || anchor.canBranch();

		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ITrackBlock track))
			return false;

		// The same refusal resolve() makes, asked here so that track no curve can use is refused by the click
		// that picked it rather than by every click after it. A slope can be started from, at either end, and a
		// crossing from the end of either of its lines.
		return axisToward(track.getTrackAxes(level, pos, state), Vec3.ZERO) != null;
	}

	/**
	 * Which of the axes Create's track at a block reports a curve going {@code toOther} would use: its one
	 * axis, or of a crossing's two, the one that runs nearer the way the curve goes, so that the curve
	 * carries on the line it is drawn out of. Null where there are several and one is not level: Create has no
	 * such track, every one of its crossings being two level lines, but another mod's track block may, and
	 * which end of it a curve should join is not something to guess.
	 *
	 * <p>A crossing puts the nodes at the ends of each of its lines where a straight piece of track along
	 * that line would ({@code TrackBlock#getConnected}), so the end of either line is as good a place to
	 * join as the end of any straight track.
	 */
	@Nullable
	private static Vec3 axisToward(List<Vec3> axes, Vec3 toOther) {
		if (axes.size() == 1)
			return axes.get(0);
		Vec3 best = null;
		double bestAlong = -1;
		for (Vec3 axis : axes) {
			if (Math.abs(axis.y) > 1.0E-4)
				return null;
			double along = toOther.lengthSqr() < 1.0E-8 ? 0 : Math.abs(axis.normalize()
				.dot(toOther.normalize()));
			if (along > bestAlong) {
				bestAlong = along;
				best = axis;
			}
		}
		return best;
	}

	/**
	 * The slope a curve drawn from the block {@code startClick} would leave it at: that of the route
	 * through an anchor there, in the direction the new curve would go, and nothing for anywhere else.
	 * Create's flat track is level, and an anchor with no curve on it yet has no slope to keep.
	 *
	 * <p>Create's sloped track is as steep as it is. Which way a curve leaves it, up or down, is a matter
	 * of the end it leaves by, which is not known until the curve is aimed; this gives the climb without the
	 * sign, which is all {@link #startsLevel} asks of it.
	 */
	public static double startSlope(Level level, BlockPos startClick) {
		if (!(level.getBlockEntity(startClick) instanceof AngledTrackAnchorBlockEntity anchor)) {
			BlockState state = level.getBlockState(startClick);
			if (state.getBlock() instanceof ITrackBlock track) {
				List<Vec3> axes = track.getTrackAxes(level, startClick, state);
				if (axes.size() == 1)
					return Math.abs(AngledTrackAnchorBlockEntity.slopeOf(axes.get(0)));
			}
			return 0;
		}
		Endpoint endpoint = fromExisting(anchor, Role.START, null);
		// A branch may leave either way, up or down the line's climb, which is the same climb.
		if (endpoint.branches())
			return Math.abs(endpoint.fixedSlope());
		return endpoint.error() != null || endpoint.fixedHeading() == null ? 0 : endpoint.fixedSlope();
	}

	/**
	 * Whether a curve drawn from {@code startClick} leaves level, to the permille that is shown. This
	 * is what decides whether asking for no grade is asking for anything: from level ground it is not,
	 * and from a climb it is the request to level off.
	 */
	public static boolean startsLevel(Level level, BlockPos startClick) {
		return GradeText.permille(startSlope(level, startClick)) == 0;
	}

	/** {@link #plan(Level, BlockPos, BlockPos, Direction, float, GradeSpec)} with no grade asked for. */
	public static Plan plan(Level level, BlockPos startClick, BlockPos hitPos, Direction hitFace,
		float angleOffsetDegrees) {
		return plan(level, startClick, hitPos, hitFace, angleOffsetDegrees, null);
	}

	/**
	 * The curve that clicking {@code hitPos} would draw, given a selection made at {@code startClick}.
	 *
	 * <p>With a grade, the click decides where the curve arrives on the ground and the grade decides how
	 * high: the far end is moved to the height that lets the curve change from the climb it leaves the
	 * start with to the grade asked for, at a steady rate along the way. For a curve whose horizontal
	 * handles are {@code h} long, whose climb at the start is {@code s0} and which is to arrive at
	 * {@code g}, that is a rise of {@code 1.5·h·(s0 + g)}, to a whole block because that is how high a
	 * node can be, and so it arrives at {@code rise / (1.5·h) − s0}, which is {@code g} less what the
	 * rounding took. Asking for no grade makes the curve arrive level instead. None of this applies
	 * where the far end already has a height of its own, which is any end but a new anchor on open ground.
	 *
	 * @param angleOffsetDegrees the player's hand adjustment to the free end's tangent.
	 * @param grade              the grade asked for, or null to take the far end at the height it was clicked.
	 */
	public static Plan plan(Level level, BlockPos startClick, BlockPos hitPos, Direction hitFace,
		float angleOffsetDegrees, @Nullable GradeSpec grade) {

		// Which side of a track block the curve should leave from depends on where the other end
		// is, and the other end has the same question about this one. Block centres break the
		// circle: they are within half a block of the answer, and the two only disagree when the
		// ends are close enough together that the curve is refused anyway.
		Vec3 roughStart = Vec3.atBottomCenterOf(startClick);
		Vec3 roughEnd = Vec3.atBottomCenterOf(hitPos);

		Endpoint start = resolve(level, startClick, null, roughEnd, Role.START, false);
		if (start.error() != null)
			return Plan.failed(start.error());

		// In grade mode the height of a new anchor is not the height that was clicked, so whether there
		// is room at that height is not the question; it is asked once the real height is known.
		Endpoint end = resolve(level, hitPos, hitFace, roughStart, Role.END, grade != null);
		if (end.error() != null)
			return Plan.failed(end.error());

		return connect(level, start, end, angleOffsetDegrees, grade, snapStepDegrees());
	}

	/**
	 * The curve between two ends that have been resolved: which way round the link runs, and the
	 * geometry. Shared by a click, which resolves the far end from the block that was aimed at, and by
	 * a distance, which chooses it.
	 */
	private static Plan connect(Level level, Endpoint start, Endpoint end, float angleOffsetDegrees,
		@Nullable GradeSpec grade, int snap) {
		// A curve off Create's sloped track runs straight on along the slope's line (see slopeError), so
		// from a slope the far end is the block on that line nearest the one aimed at, when that is open
		// ground. Moved there, it is looked at again: an anchor may already stand on the line, and the
		// block it was moved to may be in the way.
		boolean blocked = false;
		if (start.onSlope() && end.mustPlace() && end.fixedHeading() == null) {
			BlockPos onLine = ontoLine(start, end.anchorPos());
			// Half of it comes from the dock and half from the aimed block, so it can be in a chunk
			// nobody is looking at; reading it there would load it, on every click. Not loaded is no room.
			if (level.isOutsideBuildHeight(onLine) || !level.isLoaded(onLine)) {
				end = end.movedTo(onLine);
				blocked = true;
			} else if (level.getBlockEntity(onLine) instanceof AngledTrackAnchorBlockEntity there) {
				end = fromExisting(there, Role.END, Vec3.atBottomCenterOf(start.anchorPos()));
				if (end.error() != null)
					return Plan.failed(end.error());
			} else {
				end = end.movedTo(onLine);
				blocked = grade == null && !level.getBlockState(onLine)
					.canBeReplaced();
			}
		}

		if (start.anchorPos()
			.equals(end.anchorPos()))
			return Plan.failed("same_anchor");
		// A link is somebody's next and somebody's prev, and a turnout keeps a branch apart from both, so a
		// curve between two turnouts would be no anchor's. A plain anchor between them does it.
		if (start.branches() && end.branches())
			return Plan.failed("branch_to_branch");
		// Two anchors are joined by one curve, and linking them again takes away the link they have: a second
		// curve between them would quietly replace the first, and a branch to the anchor on the line beside it
		// would leave the turnout with no line on that side.
		if (level.getBlockEntity(start.anchorPos()) instanceof AngledTrackAnchorBlockEntity from
			&& level.getBlockEntity(end.anchorPos()) instanceof AngledTrackAnchorBlockEntity to
			&& (from.isLinkedTo(to.getBlockPos()) || to.isLinkedTo(from.getBlockPos())))
			return Plan.failed("already_linked");

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

		// The grid has nothing to move on a curve along a slope's line: its far end is either fixed or, taken
		// onto the line above, already arrives along an axis, which is on the grid.
		Endpoint aimed = end;
		if (snap > 0 && Math.abs(angleOffsetDegrees) < 1.0E-4)
			end = snappedTarget(level, start, end, grade, snap);

		Plan plan = roomAtEnd(level, solve(level, start, end, angleOffsetDegrees, forward, reverseEnd, grade, snap));

		// The grid is a preference. A curve it makes impossible — a turn it makes too sharp, an anchor it has
		// nowhere to put — is not worth an angle, so the curve is the one the aimed block gives, whether that
		// is any better or not: the preview should say what pointing there does, not what the grid wished.
		if (snap > 0 && !plan.valid())
			plan = roomAtEnd(level, solve(level, start, aimed, angleOffsetDegrees, forward, reverseEnd, grade, 0));

		// Last, so that nothing above can work the curve out again without it.
		return blocked && plan.valid() ? plan.withError("no_room") : plan;
	}

	/**
	 * The block on the line an end docked onto a slope runs along, at {@code pos}'s height and as near it
	 * as the line goes. A slope lies along one of the four axes, so this is {@code pos} with one coordinate
	 * taken from the docked anchor.
	 */
	private static BlockPos ontoLine(Endpoint dock, BlockPos pos) {
		Vec3 heading = dock.fixedHeading();
		return Math.abs(heading.x) > Math.abs(heading.z) ? new BlockPos(pos.getX(), pos.getY(), dock.anchorPos()
			.getZ()) : new BlockPos(dock.anchorPos()
				.getX(), pos.getY(), pos.getZ());
	}

	/**
	 * A plan whose far end was put at a height of the grade's choosing, refused if there is no room for
	 * an anchor at that height. The ghost still draws the curve in red, which shows where the block that
	 * is in the way is.
	 */
	private static Plan roomAtEnd(Level level, Plan plan) {
		if (!plan.valid() || plan.grade() == null)
			return plan;

		BlockPos pos = plan.end()
			.anchorPos();
		boolean room = !level.isOutsideBuildHeight(pos) && level.isLoaded(pos) && level.getBlockState(pos)
			.canBeReplaced() && !(level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity);
		return room ? plan : plan.withError("no_room");
	}

	/**
	 * The curve that leaves the block {@code startClick} straight ahead and runs {@code horizontalLength}
	 * along the ground at a grade, for when the place it should go is a distance and not a block that
	 * can be aimed at.
	 *
	 * <p>Ahead is the way the route already runs there: out of the side of an anchor that is free, or
	 * away from a piece of Create's track on whichever of its two sides {@code look} faces. An anchor
	 * that is on no route yet can be left either way, and goes the way it points or the other, whichever
	 * {@code look} is nearer to.
	 *
	 * <p>The far end is a block, and the one nearest the line ahead whose distance is within a block and
	 * a half of the one asked for, the way {@link #planStraight} chooses; the curve to it is then worked
	 * out as any other. So it is straight to within the width of a block, and a little over or under the
	 * length asked for, which is said when it is laid.
	 *
	 * @param look which way the player faces, horizontally; used only where the start does not decide.
	 */
	public static Plan planByDistance(Level level, BlockPos startClick, GradeSpec grade, double horizontalLength,
		Vec3 look) {
		if (horizontalLength < MIN_SPAN + 1)
			return Plan.failed("too_close");
		if (horizontalLength > AllConfigs.server().trains.maxTrackPlacementLength.get())
			return Plan.failed("too_far");

		Vec3 facing = flatUnit(look);
		Endpoint start = resolve(level, startClick, null, Vec3.atBottomCenterOf(startClick)
			.add(facing.scale(8)), Role.START, false);
		if (start.error() != null)
			return Plan.failed(start.error());

		Vec3 ahead;
		if (start.fixedHeading() != null) {
			ahead = flatUnit(start.fixedHeading());
		} else if (level.getBlockEntity(start.anchorPos()) instanceof AngledTrackAnchorBlockEntity anchor) {
			Vec3 pointing = flatUnit(anchor.heading());
			ahead = pointing.dot(facing) >= 0 ? pointing : pointing.scale(-1);
		} else {
			return Plan.failed("invalid_start");
		}

		// Where the line ahead leaves the start's node, which is the block's centre if it climbs and is
		// on a route, and its boundary otherwise; the climb is not known yet, so a start that has none of
		// its own is taken to be climbing. A start docked onto a slope leaves from the slope's end.
		boolean climbs = start.fixedHeading() == null || start.fixedSlope() != 0;
		Vec3 from = leavingPoint(start, ahead, climbs);
		BlockPos around = BlockPos.containing(from.add(ahead.scale(horizontalLength)));

		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (int x = -STRAIGHT_SEARCH; x <= STRAIGHT_SEARCH; x++) {
			for (int z = -STRAIGHT_SEARCH; z <= STRAIGHT_SEARCH; z++) {
				BlockPos candidate = new BlockPos(around.getX() + x, start.anchorPos()
					.getY(), around.getZ() + z);
				if (candidate.equals(start.anchorPos()) || !level.isLoaded(candidate))
					continue;

				Vec3 reach = flat(Vec3.atBottomCenterOf(candidate)
					.subtract(from));
				double along = reach.dot(ahead);
				if (along < MIN_SPAN || Math.abs(along - horizontalLength) > STRAIGHT_LENGTH_TOLERANCE)
					continue;

				double sideways = reach.subtract(ahead.scale(along))
					.length();
				double score = sideways + 0.02 * Math.abs(along - horizontalLength);
				if (score < bestScore) {
					bestScore = score;
					best = candidate;
				}
			}
		}
		if (best == null)
			return Plan.failed("no_room");

		// Open ground, at the start's height: the grade moves it to the height it gives.
		// Straight ahead is the way the route runs, which the angle grid has no business bending, so none.
		Endpoint end = new Endpoint(null, best, true, null, false, false, Side.EITHER, 0.0);
		return connect(level, start, end, 0.0F, grade, 0);
	}

	/**
	 * Builds what {@code plan} described. Server side only; the client draws it instead.
	 *
	 * @return why it could not be built, as the key of a wand message, or null once it has been.
	 *         Nothing is changed when a reason comes back: a route that was turned round is turned
	 *         back and an anchor that was placed is taken out again. Only what was cleared to make room
	 *         for an anchor — grass and the like — stays gone.
	 */
	@Nullable
	public static String apply(Level level, Plan plan) {
		if (!plan.valid())
			return plan.error();

		// Before anything is placed, so that a route which cannot be turned leaves the world just as
		// it found it. Whether the whole of it can be reached is only known here: the ghost was drawn
		// from what the client has loaded, which can be less than the server does.
		boolean reversed = false;
		if (plan.reverseEnd()) {
			if (!(level.getBlockEntity(plan.end()
				.anchorPos()) instanceof AngledTrackAnchorBlockEntity turning) || turning.reverseRoute() == 0)
				return "route_unavailable";
			reversed = true;
		}

		// Everything from here on can still fail for want of room, and by then the route has been
		// turned and anchors may be standing, so what is done is written down to be undone.
		List<BlockPos> placed = new ArrayList<>();
		place(level, plan.start(), plan.startHeading(), placed);
		place(level, plan.end(), plan.endHeading(), placed);

		if (!(level.getBlockEntity(plan.start()
			.anchorPos()) instanceof AngledTrackAnchorBlockEntity startAnchor))
			return abandon(level, plan, reversed, placed);
		if (!(level.getBlockEntity(plan.end()
			.anchorPos()) instanceof AngledTrackAnchorBlockEntity endAnchor))
			return abandon(level, plan, reversed, placed);

		// Anchors store their heading pointing downstream, and downstream is whichever way the link
		// runs. Drawn backwards, the two headings are the negatives of the curve's own directions —
		// and for the anchor being continued backwards out of, that negation lands exactly on the
		// heading it already had, which is what leaves the curve on its other side undisturbed.
		AngledTrackAnchorBlockEntity from = plan.forward() ? startAnchor : endAnchor;
		AngledTrackAnchorBlockEntity to = plan.forward() ? endAnchor : startAnchor;
		// The climb goes with the heading, as the y of the vector. Turning a vector round turns the
		// climb round with it, so an anchor continued backwards gets the slope it had.
		Vec3 startAim = AngledTrackAnchorBlockEntity.tangentOf(plan.startHeading(), plan.startSlope());
		Vec3 endAim = AngledTrackAnchorBlockEntity.tangentOf(plan.endHeading(), plan.endSlope());
		Vec3 fromHeading = plan.forward() ? startAim : endAim.scale(-1);
		Vec3 toHeading = plan.forward() ? endAim : startAim.scale(-1);
		Endpoint fromEnd = plan.forward() ? plan.start() : plan.end();
		Endpoint toEnd = plan.forward() ? plan.end() : plan.start();

		// Aiming an end that was already aimed would only feed its own heading back through a yaw
		// and out again, losing a little of it to float each time a route was extended.
		//
		// An end that stops being one reports a different axis, and linkTo holds the sign it had, so
		// that whatever is standing on it stays facing the same way.
		Vec3 fromAim = fromEnd.needsAiming() ? fromHeading : null;
		Vec3 toAim = toEnd.needsAiming() ? toHeading : null;
		// An end on a slope is docked onto it in the same step as it is linked, so that its node is on the
		// slope's end from the moment the graph is rebuilt around it.
		boolean fromDocks = fromEnd.onSlope();
		boolean toDocks = toEnd.onSlope();

		Biarc.Junction junction = plan.junction();
		if (junction == null)
			return link(from, fromEnd, to, toEnd, fromAim, toAim, fromDocks, toDocks) ? null
				: abandon(level, plan, reversed, placed, "branch_taken");

		// Two arcs, and an anchor where they meet. It points the way the route runs there, which is
		// the junction's heading when the link runs the way the curve was drawn and its opposite when
		// it runs the other way, like the two ends'.
		Vec3 middleAim = AngledTrackAnchorBlockEntity.tangentOf(junction.heading(), plan.junctionSlope());
		Vec3 middleHeading = plan.forward() ? middleAim : middleAim.scale(-1);
		placeAnchor(level, junction.pos(), middleHeading);
		placed.add(junction.pos());
		if (!(level.getBlockEntity(junction.pos()) instanceof AngledTrackAnchorBlockEntity middle))
			return abandon(level, plan, reversed, placed);

		if (!link(from, fromEnd, middle, null, fromAim, middleHeading, fromDocks, false)
			|| !link(middle, null, to, toEnd, null, toAim, false, toDocks))
			return abandon(level, plan, reversed, placed, "branch_taken");
		return null;
	}

	/**
	 * Links {@code from} to {@code to}, so that the route runs from one into the other: as one's next and the
	 * other's prev, or, where either end is a branch out of a turnout, as the turnout's branch and the other's
	 * prev or next. The two cannot both be branches; {@link #connect} refuses that.
	 *
	 * @param fromEnd what the plan said about {@code from}, or null for the anchor between two arcs.
	 * @param toEnd   the same for {@code to}.
	 * @return whether they were linked: a branch is not, on a side of the turnout with no room left.
	 */
	private static boolean link(AngledTrackAnchorBlockEntity from, @Nullable Endpoint fromEnd,
		AngledTrackAnchorBlockEntity to, @Nullable Endpoint toEnd, @Nullable Vec3 fromAim, @Nullable Vec3 toAim,
		boolean fromDocks, boolean toDocks) {
		if (fromEnd != null && fromEnd.branches())
			return from.branchTo(to, fromEnd.branch() == Branch.AHEAD, true, toAim, toDocks, null);
		if (toEnd != null && toEnd.branches())
			return to.branchTo(from, toEnd.branch() == Branch.AHEAD, false, fromAim, fromDocks, null);
		from.linkTo(to, fromAim, toAim, fromDocks, toDocks);
		return true;
	}

	/** Puts the world back as far as {@link #apply} had got, and says there was no room. */
	private static String abandon(Level level, Plan plan, boolean reversed, List<BlockPos> placed) {
		return abandon(level, plan, reversed, placed, "no_room");
	}

	/** The same, giving {@code reason} as why. */
	private static String abandon(Level level, Plan plan, boolean reversed, List<BlockPos> placed, String reason) {
		// Only where an anchor really stands: a block that could not be placed leaves nothing of ours
		// there, and what is there instead is not ours to remove.
		for (BlockPos pos : placed)
			if (level.getBlockState(pos)
				.is(CTNRegistry.ANGLED_TRACK_ANCHOR.get()))
				level.removeBlock(pos, false);

		// Turning a route round twice leaves it as it was.
		if (reversed && level.getBlockEntity(plan.end()
			.anchorPos()) instanceof AngledTrackAnchorBlockEntity turned)
			turned.reverseRoute();
		return reason;
	}

	private static void place(Level level, Endpoint endpoint, Vec3 heading, List<BlockPos> placed) {
		if (!endpoint.mustPlace())
			return;

		placeAnchor(level, endpoint.anchorPos(), heading);
		placed.add(endpoint.anchorPos());
	}

	/** Puts an anchor at {@code pos}, clearing whatever was there first. */
	static void placeAnchor(Level level, BlockPos pos, Vec3 heading) {
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
	 * @param heightFree whether a new anchor on open ground is going to be moved to a height of the
	 *                grade's choosing, so that whether the block clicked is empty says nothing about it.
	 */
	private static Endpoint resolve(Level level, BlockPos hitPos, @Nullable Direction face, Vec3 towards, Role role,
		boolean heightFree) {
		// An anchor was clicked outright.
		if (level.getBlockEntity(hitPos) instanceof AngledTrackAnchorBlockEntity anchor)
			return fromExisting(anchor, role, towards);

		BlockState state = level.getBlockState(hitPos);

		// Create's own track. The anchor goes in the block beside it so that the two share a node:
		// an end anchor puts its node half a block along its axis from its own centre, and so does
		// Create's track, so placing one directly alongside the other lands both on the same point
		// and the graph merges them. Anywhere else and the curve simply stops next to the track.
		if (state.getBlock() instanceof ITrackBlock track) {
			Vec3 toOther = flat(towards.subtract(Vec3.atBottomCenterOf(hitPos)));
			Vec3 axis = axisToward(track.getTrackAxes(level, hitPos, state), toOther);
			// Not "junction": that key is the ghost's note that an anchor goes between two arcs.
			if (axis == null)
				return Endpoint.failed("junction_track");

			// The side of the track that faces the rest of the curve. An axis serves either way
			// along itself, so this is the only thing that picks between the two.
			Vec3 side = axis.dot(toOther) < 0 ? axis.scale(-1) : axis;
			return trackEnd(level, hitPos, state, track, side, role, towards);
		}

		// Open ground: the anchor goes where a block would have gone. Only ever the far end of a
		// curve — a selection is only made on track, so reaching here as the start means whatever
		// was selected has been mined since, and putting an anchor above the hole is not what
		// anyone asked for.
		if (role == Role.START)
			return Endpoint.failed("invalid_start");

		BlockPos placePos = state.canBeReplaced() ? hitPos : hitPos.relative(face == null ? Direction.UP : face);
		if (level.getBlockEntity(placePos) instanceof AngledTrackAnchorBlockEntity existing)
			return fromExisting(existing, role, towards);
		if (!heightFree && !level.getBlockState(placePos)
			.canBeReplaced())
			return Endpoint.failed("no_room");

		return new Endpoint(null, placePos, true, null, false, false, Side.EITHER, 0.0);
	}

	/**
	 * The end of a piece of Create's track that faces the rest of the curve, as an end for the curve: level
	 * track, which the curve meets beside it, and sloped track, which it docks onto. One for both, so that
	 * where the anchor goes and what is in its way are worked out the same way for each.
	 *
	 * <p>The node is where Create puts it, half the track's axis from the centre of the block at the height
	 * of the track there ({@code ITrackBlock#getConnected}): on the boundary of the block beyond that end, at
	 * that block's floor. For level track that is the floor of the track's own block; for a slope it is a
	 * block above the slope's floor at the top and level with it at the foot. The anchor goes in the block
	 * beyond and shares the node — at a slope {@link AngledTrackAnchorBlockEntity#slopeDockNode} puts it
	 * there from the anchor's side, and is what is used here, so that the planner and the anchor cannot
	 * disagree — and the curve runs along the track's axis at that end, at its climb: out of the track at a
	 * start and into it at an end, so up and out of a slope's top, down and out of its foot.
	 *
	 * <p>An anchor already there is where they differ. Beside level track it is taken as it is, whatever it
	 * holds, since it shares the track's node either way. Beside a slope it has to become docked, and only one
	 * standing on its own can: a docked one has that end of the slope already, and the end of another route
	 * would be docked with a curve it already has, which nothing here measures against the slope, so it is in
	 * the way.
	 *
	 * @param side the track's axis, pointing out of the end that faces the rest of the curve.
	 * @param towards roughly where the other end of the curve is, for an anchor there that is to branch.
	 */
	private static Endpoint trackEnd(Level level, BlockPos hitPos, BlockState state, ITrackBlock track, Vec3 side,
		Role role, Vec3 towards) {
		Vec3 node = Vec3.atBottomCenterOf(hitPos)
			.add(0, track.getElevationAtCenter(level, hitPos, state), 0)
			.add(side.scale(0.5));
		BlockPos anchorPos = BlockPos.containing(node.add(flatUnit(side).scale(0.5)));

		// Headings always point downstream, so a route leaving a track points away from it and one arriving
		// points into it, climb and all. The track is behind a start and ahead of an end, the way it goes.
		Vec3 travel = role == Role.START ? side : side.scale(-1);
		Vec3 heading = flatUnit(travel);
		double slope = AngledTrackAnchorBlockEntity.slopeOf(travel);
		boolean sloped = Math.abs(side.y) > 1.0E-4;
		Vec3 dockNode = sloped ? AngledTrackAnchorBlockEntity.slopeDockNode(anchorPos, heading, role == Role.START) : null;

		if (level.getBlockEntity(anchorPos) instanceof AngledTrackAnchorBlockEntity existing) {
			if (!sloped)
				return fromExisting(existing, role, towards);
			if (existing.isSlopeDocked())
				return Endpoint.failed("dock_taken");
			if (existing.isLinked())
				return Endpoint.failed("no_room");
			return new Endpoint(null, anchorPos, false, heading, false, true, Side.EITHER, slope, dockNode);
		}
		if (!level.getBlockState(anchorPos)
			.canBeReplaced())
			return Endpoint.failed("no_room");
		return new Endpoint(null, anchorPos, true, heading, false, true, Side.EITHER, slope, dockNode);
	}

	/**
	 * What an anchor that already exists allows.
	 *
	 * <p>Each anchor holds one link in each direction, so a route runs through it. A curve leaves by the
	 * side that is still free, and the side that is taken is what fixes the heading: it belongs to the
	 * curve already there.
	 *
	 * <p>An anchor with no side free is one a route runs through, and a curve from it is a branch: it
	 * leaves along the route's heading, beside the line on whichever side faces {@code towards}, so that
	 * the turnout is made the way the player is pointing. A side that has a branch already has no room for
	 * another.
	 *
	 * @param towards roughly where the other end of the curve is; null where it is not known, which takes
	 *                the side ahead where there is a choice.
	 */
	private static Endpoint fromExisting(AngledTrackAnchorBlockEntity anchor, Role role, @Nullable Vec3 towards) {
		boolean nextFree = anchor.getNext() == null;
		boolean prevFree = anchor.getPrev() == null;

		// One docked onto a slope has the slope on the side without a curve, so neither is free; but it has
		// one curve, not two, and saying it had two would point the player at the wrong thing.
		if (anchor.isSlopeDocked())
			return Endpoint.failed("dock_taken");
		// Nor is the side of an end joined onto Create's level track, whose track is the line there.
		if (!anchor.hasFreeSide())
			return branchFrom(anchor, role, towards);

		Side side = nextFree && prevFree ? Side.EITHER : nextFree ? Side.NEXT : Side.PREV;
		// An anchor whose line has gone from both sides of a branch still points the way the branch runs, and
		// turning it would move the branch: a new curve goes out of whichever side faces the other end.
		if (side == Side.EITHER && anchor.hasBranch())
			side = aheadOf(anchor, towards) ? Side.NEXT : Side.PREV;

		// Which way a new curve leaves this anchor. An anchor already carrying one has its
		// direction settled — the route runs through it, so the new curve continues the line, and
		// whether that is forwards or backwards is decided by which side was left free rather than
		// by which of the two clicks landed here first.
		Vec3 outward = side == Side.EITHER ? null
			: side == Side.NEXT ? anchor.tangent()
				: anchor.tangent()
					.scale(-1);

		// solve() wants the curve's own direction of travel at each end: leaving it at the start,
		// carrying on past it at the end. Those are opposite ways round from "outward".
		Vec3 fixed = outward == null ? null : role == Role.START ? outward : outward.scale(-1);

		// The climb is read off the same vector, so it turns round with the heading: an anchor whose
		// route climbs, continued backwards out of its first anchor, is a descent for the new curve.
		// Whether it will have a route through it once linked, which is whether the other side has a line. An
		// anchor with branches and no line has none, and a branch does not move a node.
		boolean interior = side == Side.NEXT ? anchor.getPrev() != null
			: side == Side.PREV && anchor.getNext() != null;
		return new Endpoint(null, anchor.getBlockPos(), false, fixed == null ? null : flatUnit(fixed), interior,
			false, side, fixed == null ? 0.0 : AngledTrackAnchorBlockEntity.slopeOf(fixed));
	}

	/**
	 * A branch out of an anchor a route runs through, on the side that faces {@code towards}: see
	 * {@link #fromExisting}. It leaves along the route's heading on that side, climb and all, so that it
	 * meets the line there at no angle, which is what lets a train from the other side take either.
	 *
	 * <p>It leaves from the anchor's node on that side, which a branch does not move: the centre for an
	 * anchor in the middle of a route, the boundary of its block for an end joined onto Create's track.
	 */
	private static Endpoint branchFrom(AngledTrackAnchorBlockEntity anchor, Role role, @Nullable Vec3 towards) {
		boolean ahead = aheadOf(anchor, towards);
		if (!anchor.canBranch(ahead))
			return Endpoint.failed("branch_taken");

		Vec3 outward = ahead ? anchor.tangent() : anchor.tangent()
			.scale(-1);
		Vec3 fixed = role == Role.START ? outward : outward.scale(-1);
		boolean interior = anchor.getPrev() != null && anchor.getNext() != null;
		return new Endpoint(null, anchor.getBlockPos(), false, flatUnit(fixed), interior, false, Side.EITHER,
			AngledTrackAnchorBlockEntity.slopeOf(fixed), null, ahead ? Branch.AHEAD : Branch.BEHIND);
	}

	/** Whether {@code towards} lies ahead of an anchor, along its heading; ahead where it is not known. */
	private static boolean aheadOf(AngledTrackAnchorBlockEntity anchor, @Nullable Vec3 towards) {
		return towards == null || flat(towards.subtract(Vec3.atBottomCenterOf(anchor.getBlockPos())))
			.dot(anchor.heading()) >= 0;
	}

	// ------------------------------------------------------------- geometry

	private static Plan solve(Level level, Endpoint start, Endpoint clicked, float angleOffsetDegrees,
		boolean forward, boolean reverseEnd, @Nullable GradeSpec grade, int snap) {
		Vec3 fixedStart = start.fixedHeading() == null ? null : flatUnit(start.fixedHeading());
		Vec3 fixedEnd = clicked.fixedHeading() == null ? null : flatUnit(clicked.fixedHeading());

		// The climb each end is obliged to have, where something already decides it: an anchor with a
		// route through it, or Create's flat track, which has none. A free end has nothing to answer
		// to and is given one below, from how far the curve has to climb.
		Double fixedStartSlope = fixedStart == null ? null : start.fixedSlope();
		Double fixedEndSlope = fixedEnd == null ? null : clicked.fixedSlope();

		// Only a new anchor on open ground has a height for the grade to choose. One that exists, or
		// Create's own track, is where it is, and the curve to it is worked out as it always was.
		GradeSpec laid = grade != null && clicked.mustPlace() && fixedEnd == null ? grade : null;

		Endpoint end = clicked;
		Layout layout;
		double[] slopes;
		if (laid == null) {
			// Whether an end climbs decides where its anchor puts its node, at the block's centre if it
			// does, so it has to be known before the geometry is, while how much it climbs can only be
			// worked out from that geometry. Nodes are at the height of their block whichever of the two
			// places they are in, so how far the curve rises is known from the blocks alone, and that is
			// what settles it.
			double rise = end.anchorPos()
				.getY()
				- start.anchorPos()
					.getY();
			boolean startSloped = sloped(fixedStartSlope, fixedEndSlope, rise);
			boolean endSloped = sloped(fixedEndSlope, fixedStartSlope, rise);

			layout = layout(start, end, fixedStart, fixedEnd, startSloped, endSloped, angleOffsetDegrees, snap);
			if (layout == null)
				return Plan.failed("too_close");
			slopes = slopesBetween(layout.startPoint(), layout.endPoint(), layout.startHeading(), layout.endHeading(),
				fixedStartSlope, fixedEndSlope, startSloped, endSloped);
		} else {
			Graded graded = gradedEnd(start, clicked, fixedStart, fixedStartSlope, laid, angleOffsetDegrees, snap);
			if (graded == null)
				return Plan.failed("too_close");
			end = graded.end();
			layout = graded.layout();
			slopes = graded.slopes();
		}

		Vec3 startHeading = layout.startHeading();
		Vec3 endHeading = layout.endHeading();
		Vec3 startPoint = layout.startPoint();
		Vec3 endPoint = layout.endPoint();

		BezierConnection curve = curveBetween(start.anchorPos(), end.anchorPos(), startPoint, endPoint, startHeading,
			endHeading, slopes[0], slopes[1], start.normal(), end.normal());

		Plan plan = new Plan(null, start, end, startHeading, endHeading, curve, layout.adjustable(), layout.turn(),
			forward, reverseEnd, null, null, slopes[0], slopes[1], 0, laid);

		// Sampled once, for the ghost every tick: how steep the curve gets, and whether it ever goes back on
		// the way a docked end climbs.
		Climb climb = climb(curve);
		String error = validateSpan(startPoint, endPoint, startHeading, endHeading, layout.turn());
		if (error == null && (start.onSlope() || end.onSlope()))
			error = slopeError(climb, startPoint, endPoint, startHeading, endHeading, start, end);
		if (error != null)
			return plan.withError(error);

		// A curve whose tangents make unequal angles with its chord is a different shape read the
		// other way round, so it would be drawn one way and driven another. That is what a free end
		// avoids by being aimed at the arc, and what a fixed one — two anchors already pointing
		// somewhere, or an end turned by hand — cannot: it is built as two arcs instead.
		if (orientationDeviation(curve) > MAX_ORIENTATION_DEVIATION)
			return splitPlan(level, plan, startPoint, endPoint);

		error = validateCurve(level, curve, climb.steepest(), startPoint, endPoint, startHeading, endHeading, start, end);
		return error == null ? plan : plan.withError(error);
	}

	/**
	 * Where the nodes of a curve are and which way it leaves and arrives, once the headings and the nodes
	 * have settled against one another and the player's turn of the free end has been put on.
	 */
	private record Layout(Vec3 startHeading, Vec3 endHeading, Vec3 startPoint, Vec3 endPoint, boolean adjustable,
		double turn) {}

	/**
	 * The layout of a curve between two anchors, or null where their blocks are in one column and
	 * there is no direction to aim in.
	 *
	 * @param fixedStart the heading the start is obliged to have, or null if it is free to be aimed.
	 * @param fixedEnd   the same for the end.
	 */
	@Nullable
	private static Layout layout(Endpoint start, Endpoint end, @Nullable Vec3 fixedStart, @Nullable Vec3 fixedEnd,
		boolean startSloped, boolean endSloped, float angleOffsetDegrees, int snap) {
		Vec3 seed = flat(Vec3.atBottomCenterOf(end.anchorPos())
			.subtract(Vec3.atBottomCenterOf(start.anchorPos())));
		if (seed.lengthSqr() < 1.0E-6)
			return null;
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
			startPoint = leavingPoint(start, startHeading, startSloped);
			endPoint = arrivingPoint(end, endHeading, endSloped);

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

		// A curve with an end docked onto a slope runs along the slope's line, which the settling above has
		// already aimed the free end along, so it is not turned off it by hand. Nor by the grid below, which
		// leaves alone a heading along an axis.
		boolean adjustable = !start.onSlope() && !end.onSlope() && (fixedStart == null || fixedEnd == null);
		if (adjustable && Math.abs(angleOffsetDegrees) > 1.0E-4) {
			if (fixedEnd == null)
				endHeading = rotateY(endHeading, angleOffsetDegrees);
			else
				startHeading = rotateY(startHeading, angleOffsetDegrees);
			startPoint = leavingPoint(start, startHeading, startSloped);
			endPoint = arrivingPoint(end, endHeading, endSloped);
		}

		// The angles on the grid win over the ones the aimed block and the hand gave, for the end that is free
		// to be aimed. A start that is free as well is one that has nothing to answer to, so it is drawn
		// to the grid too. A free start whose end is fixed is left to the arc that meets the end.
		if (snap > 0 && fixedEnd == null) {
			Vec3 snappedEnd = snapHeading(endHeading, snap);
			Vec3 snappedStart = fixedStart == null ? snapHeading(startHeading, snap) : null;
			if (snappedEnd != null || snappedStart != null) {
				if (snappedEnd != null)
					endHeading = snappedEnd;
				if (snappedStart != null)
					startHeading = snappedStart;
				startPoint = leavingPoint(start, startHeading, startSloped);
				endPoint = arrivingPoint(end, endHeading, endSloped);
			}
		}

		double turn = Math.toDegrees(Math.acos(Mth.clamp(startHeading.dot(endHeading), -1, 1)));
		return new Layout(startHeading, endHeading, startPoint, endPoint, adjustable, turn);
	}

	/**
	 * Where a curve leaves its start: the slope's end for a start docked onto a slope, and otherwise
	 * wherever the anchor puts its exit node for this heading.
	 */
	private static Vec3 leavingPoint(Endpoint start, Vec3 heading, boolean sloped) {
		return start.onSlope() ? start.dockNode()
			: AngledTrackAnchorBlockEntity.exitNode(start.anchorPos(), heading, start.interior(), sloped);
	}

	/** Where a curve arrives at its end, the same way: see {@link #leavingPoint}. */
	private static Vec3 arrivingPoint(Endpoint end, Vec3 heading, boolean sloped) {
		return end.onSlope() ? end.dockNode()
			: AngledTrackAnchorBlockEntity.entryNode(end.anchorPos(), heading, end.interior(), sloped);
	}

	/**
	 * Why a curve with an end docked onto Create's sloped track cannot be built, or null if it can.
	 *
	 * <p>It has to run straight on along the slope's line ({@code slope_turn}). Create works a bezier's
	 * handles out with its axes made unit vectors in three dimensions, and measures the radius from the
	 * second end against that end's axis, so where the climbs at the two ends differ the two directions of a
	 * turning curve get handles of different lengths and are different curves: about a block apart for a
	 * quarter turn of radius ten between a 45 degree end and a level one. Laid straight, the handle is a
	 * third of the distance whichever way it is read. Create's own wand turns nothing off a slope either.
	 *
	 * <p>And its height has to go the way the slope does at a docked end, up from the top and down from the
	 * foot, all along it and never back ({@code slope_way}): one that climbed out of the top of a slope and
	 * came down again would have a crest just past it. Create's wand refuses the same, as leaving a slope
	 * ascending or descending. Sampling the height is all it takes, and {@link #climb} has done it. A curve
	 * that ends no further on than it started has to come back, and so does one between two docked ends that
	 * disagree about the way, since it leaves the one going up and arrives at the other going down, both at
	 * 45 degrees.
	 */
	@Nullable
	private static String slopeError(Climb climb, Vec3 startPoint, Vec3 endPoint, Vec3 startHeading,
		Vec3 endHeading, Endpoint start, Endpoint end) {
		Vec3 chord = flat(endPoint.subtract(startPoint));
		double sideways = Math.abs(chord.x * startHeading.z - chord.z * startHeading.x);
		if (startHeading.dot(endHeading) < 1 - 1.0E-9 || sideways > 1.0E-6)
			return "slope_turn";

		boolean up = (start.onSlope() ? start.fixedSlope() : end.fixedSlope()) > 0;
		return (up ? climb.falls() : climb.rises()) ? "slope_way" : null;
	}

	/** How many times the height of a graded end is looked for again, once its nodes have moved. */
	private static final int GRADE_PASSES = 4;

	/** A far end put at the height its grade gives it, with the curve's layout and the slopes at its two ends. */
	private record Graded(Endpoint end, Layout layout, double[] slopes) {}

	/**
	 * Puts the far end of a curve at the height the grade asks for, and works out how the curve climbs
	 * at each end.
	 *
	 * <p>The height of a bezier is a cubic of its own, whose control points sit {@code h·slope} above or
	 * below the two ends, {@code h} being the length of the horizontal handles. Slopes that change at a
	 * steady rate from {@code s0} to {@code s1} are the case where the middle control points are the
	 * average of those, and the rise is then {@code 1.5·h·(s0 + s1)}. So a curve that is to arrive at the
	 * grade {@code g} rises {@code 1.5·h·(s0 + g)}. A node is at the height of a block, which makes that a
	 * whole number, and it is the far end's slope that gives way: {@code s1 = rise / (1.5·h) − s0}.
	 * What is missed is a fraction of a block of rise over the length of the curve, and not carried
	 * on, since the next curve starts from the slope this one arrived with and aims at the grade again.
	 *
	 * <p>A curve asked to arrive level does, whatever that makes of the rise: it levels off, which is
	 * what the player meant by asking, and the next curve starts from level.
	 *
	 * <p>The handles are as long as the curve seen from above says, which depends on where the nodes are,
	 * which depends on whether the ends climb, which depends on the rise. So the rise is looked for
	 * until it comes out as it went in. It does within a pass or two; where it does not, the curve is
	 * built at the height last tried and its far slope worked out from the handles it has, so it is still
	 * a steady change of grade, and the same one on the client and on the server.
	 *
	 * @param fixedStart      the heading the start is obliged to have, or null if it is free to be aimed.
	 * @param fixedStartSlope the climb the start is obliged to have, or null if it is free. A free start is
	 *                        taken to leave level, which is what it is: it is not on a route yet.
	 * @return null where the ends are in one column.
	 */
	@Nullable
	private static Graded gradedEnd(Endpoint start, Endpoint clicked, @Nullable Vec3 fixedStart,
		@Nullable Double fixedStartSlope, GradeSpec grade, float angleOffsetDegrees, int snap) {
		double s0 = fixedStartSlope == null ? 0 : fixedStartSlope;
		double g = grade.grade();
		int startY = start.anchorPos()
			.getY();

		int rise = 0;
		Endpoint end = clicked;
		Layout layout = null;
		boolean startSloped = false;
		boolean endSloped = false;
		for (int pass = 0; pass < GRADE_PASSES; pass++) {
			end = clicked.atHeight(startY + rise);
			// A curve asked to arrive level does, whatever the rounding of its rise leaves over; any other
			// arrives climbing unless it has neither a climb to start from nor a rise to make.
			endSloped = g != 0 && (rise != 0 || s0 != 0);
			startSloped = fixedStartSlope != null ? fixedStartSlope != 0 : rise != 0 || endSloped;
			layout = layout(start, end, fixedStart, null, startSloped, endSloped, angleOffsetDegrees, snap);
			if (layout == null)
				return null;

			int wanted = (int) Math.round(1.5 * handleOf(layout) * (s0 + g));
			if (wanted == rise)
				break;
			if (pass + 1 < GRADE_PASSES)
				rise = wanted;
		}

		double handle = handleOf(layout);
		double endSlope = handle < 1.0E-6 ? 0 : rise / (1.5 * handle) - s0;
		return new Graded(end, layout, new double[] { keepSloped(s0, startSloped), keepSloped(endSlope, endSloped) });
	}

	/** How long the handles of the curve a layout describes are. */
	private static double handleOf(Layout layout) {
		return handleLength(layout.startPoint(), layout.endPoint(), layout.startHeading(), layout.endHeading());
	}

	/**
	 * The curve between two points as an anchor builds the one it owns. Both tangents point into the
	 * curve: the first forwards along the route, the second backwards along it.
	 *
	 * <p>The headings are horizontal unit vectors and the slopes are the climb in the direction of
	 * travel at each end. See {@link AngledTrackAnchorBlockEntity#tangentOf}.
	 */
	static BezierConnection curveBetween(BlockPos from, BlockPos to, Vec3 fromPoint, Vec3 toPoint,
		Vec3 fromHeading, Vec3 toHeading, double fromSlope, double toSlope) {
		return curveBetween(from, to, fromPoint, toPoint, fromHeading, toHeading, fromSlope, toSlope, UP, UP);
	}

	/**
	 * The same with the normals of the rails at its two ends, which are straight up except where an end is
	 * docked onto a slope: see {@link Endpoint#normal}.
	 */
	static BezierConnection curveBetween(BlockPos from, BlockPos to, Vec3 fromPoint, Vec3 toPoint,
		Vec3 fromHeading, Vec3 toHeading, double fromSlope, double toSlope, Vec3 fromNormal, Vec3 toNormal) {
		return new BezierConnection(Couple.create(from, to), Couple.create(fromPoint, toPoint),
			Couple.create(AngledTrackAnchorBlockEntity.tangentOf(fromHeading, fromSlope),
				AngledTrackAnchorBlockEntity.tangentOf(toHeading, toSlope)
					.scale(-1)),
			Couple.create(fromNormal, toNormal), true, false, TrackMaterial.ANDESITE);
	}

	/** The same level curve this always was: no climb at either end. */
	static BezierConnection curveBetween(BlockPos from, BlockPos to, Vec3 fromPoint, Vec3 toPoint,
		Vec3 fromHeading, Vec3 toHeading) {
		return curveBetween(from, to, fromPoint, toPoint, fromHeading, toHeading, 0, 0);
	}

	/**
	 * The plan for a curve that has to be two, or the reason there is no room to make it so.
	 *
	 * <p>The junction is somewhere an anchor can go: loaded, and a block that may be built in.
	 * Chunks that are not loaded are not searched, since asking for a block in one makes a server
	 * load it. What the plan keeps from the single curve is its curve, when there is no junction to
	 * be had, so that the ghost still shows where the player was aiming.
	 *
	 * <p>The climb is shared out as the junction's height already is, by the length of each arc: the
	 * anchor between them climbs at the slope of the whole link, and each arc then works its own free
	 * end out from that, the way a single curve does from a neighbour that is already there.
	 *
	 * <p>A link laid at a grade has both its slopes already, from the single curve it would have been:
	 * the start's, and the one the far end arrives with. The anchor between the arcs takes a slope
	 * between the two, by how far along the link it is, which is where the slope of a curve that changes
	 * at a steady rate would be, and each arc then has both of its slopes given.
	 */
	private static Plan splitPlan(Level level, Plan single, Vec3 startPoint, Vec3 endPoint) {
		Endpoint start = single.start();
		Endpoint end = single.end();
		Vec3 startHeading = single.startHeading();
		Vec3 endHeading = single.endHeading();

		Biarc.Junction junction =
			Biarc.find(startPoint, startHeading, endPoint, endHeading, pos -> junctionUsable(level, pos));
		if (junction == null)
			return single.withError("uneven");

		boolean graded = single.grade() != null;
		Double fixedStartSlope = start.fixedHeading() == null ? null : start.fixedSlope();
		Double fixedEndSlope = end.fixedHeading() == null ? null : end.fixedSlope();
		if (graded) {
			fixedStartSlope = single.startSlope();
			fixedEndSlope = single.endSlope();
		}
		double wholeRise = endPoint.y - startPoint.y;
		boolean startSloped = graded ? single.startSlope() != 0 : sloped(fixedStartSlope, fixedEndSlope, wholeRise);
		boolean endSloped = graded ? single.endSlope() != 0 : sloped(fixedEndSlope, fixedStartSlope, wholeRise);

		double firstRun = flat(junction.point()
			.subtract(startPoint)).length();
		double secondRun = flat(endPoint.subtract(junction.point())).length();
		double junctionSlope = (float) (graded
			? single.startSlope() + (single.endSlope() - single.startSlope()) * firstRun / (firstRun + secondRun)
			: wholeRise / (firstRun + secondRun));

		double[] firstSlopes = solveSlopes(handleLength(startPoint, junction.point(), startHeading, junction.heading()),
			junction.point().y - startPoint.y, fixedStartSlope, junctionSlope);
		double[] secondSlopes = solveSlopes(handleLength(junction.point(), endPoint, junction.heading(), endHeading),
			endPoint.y - junction.point().y, junctionSlope, fixedEndSlope);
		double startSlope = keepSloped(firstSlopes[0], startSloped);
		double endSlope = keepSloped(secondSlopes[1], endSloped);

		BezierConnection first = curveBetween(start.anchorPos(), junction.pos(), startPoint, junction.point(),
			startHeading, junction.heading(), startSlope, junctionSlope, start.normal(), UP);
		BezierConnection second = curveBetween(junction.pos(), end.anchorPos(), junction.point(), endPoint,
			junction.heading(), endHeading, junctionSlope, endSlope, UP, end.normal());
		Plan plan = new Plan(null, start, end, startHeading, endHeading, first, single.adjustable(),
			single.turnDegrees(), single.forward(), single.reverseEnd(), junction, second, startSlope, endSlope,
			junctionSlope, single.grade());

		// Each arc is held to what a curve of its own would be, against whatever is already at the
		// end it shares with the route; the junction has no neighbour yet, only the other arc.
		String error = validateCurve(level, first, startPoint, junction.point(), startHeading, junction.heading(),
			start, null);
		if (error == null)
			error = validateCurve(level, second, junction.point(), endPoint, junction.heading(), endHeading, null,
				end);
		if (error == null)
			error = validateJoint(first, second);
		return error == null ? plan : plan.withError(error);
	}

	/**
	 * Whether an anchor may go in a block as the point between two arcs: one that is loaded, since
	 * asking for a block in a chunk that is not makes a server load it, and that may be built in.
	 * Shared with {@link CurveRepair}, which has to agree with the planner about where one can go.
	 */
	static boolean junctionUsable(Level level, BlockPos pos) {
		return level.isLoaded(pos) && level.getBlockState(pos)
			.canBeReplaced();
	}

	/** Whether a train may cross the node where two arcs meet, asked the way Create asks it. */
	@Nullable
	static String validateJoint(BezierConnection first, BezierConnection second) {
		Vec3 arriving = first.getPosition(1)
			.subtract(first.getPosition(0.75));
		Vec3 leaving = second.getPosition(0.25)
			.subtract(second.getPosition(0));
		if (arriving.lengthSqr() < 1.0E-8 || leaving.lengthSqr() < 1.0E-8)
			return "too_sharp";
		return arriving.normalize()
			.dot(leaving.normalize()) < MIN_JOINT_DOT ? "too_sharp" : null;
	}

	/**
	 * The checks about the span as a whole rather than about the curve across it: how far apart the
	 * ends are, whether the tangents point towards one another, and how much it turns.
	 */
	@Nullable
	private static String validateSpan(Vec3 startPoint, Vec3 endPoint, Vec3 startHeading, Vec3 endHeading,
		double turn) {
		Vec3 span = endPoint.subtract(startPoint);

		if (flat(span).length() < MIN_SPAN)
			return "too_close";

		int maxLength = AllConfigs.server().trains.maxTrackPlacementLength.get();
		if (span.length() > maxLength)
			return "too_far";

		Vec3 direction = flat(span).normalize();
		if (startHeading.dot(direction) <= 0 || endHeading.dot(direction) <= 0)
			return "doubles_back";

		if (turn > MAX_TURN_DEGREES)
			return "too_sharp";

		return null;
	}

	/**
	 * The checks about one curve: whether it climbs more than track may, whether it is tighter than a
	 * train will take, and whether a train can cross the nodes at its ends.
	 *
	 * @param startNeighbour the end the curve leaves from, if a route already runs through it for the
	 *                       curve to be compared against; null where nothing does, and the curve
	 *                       meets its own tangent.
	 * @param endNeighbour   the same for the end it arrives at.
	 */
	@Nullable
	private static String validateCurve(Level level, BezierConnection curve, Vec3 startPoint, Vec3 endPoint,
		Vec3 startHeading, Vec3 endHeading, @Nullable Endpoint startNeighbour, @Nullable Endpoint endNeighbour) {
		return validateCurve(level, curve, maxLocalGrade(curve), startPoint, endPoint, startHeading, endHeading,
			startNeighbour, endNeighbour);
	}

	/** The same, with the curve's steepest grade, {@link #maxLocalGrade}, already measured. */
	@Nullable
	private static String validateCurve(Level level, BezierConnection curve, double steepest, Vec3 startPoint,
		Vec3 endPoint, Vec3 startHeading, Vec3 endHeading, @Nullable Endpoint startNeighbour,
		@Nullable Endpoint endNeighbour) {
		Vec3 arriving =
			startNeighbour == null ? null : adjoiningDirection(level, startNeighbour, startHeading, Role.START);
		Vec3 leaving = endNeighbour == null ? null : adjoiningDirection(level, endNeighbour, endHeading, Role.END);
		return validateCurve(curve, steepest, startPoint, endPoint, startHeading, endHeading, arriving, leaving);
	}

	/**
	 * The same checks with the neighbours' directions already worked out, for a caller that knows
	 * which of an anchor's sides the neighbour is on rather than having it read off the anchor.
	 *
	 * @param arriving the direction of the track that already arrives at the start, as
	 *                 {@link #adjoiningDirection} gives it; null to compare against the heading.
	 * @param leaving  the same for the track that already leaves from the end.
	 */
	@Nullable
	static String validateCurve(BezierConnection curve, Vec3 startPoint, Vec3 endPoint, Vec3 startHeading,
		Vec3 endHeading, @Nullable Vec3 arriving, @Nullable Vec3 leaving) {
		return validateCurve(curve, maxLocalGrade(curve), startPoint, endPoint, startHeading, endHeading, arriving,
			leaving);
	}

	@Nullable
	private static String validateCurve(BezierConnection curve, double steepest, Vec3 startPoint, Vec3 endPoint,
		Vec3 startHeading, Vec3 endHeading, @Nullable Vec3 arriving, @Nullable Vec3 leaving) {
		int maxLength = AllConfigs.server().trains.maxTrackPlacementLength.get();

		if (steeperThanAllowed(steepest))
			return "too_steep";

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

		if (startChord.normalize()
			.dot(arriving == null ? curve.axes.getFirst()
				.normalize() : arriving) < MIN_JOINT_DOT)
			return "too_sharp";

		if (endChord.normalize()
			.dot(leaving == null ? curve.axes.getSecond()
				.scale(-1)
				.normalize() : leaving) < MIN_JOINT_DOT)
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
		// A branch out of an end leaves the boundary of the end's block, where what a train comes off is the
		// end's own span, aimed along the heading.
		if (!endpoint.interior())
			return null;
		if (!(level.getBlockEntity(endpoint.anchorPos()) instanceof AngledTrackAnchorBlockEntity anchor))
			return null;

		// A branch is beside the line on its own side, and a train that takes it comes from the other: the
		// line there is what it has to line up with.
		if (endpoint.branches())
			return adjoiningDirection(anchor, endpoint.branch() == Branch.BEHIND, role);

		// Whichever side is already spoken for is the one with a curve on it. Exactly one is, or
		// the endpoint would not have got this far.
		return adjoiningDirection(anchor, anchor.getNext() != null, role);
	}

	/**
	 * The same for an anchor that may have a curve on both sides, and is asked about one of them:
	 * the link being replaced is the other, and not what the new curve is to be compared against.
	 *
	 * @param neighbourIsNext which side the track to compare against is on.
	 */
	@Nullable
	static Vec3 adjoiningDirection(AngledTrackAnchorBlockEntity anchor, boolean neighbourIsNext, Role role) {
		Level level = anchor.getLevel();
		BlockPos neighbourPos = neighbourIsNext ? anchor.getNext() : anchor.getPrev();
		if (level == null || neighbourPos == null
			|| !(level.getBlockEntity(neighbourPos) instanceof AngledTrackAnchorBlockEntity neighbour))
			return null;

		BlockPos pos = anchor.getBlockPos();
		Vec3 anchorHeading = anchor.tangent();

		// The existing curve, written from this node outwards, so both it and the new curve are
		// measured leaving the same place and the comparison is between like and like.
		Vec3 here = neighbourIsNext ? AngledTrackAnchorBlockEntity.exitNode(pos, anchorHeading, true)
			: AngledTrackAnchorBlockEntity.entryNode(pos, anchorHeading, true);
		Vec3 there = neighbourIsNext ? neighbour.entryNode() : neighbour.exitNode();
		if (here.distanceToSqr(there) < 1.0E-6)
			return null;

		Vec3 outward = neighbourIsNext ? anchorHeading : anchorHeading.scale(-1);
		Vec3 farTangent = neighbourIsNext ? neighbour.tangent()
			.scale(-1) : neighbour.tangent();

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
	 *
	 * <p>The same goes for a line that climbs. {@link #length} is how far it goes along the ground,
	 * and {@link #rise} how many blocks it gains, which is a whole number because a node is at the
	 * height of a block. The grade asked for is rarely exactly that over this run, so the grade the
	 * line has, {@link #slope}, is reported too. It is the same all along: the tangents at both ends
	 * lean by it, and a straight line leaning by the rise over the run is straight in three dimensions.
	 */
	public record StraightPlan(@Nullable String error, BlockPos endPos, Vec3 heading, double bearing,
		double length, int rise) {

		static StraightPlan failed(String error) {
			return new StraightPlan(error, BlockPos.ZERO, Vec3.ZERO, 0, 0, 0);
		}

		/** How steeply the line climbs, as a fraction: the rise over the run, minus for a descent. */
		public double slope() {
			return length < 1.0E-6 ? 0 : rise / length;
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
		return planStraight(level, from, requestedBearing, requestedLength, 0);
	}

	/**
	 * The same for a line that climbs, at {@code permille} over the run; a minus sign is a descent.
	 *
	 * <p>Where the line ends on the ground is chosen exactly as for a level one, and its height follows:
	 * the grade over the run it came out with, to the nearest whole block. The grade the line really
	 * has is that rise over that run, which is off from the one asked for by up to half a block over the
	 * run — more for a short line or a gentle grade — and is what the plan reports. A line that comes
	 * out steeper than {@code curves.maxGrade} allows is refused, as a curve is.
	 */
	public static StraightPlan planStraight(Level level, BlockPos from, float requestedBearing, int requestedLength,
		int permille) {
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

		int rise = (int) Math.round(permille / 1000.0 * span.length());
		BlockPos end = best.atY(from.getY() + rise);

		// Blocked, but the line is still worked out — the preview draws it in red, which says far
		// more than the message does on its own: it is the difference between being told "no room"
		// and being shown where the obstruction is.
		String error = !level.isOutsideBuildHeight(end) && (level.getBlockState(end)
			.canBeReplaced() || level.getBlockEntity(end) instanceof AngledTrackAnchorBlockEntity) ? null : "no_room";
		if (error == null && steeperThanAllowed(Math.abs(rise) / span.length()))
			error = "too_steep";

		return new StraightPlan(error, end, heading, AngledTrackAnchorBlockEntity.yawOf(heading), span.length(), rise);
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
		// A line that climbs has its nodes at the centres of its blocks, and tangents that lean by the
		// climb: the same two things an anchor does once it holds a slope.
		boolean climbs = plan.rise() != 0;
		Vec3 tangent = AngledTrackAnchorBlockEntity.tangentOf(heading, plan.slope());
		return new BezierConnection(Couple.create(from, plan.endPos()),
			Couple.create(AngledTrackAnchorBlockEntity.exitNode(from, heading, false, climbs),
				AngledTrackAnchorBlockEntity.entryNode(plan.endPos(), heading, false, climbs)),
			Couple.create(tangent, tangent.scale(-1)), Couple.create(UP, UP), true, false, TrackMaterial.ANDESITE);
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

		// The climb goes with the heading, as the y of the vector, the way a curve's anchors are aimed.
		Vec3 tangent = AngledTrackAnchorBlockEntity.tangentOf(plan.heading(), plan.slope());
		start.linkTo(end, tangent, tangent);
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
	 * @param slope    how steeply the route climbs there, which the new anchor takes with it.
	 * @param anchorPos the block the new anchor would occupy.
	 * @param error    why it cannot be done there, or null if it can.
	 */
	public record SplitTarget(@Nullable AngledTrackAnchorBlockEntity owner, double t, Vec3 point, Vec3 tangent,
		double slope, BlockPos anchorPos, @Nullable String error, @Nullable BlockPos far) {

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

		// Every curve is held by one of its two ends, the branches of a turnout among them, so asking each anchor
		// for those it holds asks about each curve once.
		for (AngledTrackAnchorBlockEntity anchor : AnchorIndex.in(level))
			for (BezierConnection curve : anchor.ownedCurves()) {
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
		// The anchor at the curve's other end: the owner's next, or a branch of its.
		BlockPos far = curve.bePositions.getSecond();

		// The tangent as a difference across a short step rather than the bezier's derivative, so
		// this needs nothing but the curve's public shape.
		double step = 1.0 / Math.max(8, (int) (length * 4));
		Vec3 along = curve.getPosition(Math.min(1, t + step))
			.subtract(curve.getPosition(Math.max(0, t - step)));
		Vec3 tangent = flat(along);
		boolean upright = tangent.lengthSqr() < 1.0E-8;
		double slope = upright ? owner.getSlope() : (float) (along.y / tangent.length());
		tangent = upright ? owner.heading() : tangent.normalize();

		// The block whose floor is nearest the curve, not the one the curve is in: an anchor's node is
		// at the bottom of its block, and on a climb the curve is part way up it. Level curves are
		// at a whole height, where the two come to the same block.
		BlockPos anchorPos = BlockPos.containing(point.x, Mth.floor(point.y + 0.5), point.z);

		// Both halves have to be a curve in their own right afterwards. The same MIN_SPAN the wand
		// holds a new curve to, measured along this one.
		if (t * length < MIN_SPAN + 1 || (1 - t) * length < MIN_SPAN + 1)
			return new SplitTarget(owner, t, point, tangent, slope, anchorPos, "split_too_close", far);
		if (anchorPos.equals(owner.getBlockPos()) || anchorPos.equals(far))
			return new SplitTarget(owner, t, point, tangent, slope, anchorPos, "split_too_close", far);
		if (level.getBlockEntity(anchorPos) instanceof AngledTrackAnchorBlockEntity)
			return new SplitTarget(owner, t, point, tangent, slope, anchorPos, "split_occupied", far);
		if (!level.getBlockState(anchorPos)
			.canBeReplaced())
			return new SplitTarget(owner, t, point, tangent, slope, anchorPos, "no_room", far);

		// Splitting replaces the curve with two others, and a signal standing on it remembers the
		// curve it was put on by where its ends are.
		if (carriesPoints(level, curve))
			return new SplitTarget(owner, t, point, tangent, slope, anchorPos, "split_has_points", far);

		return new SplitTarget(owner, t, point, tangent, slope, anchorPos, null, far);
	}

	/**
	 * Whether a signal, station or the like stands on the graph's edge for this curve, in either
	 * direction.
	 *
	 * <p>Such a thing is placed against the curve itself, by the block at one end and the one at the
	 * other, and if the curve is replaced by anything else it is left pointing at nothing. A curve
	 * the graph does not hold yet has nothing on it.
	 */
	static boolean carriesPoints(Level level, BezierConnection curve) {
		TrackNodeLocation startLocation = new TrackNodeLocation(curve.starts.getFirst()).in(level);
		TrackNodeLocation endLocation = new TrackNodeLocation(curve.starts.getSecond()).in(level);

		TrackGraph graph = Create.RAILWAYS.sided(level)
			.getGraph(level, startLocation);
		if (graph == null)
			return false;

		TrackNode start = graph.locateNode(startLocation);
		TrackNode end = graph.locateNode(endLocation);
		if (start == null || end == null)
			return false;

		TrackEdge forwards = graph.getConnectionsFrom(start)
			.get(end);
		TrackEdge backwards = graph.getConnectionsFrom(end)
			.get(start);
		return forwards != null && forwards.getEdgeData()
			.hasPoints()
			|| backwards != null && backwards.getEdgeData()
				.hasPoints();
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
		BlockPos farPos = target.far();
		if (farPos == null || !(level.getBlockEntity(farPos) instanceof AngledTrackAnchorBlockEntity ahead))
			return false;
		// Which kind of link the curve is: the owner's next, and maybe a branch coming into a turnout there; or
		// a branch of the owner's, running on from it. Read before anything changes, as the first link below
		// takes the far end off the owner.
		boolean ownersBranch = !farPos.equals(owner.getNext());
		boolean intoBranch = !ownersBranch && ahead.isBranch(owner.getBlockPos());
		if (ownersBranch && !(owner.isBranch(farPos) && owner.getBlockPos()
			.equals(ahead.getPrev())))
			return false;
		boolean branchAhead = Boolean.TRUE.equals(ownersBranch ? owner.branchSide(farPos)
			: ahead.branchSide(owner.getBlockPos()));
		// Where the owner is among the turnout's branches, for the point to take that place: the first link
		// takes the owner off the turnout before the second puts the point on.
		int intoAt = intoBranch ? ahead.getBranches(branchAhead)
			.indexOf(owner.getBlockPos()) : -1;

		BlockPos pos = target.anchorPos();
		clearFor(level, pos);
		level.setBlock(pos, CTNRegistry.ANGLED_TRACK_ANCHOR.get()
			.defaultBlockState()
			.setValue(AngledTrackAnchorBlock.AXIS, AnchorAxis.nearestTo(target.tangent())), Block.UPDATE_ALL);

		if (!(level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity inserted))
			return false;

		// The first link takes the far end off the owner, so the anchor ahead has to be caught
		// before it, not looked up again after. So does whether it is docked onto a slope, which it
		// is not for the moment between the two links, when it has no curve.
		boolean aheadDocked = ahead.isSlopeDocked();
		Vec3 aim = AngledTrackAnchorBlockEntity.tangentOf(target.tangent(), target.slope());
		// A branch stays a branch of its turnout, at whichever end of the two curves the turnout is. The point
		// takes the old branch's place, or the room its curve left, so there is always room for it.
		if (ownersBranch)
			owner.branchTo(inserted, branchAhead, true, aim, false, farPos);
		else
			owner.linkTo(inserted, null, aim);
		if (intoBranch) {
			ahead.branchTo(inserted, branchAhead, false, null);
			ahead.moveBranch(pos, intoAt);
		} else
			inserted.linkTo(ahead, null, null, false, aheadDocked);
		return true;
	}

	// ----------------------------------------------------------------- math

	/**
	 * How far {@code curve} strays from itself read the other way round: the largest distance from a
	 * point on one to the nearest point of the other, both ways, in blocks. Zero for a curve whose
	 * tangents make equal angles with its chord. See {@link #MAX_ORIENTATION_DEVIATION}.
	 */
	public static double orientationDeviation(BezierConnection curve) {
		DeviationKey key = new DeviationKey(curve.starts.getFirst(), curve.starts.getSecond(),
			curve.axes.getFirst(), curve.axes.getSecond());
		Double known = DEVIATIONS.get(key);
		if (known != null)
			return known;

		double deviation = deviation(curve, curve.secondary());
		DEVIATIONS.put(key, deviation);
		return deviation;
	}

	/**
	 * What a curve is made from, which is all its shape depends on. The measurement samples it a
	 * hundred and twenty times against a hundred and twenty segments, and the wand asks for the same
	 * curve every tick while it is aimed at the same two anchors.
	 */
	private record DeviationKey(Vec3 firstStart, Vec3 secondStart, Vec3 firstAxis, Vec3 secondAxis) {}

	/** The last few curves measured, least recently used out first. Read from the client and the server. */
	private static final Map<DeviationKey, Double> DEVIATIONS =
		Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<DeviationKey, Double> eldest) {
				return size() > 512;
			}
		});

	/**
	 * How far apart two curves are: the largest distance from a point on one to the nearest point of
	 * the other, taken both ways, in blocks.
	 */
	public static double deviation(BezierConnection a, BezierConnection b) {
		Vec3[] first = new Vec3[ORIENTATION_SAMPLES + 1];
		Vec3[] second = new Vec3[ORIENTATION_SAMPLES + 1];
		for (int i = 0; i <= ORIENTATION_SAMPLES; i++) {
			double t = i / (double) ORIENTATION_SAMPLES;
			first[i] = a.getPosition(t);
			second[i] = b.getPosition(t);
		}
		return deviation(first, second);
	}

	/** The same for two lines already sampled, each a run of points along it in order. */
	static double deviation(Vec3[] first, Vec3[] second) {
		return Math.max(farthestFrom(first, second), farthestFrom(second, first));
	}

	/** The largest distance from any of {@code points} to the polyline through {@code line}. */
	private static double farthestFrom(Vec3[] points, Vec3[] line) {
		double worst = 0;
		for (Vec3 point : points) {
			double nearest = Double.MAX_VALUE;
			for (int i = 0; i + 1 < line.length; i++)
				nearest = Math.min(nearest, distanceToSegment(point, line[i], line[i + 1]));
			worst = Math.max(worst, nearest);
		}
		return worst;
	}

	private static double distanceToSegment(Vec3 point, Vec3 a, Vec3 b) {
		Vec3 along = b.subtract(a);
		double lengthSqr = along.lengthSqr();
		double t = lengthSqr < 1.0E-12 ? 0 : Mth.clamp(point.subtract(a)
			.dot(along) / lengthSqr, 0, 1);
		return point.distanceTo(a.add(along.scale(t)));
	}

	/**
	 * {@code tangent} mirrored about {@code chord}: the other tangent of the circular arc that
	 * joins the two ends of a curve whose chord runs that way.
	 */
	static Vec3 reflect(Vec3 tangent, Vec3 chord) {
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

	// ---------------------------------------------------------------- angle grid

	/** {@code curves.snapAngle}, in degrees; the default where the config cannot be read yet. 0 is off. */
	public static int snapStepDegrees() {
		try {
			return CTNConfig.SERVER.snapAngle.get();
		} catch (IllegalStateException notLoaded) {
			return DEFAULT_SNAP_ANGLE;
		}
	}

	/** The way a horizontal vector points, in compass degrees: 0 is east and 90 north, as on a map of the world. */
	public static double bearingOf(Vec3 heading) {
		return Math.toDegrees(Math.atan2(-heading.z, heading.x));
	}

	/**
	 * The horizontal unit vector for a compass bearing. The multiples of 45 are written out rather than
	 * worked out, because {@code cos(90°)} is six parts in 10^17 and not nothing, and a heading that is
	 * meant to lie along an axis should lie along it exactly.
	 */
	public static Vec3 headingAt(double bearing) {
		double wrapped = ((bearing % 360) + 360) % 360;
		double diagonal = Math.sqrt(0.5);
		if (wrapped % 90 == 0)
			return switch ((int) (wrapped / 90)) {
				case 0 -> new Vec3(1, 0, 0);
				case 1 -> new Vec3(0, 0, -1);
				case 2 -> new Vec3(-1, 0, 0);
				default -> new Vec3(0, 0, 1);
			};
		if (wrapped % 45 == 0)
			return switch ((int) (wrapped / 45)) {
				case 1 -> new Vec3(diagonal, 0, -diagonal);
				case 3 -> new Vec3(-diagonal, 0, -diagonal);
				case 5 -> new Vec3(-diagonal, 0, diagonal);
				default -> new Vec3(diagonal, 0, diagonal);
			};
		double radians = Math.toRadians(wrapped);
		return new Vec3(Math.cos(radians), 0, -Math.sin(radians));
	}

	/**
	 * The angle on the grid that wins for a bearing, or NaN if it is not near enough to any. A bearing that
	 * is already on one is left to be.
	 */
	static double gridAngle(double bearing, int step) {
		double nearest = Math.round(bearing / step) * (double) step;
		double off = Math.abs(bearing - nearest);
		return off <= step * SNAP_WINDOW && off >= SNAP_EXACT ? nearest : Double.NaN;
	}

	/** The heading drawn to the grid, or null if it is on it already or too far from it to be drawn. */
	@Nullable
	private static Vec3 snapHeading(Vec3 heading, int step) {
		double wanted = gridAngle(bearingOf(heading), step);
		return Double.isNaN(wanted) ? null : headingAt(wanted);
	}

	/**
	 * The layout of the curve to a far end as the planner would have it with nothing drawn to the grid: what
	 * {@link #solve} works out first, without the slopes.
	 */
	@Nullable
	private static Layout naturalLayout(Endpoint start, Endpoint clicked, @Nullable GradeSpec grade) {
		Vec3 fixedStart = start.fixedHeading() == null ? null : flatUnit(start.fixedHeading());
		Vec3 fixedEnd = clicked.fixedHeading() == null ? null : flatUnit(clicked.fixedHeading());
		Double fixedStartSlope = fixedStart == null ? null : start.fixedSlope();
		Double fixedEndSlope = fixedEnd == null ? null : clicked.fixedSlope();
		GradeSpec laid = grade != null && clicked.mustPlace() && fixedEnd == null ? grade : null;

		if (laid != null) {
			Graded graded = gradedEnd(start, clicked, fixedStart, fixedStartSlope, laid, 0, 0);
			return graded == null ? null : graded.layout();
		}

		double rise = clicked.anchorPos()
			.getY()
			- start.anchorPos()
				.getY();
		return layout(start, clicked, fixedStart, fixedEnd, sloped(fixedStartSlope, fixedEndSlope, rise),
			sloped(fixedEndSlope, fixedStartSlope, rise), 0, 0);
	}

	/**
	 * The block to aim the curve at so that it arrives at an angle on the grid exactly, if there is one a
	 * couple of blocks from the one that was aimed at; otherwise the block that was.
	 *
	 * <p>Where the aimed block gives an angle near the grid but not on it, some block beside it often gives
	 * one on it. That is so for every multiple of 45 degrees, since a diagonal and an axis both run through
	 * the corners of blocks; for the other angles on the grid there is none, and the heading is turned to
	 * them instead (see {@link #layout}), with the block left where it was. Taking the exact block when
	 * there is one is what keeps a right angle one clean arc, rather than an arc with an anchor and a
	 * second one added to correct it.
	 *
	 * <p>Only a new anchor on open ground can be moved. The aimed block is the nearest of those that are
	 * right, so the curve ends as near to where the player pointed as it can and still be square.
	 */
	private static Endpoint snappedTarget(Level level, Endpoint start, Endpoint end, @Nullable GradeSpec grade,
		int step) {
		if (!end.mustPlace() || end.fixedHeading() != null)
			return end;

		Layout natural = naturalLayout(start, end, grade);
		if (natural == null || !natural.adjustable())
			return end;
		double wanted = gridAngle(bearingOf(natural.endHeading()), step);
		if (Double.isNaN(wanted))
			return end;

		Endpoint best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (int dx = -SNAP_SEARCH; dx <= SNAP_SEARCH; dx++)
			for (int dz = -SNAP_SEARCH; dz <= SNAP_SEARCH; dz++) {
				int distance = dx * dx + dz * dz;
				if (distance == 0 || distance >= bestDistance)
					continue;

				BlockPos pos = end.anchorPos()
					.offset(dx, 0, dz);
				if (pos.equals(start.anchorPos()) || !roomForAnchor(level, pos, grade != null))
					continue;

				Endpoint candidate = end.movedTo(pos);
				Layout layout = naturalLayout(start, candidate, grade);
				if (layout != null && Math.abs(Mth.wrapDegrees(bearingOf(layout.endHeading()) - wanted)) < SNAP_EXACT) {
					best = candidate;
					bestDistance = distance;
				}
			}
		return best == null ? end : best;
	}

	/**
	 * Whether an anchor could go at a block: loaded, and nothing in it that is not as easily built over. In
	 * grade mode the height is not the block's own, and is asked again once the grade has chosen it.
	 */
	private static boolean roomForAnchor(Level level, BlockPos pos, boolean heightFree) {
		if (level.isOutsideBuildHeight(pos) || !level.isLoaded(pos))
			return false;
		if (level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity)
			return false;
		BlockState state = level.getBlockState(pos);
		return !(state.getBlock() instanceof ITrackBlock) && (heightFree || state.canBeReplaced());
	}

	/**
	 * Whether a rise of {@code ascend} over {@code horizontal} of run is steeper than a curve may be,
	 * which is about how much a train can climb rather than anything to do with the shape of the
	 * curve. The limit is {@code curves.maxGrade}, rounded to the whole permille the wand shows, so at the
	 * default a rise of a block for each block of run is allowed and anything steeper is not.
	 */
	static boolean tooSteep(double horizontal, double ascend) {
		return ascend > 1.0E-4 && (horizontal < 1.0E-6 || steeperThanAllowed(ascend / horizontal));
	}

	/** {@code curves.maxGrade}, in permille; the default where the config cannot be read yet. */
	public static int maxGradePermille() {
		try {
			return CTNConfig.SERVER.maxGrade.get();
		} catch (IllegalStateException notLoaded) {
			// A client that has joined a server and not yet been sent its settings, or a test that is
			// not running with them. The preview still has to say something.
			return DEFAULT_MAX_GRADE;
		}
	}

	/** Whether a grade, as a fraction, rounds to more permille than the limit. */
	static boolean steeperThanAllowed(double grade) {
		return Math.round(grade * 1000) > maxGradePermille();
	}

	/**
	 * The steepest the curve is anywhere along it, as a fraction: the rise over the horizontal run,
	 * the way a railway counts it. Local rather than average, because a curve that eases into a
	 * climb or out of one is steeper in the middle than its ends are apart.
	 */
	static double maxLocalGrade(BezierConnection curve) {
		return climb(curve).steepest();
	}

	/**
	 * What a curve's height does along it: the steepest it gets, as {@link #maxLocalGrade} gives it, and
	 * whether it ever goes up or ever goes down between two of the points it is sampled at.
	 */
	record Climb(double steepest, boolean rises, boolean falls) {}

	/** The curve's {@link Climb}, from one pass over the same points every grade check here uses. */
	static Climb climb(BezierConnection curve) {
		Vec3 previous = curve.getPosition(0);
		double worst = 0;
		boolean rises = false;
		boolean falls = false;
		for (int i = 1; i <= GRADE_SAMPLES; i++) {
			Vec3 point = curve.getPosition(i / (double) GRADE_SAMPLES);
			double run = Math.sqrt((point.x - previous.x) * (point.x - previous.x)
				+ (point.z - previous.z) * (point.z - previous.z));
			double rise = point.y - previous.y;
			if (run > 1.0E-6)
				worst = Math.max(worst, Math.abs(rise) / run);
			else if (Math.abs(rise) > 1.0E-6)
				worst = Double.MAX_VALUE;
			rises |= rise > 1.0E-6;
			falls |= rise < -1.0E-6;
			previous = point;
		}
		return new Climb(worst, rises, falls);
	}

	/**
	 * How long a curve is seen from above, in blocks: the run it covers on the ground, whatever it
	 * climbs. Summed in short straight steps, which for a bezier this gentle is good to a thousandth of
	 * a block.
	 */
	public static double horizontalLength(BezierConnection curve) {
		double length = 0;
		Vec3 previous = curve.getPosition(0);
		for (int i = 1; i <= LENGTH_SAMPLES; i++) {
			Vec3 point = curve.getPosition(i / (double) LENGTH_SAMPLES);
			length += Math.sqrt((point.x - previous.x) * (point.x - previous.x)
				+ (point.z - previous.z) * (point.z - previous.z));
			previous = point;
		}
		return length;
	}

	/**
	 * Whether an end of a curve will climb at all, given the climb of the ends that are decided and
	 * how far the curve rises. See where {@link #solve} uses it for why this has to be known first.
	 *
	 * @param own   the climb this end is obliged to have, or null if it is free.
	 * @param other the same for the other end.
	 */
	private static boolean sloped(@Nullable Double own, @Nullable Double other, double rise) {
		if (own != null)
			return own != 0;
		return rise != 0 || (other != null && other != 0);
	}

	/**
	 * A slope as the anchor will hold it, which is a float, and never zero for an end that was worked
	 * out to climb. A free end can come out of the arithmetic as exactly nothing — a curve that
	 * climbs out of a slope at the rate it has to climb to end level — and an anchor with none puts
	 * its node on the block's boundary, away from where the plan put it.
	 */
	private static double keepSloped(double slope, boolean sloped) {
		if (!sloped)
			return 0;
		float held = (float) slope;
		return Math.abs(held) < MIN_SLOPE ? (held < 0 ? -MIN_SLOPE : MIN_SLOPE) : held;
	}

	/**
	 * How long the handles of {@code curve} are seen from above: those of the same curve laid level. This
	 * is the {@code h} in the grade formulas, and it is not what the curve reports for itself. Create
	 * normalises an axis with its climb in it and takes a straight curve's handles from its length in
	 * three dimensions, so {@link BezierConnection#getHandleLength} comes out longer than this by the
	 * secant of the angle it climbs at.
	 */
	public static double levelHandleLength(BezierConnection curve) {
		return handleLength(curve.starts.getFirst(), curve.starts.getSecond(), flatUnit(curve.axes.getFirst()),
			flatUnit(curve.axes.getSecond()
				.scale(-1)));
	}

	/**
	 * How long a bezier's handles are between these two points, as Create works them out from the shape
	 * of the curve seen from above. The ends are put at one height to ask: for a curve that is straight
	 * Create measures its length in three dimensions, so the answer would otherwise grow with the climb,
	 * and what the grade formulas want is that of the level curve whatever the climb.
	 */
	static double handleLength(Vec3 from, Vec3 to, Vec3 fromHeading, Vec3 toHeading) {
		return curveBetween(BlockPos.ZERO, BlockPos.ZERO, from, new Vec3(to.x, from.y, to.z), fromHeading,
			toHeading).getHandleLength();
	}

	/**
	 * The climb at the two ends of a curve that rises {@code rise}, given those of them that are
	 * already decided.
	 *
	 * <p>The curve's height along it is a cubic bezier whose control points sit {@code handle × slope}
	 * above or below the ends, because Create makes each handle the end's axis times the handle
	 * length. So {@code rise}, the two slopes and {@code handle} are tied together, and with one
	 * slope given the other has a choice to be made:
	 * <ul>
	 * <li>Neither given: the same at both ends, a third of the rise over the handle, which makes the
	 * height a straight line along the curve and so a constant grade.</li>
	 * <li>One given: the other that makes the height change at the gentlest rate where it arrives, its
	 * second derivative nothing there. For a curve that has to end at a given height this comes out
	 * as a gradual easing into or out of the slope.</li>
	 * <li>Both given: nothing to work out.</li>
	 * </ul>
	 *
	 * @return the slope at the start and at the end, in the direction of travel.
	 */
	static double[] solveSlopes(double handle, double rise, @Nullable Double fixedStart, @Nullable Double fixedEnd) {
		if (handle < 1.0E-6)
			return new double[] { fixedStart == null ? 0 : fixedStart, fixedEnd == null ? 0 : fixedEnd };
		if (fixedStart != null && fixedEnd != null)
			return new double[] { fixedStart, fixedEnd };
		if (fixedStart != null)
			return new double[] { fixedStart, (rise - handle * fixedStart) / (2 * handle) };
		if (fixedEnd != null)
			return new double[] { (rise - handle * fixedEnd) / (2 * handle), fixedEnd };
		double slope = rise / (3 * handle);
		return new double[] { slope, slope };
	}

	/** {@link #solveSlopes} for the two ends of a planned curve, with the level case left alone. */
	private static double[] slopesBetween(Vec3 startPoint, Vec3 endPoint, Vec3 startHeading, Vec3 endHeading,
		@Nullable Double fixedStart, @Nullable Double fixedEnd, boolean startSloped, boolean endSloped) {
		if (!startSloped && !endSloped)
			return new double[] { 0, 0 };
		double[] slopes = solveSlopes(handleLength(startPoint, endPoint, startHeading, endHeading),
			endPoint.y - startPoint.y, fixedStart, fixedEnd);
		return new double[] { keepSloped(slopes[0], startSloped), keepSloped(slopes[1], endSloped) };
	}

	/** Headings are horizontal; a curve climbs by having its ends at different heights, not by tilting. */
	static Vec3 flat(Vec3 vector) {
		return new Vec3(vector.x, 0, vector.z);
	}

	static Vec3 flatUnit(Vec3 vector) {
		Vec3 flattened = flat(vector);
		return flattened.lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : flattened.normalize();
	}
}
