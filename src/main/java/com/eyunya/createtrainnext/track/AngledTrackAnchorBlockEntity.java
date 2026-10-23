package com.eyunya.createtrainnext.track;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNRegistry;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.AllShapes;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackPropagator;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One anchor in a chain of free-angle track.
 *
 * <p>An anchor is a point on a route with a heading, and it holds a link in each direction along
 * that route. Chaining is what makes the geometry work rather than being a convenience: an anchor's
 * heading gives the curve behind it the tangent {@code -heading} and the curve ahead of it
 * {@code +heading}, which are collinear, so every joint in the middle of a chain is smooth by
 * construction. Nothing has to be aimed by hand to make it line up.
 *
 * <p>Only the two ends of a chain have to reconcile a free angle with Create's discrete axes, and
 * only there does the anchor's own span across its block bend to meet one — see {@link #buildSpan}.
 *
 * <p>What stays quantised is where anchors may sit, never which way they point:
 * {@code TrackNodeLocation} rounds graph nodes onto a half-block lattice, so the span's two ends
 * are offset along one of {@link AnchorAxis}'s four directions. Tangents are untouched by the
 * graph and by NBT.
 *
 * <p>It is a {@link TrackBlockEntity} so that Create's own curve targeting works on its curves:
 * picking a segment of a curve, the selection packet and {@code getBezierGraphLocationAt} all look
 * for a {@code TrackBlockEntity} holding the curve in its connections. The connections are a
 * mirror of what the links say, kept by {@link #syncConnections}; the graph is still built from
 * the links, by {@link AngledTrackAnchorBlock#getConnected}, and never reads them.
 */
public class AngledTrackAnchorBlockEntity extends TrackBlockEntity {

	/**
	 * How often the floor under this anchor's curve is renewed, in ticks.
	 *
	 * <p>Has to be well inside how long a {@link CurveFillerBlock} survives without being renewed,
	 * and that is measured in random ticks, so five seconds is not close to the edge. It is also
	 * what puts a floor back under a curve whose far half was in an unloaded chunk when it changed.
	 */
	private static final int OCCUPANCY_REFRESH_TICKS = 100;

	/**
	 * Slack added on top of the disagreement the two edges at a node actually show, so the
	 * comparison is not sitting exactly on its own limit once float error is added to it.
	 */
	private static final double TARGETING_MARGIN = Math.toRadians(2);

	/** The most a node may be forgiven. See {@link #reachFor}. */
	private static final double MAX_TARGETING_SLACK = Math.toRadians(60);

	/**
	 * The longest route {@link #route} will walk before deciding something is wrong with it.
	 *
	 * <p>Far longer than anyone lays by hand, and short enough that a link which has gone round in a
	 * circle is given up on quickly rather than followed until the server stops responding.
	 */
	private static final int MAX_ROUTE_ANCHORS = 4096;

	/** The normal of level track. */
	private static final Vec3 UP = new Vec3(0, 1, 0);

	private static final String TAG_YAW = "Yaw";
	private static final String TAG_PREV = "Prev";
	private static final String TAG_NEXT = "Next";
	private static final String TAG_AXIS_FLIPPED = "AxisFlipped";
	private static final String TAG_SLOPE = "Slope";
	private static final String TAG_SLOPE_DOCK = "SlopeDock";

	private float yaw = 0.0F;

	/**
	 * How steeply the route climbs through this anchor: the rise over the run, so 0.05 is 50 permille
	 * and a minus sign is a descent. It belongs to the route's direction, like {@link #yaw}, and
	 * turning the route round negates it.
	 *
	 * <p>Zero for every anchor that was never given one, which includes all of those in saves made
	 * before there was any, so they read exactly as they did: flat headings, and a climb carried by
	 * the difference in height between the two ends of a curve.
	 *
	 * <p>It is kept as a slope and not as an angle because that is the form Create's own axes take. Its
	 * bezier works the handles out from the horizontal part of an axis alone, so an axis written as a
	 * horizontal unit vector plus {@code (0, slope, 0)} leaves the curve's shape seen from above exactly
	 * what it was and only changes how it climbs. Create's sloped track uses {@code (0, 1, 1)} the same way.
	 */
	private float slope = 0.0F;

	/** The anchor this route arrives from, and the one it continues to. Either may be absent. */
	@Nullable
	private BlockPos prev = null;
	@Nullable
	private BlockPos next = null;

	/**
	 * Whether the axis this anchor reports is the opposite of the one it would report if nothing
	 * had to be held: its block's own axis for an end, and the one measured along the route for an
	 * anchor at the centre of its block.
	 *
	 * <p>Create keeps a signal's facing as nothing more than a sign against the reported axis — it
	 * is the front-or-back flag {@code TrackGraphHelper.getGraphLocationAt} multiplies the axis by —
	 * so that sign is a promise to everything standing on the anchor, and the surroundings of an
	 * anchor change in ways that would break it. Turning the route round negates the axis measured
	 * along it ({@link #reverseRoute}). An end that gains a curve starts reporting that one instead
	 * of its block's, and an anchor that loses one goes back, and where the route runs against the
	 * block's axis — about half the time — the two have opposite signs. Any of them would leave
	 * every signal and station on the anchor facing the other way, and put it on the other edge the
	 * next time the graph was rebuilt around it. {@link #keepAxisSign} sets this to hold the sign
	 * through each.
	 *
	 * <p>False for every anchor that never needed it, which includes all of those in saves made
	 * before this existed, so they report exactly what they always did.
	 */
	private boolean flipped = false;

	/**
	 * Whether the side of this anchor that has no curve is joined to the end of a piece of Create's sloped
	 * track, which then shares this anchor's one node.
	 *
	 * <p>Neither of the places an end usually puts its node meets a slope. A slope's end node is on the
	 * boundary of the block beyond it, at that block's floor, and the track there runs at 45 degrees. An
	 * end that climbs puts its node at the centre of its block, half a block from the slope's; an end that
	 * is level keeps a span across its block, and a level span against a 45 degree slope is a joint
	 * {@code TrackEdge.canTravelTo} lets no train across. So a docked end has a single node, on the
	 * boundary of its free side at its floor, which is the slope's own, and its curve leaves from there
	 * along the slope's axis. See {@link #dockNode}.
	 *
	 * <p>It only means anything for an end, an anchor with a curve on one side: see {@link #isSlopeDocked}.
	 * The wand sets it as it lays the curve ({@link #linkTo(AngledTrackAnchorBlockEntity, Vec3, Vec3,
	 * boolean, boolean)}), and it stays through whatever leaves the anchor an end — the route turned round,
	 * a point put on the curve, the curve mended — since all of those keep the free side free. It is
	 * dropped once the anchor has a curve on both sides or none, either of which leaves it no free side
	 * that faces the slope, and once the slope is no longer there to share the node: see
	 * {@link #undockIfSlopeGone}. False for every anchor saved before there was any.
	 */
	private boolean slopeDock = false;

	@Nullable
	private BezierConnection cachedCurve = null;
	private long cachedKey = Long.MIN_VALUE;

	/** What {@link #collisionShape} last worked out, and the curves it worked it out from. */
	@Nullable
	private VoxelShape climbingFloor = null;

	@Nullable
	private BezierConnection floorFromPrev = null;

	@Nullable
	private BezierConnection floorFromNext = null;

	/** The curve this anchor last laid a floor along, so a floor that moves can be taken up. */
	@Nullable
	private BezierConnection occupied = null;

	private int occupancyTimer;

	public AngledTrackAnchorBlockEntity(BlockPos pos, BlockState state) {
		super(CTNRegistry.ANGLED_TRACK_ANCHOR_ENTITY.get(), pos, state);
		// Start the yaw already agreeing with the axis the block was placed on, so a fresh anchor
		// is straight rather than bent until it is first turned.
		yaw = state.getValue(AngledTrackAnchorBlock.AXIS)
			.defaultYaw();
		// Spread the refreshes out by position rather than having every anchor on a route come due
		// on the same tick, which on a long line would be one very lumpy tick every five seconds.
		occupancyTimer = Math.floorMod(pos.hashCode(), OCCUPANCY_REFRESH_TICKS) + 1;
	}

	// ---------------------------------------------------------------- state

	public float getYaw() {
		return yaw;
	}

	/** See {@link #slope}. */
	public float getSlope() {
		return slope;
	}

	/**
	 * A direction of travel written the way the curves take it: the horizontal unit vector
	 * {@code heading} with the climb as its y component, which is not a unit vector.
	 */
	public static Vec3 tangentOf(Vec3 heading, double slope) {
		return new Vec3(heading.x, slope, heading.z);
	}

	/**
	 * The normal of track that runs along {@code heading} climbing at {@code slope}: straight up, tilted back
	 * against the climb. It is the vector Create gives its own sloped track, {@code (0, 1, 1)} for the one that
	 * climbs north, and it is the same whichever way the route is read, since turning round negates the heading
	 * and the climb together.
	 */
	public static Vec3 slopeNormal(Vec3 heading, double slope) {
		double length = Math.sqrt(1 + slope * slope);
		return new Vec3(-slope * heading.x / length, 1 / length, -slope * heading.z / length);
	}

	/** The climb in a vector made by {@link #tangentOf}: its y over the length of its horizontal part. */
	public static double slopeOf(Vec3 tangent) {
		double run = Math.sqrt(tangent.x * tangent.x + tangent.z * tangent.z);
		return run < 1.0E-9 ? 0 : tangent.y / run;
	}

	/** Which way the route runs through this anchor, and how steeply it climbs there. */
	public Vec3 tangent() {
		return tangentOf(heading(), slope);
	}

	/** See {@link #flipped}. */
	public boolean isFlipped() {
		return flipped;
	}

	/**
	 * Whether this anchor is an end docked onto Create's sloped track, with its node on the slope's end:
	 * see {@link #slopeDock}. Only an end can be, so this is false for an anchor with a curve on both sides
	 * or on neither, whatever was set.
	 */
	public boolean isSlopeDocked() {
		return slopeDock && (prev == null) != (next == null);
	}

	/**
	 * Whether the slope this end is docked onto is still there to share its node: a block beside the node
	 * that reports it, as Create's walk would find it, with a sloped axis. Mined, or replaced by anything
	 * else, it is not, and that includes level track, whose node at the same place would meet the docked
	 * curve at 45 degrees.
	 *
	 * <p>True when it cannot be told: a block beside the node in a chunk that is not loaded leaves the dock as
	 * it is, since the slope may be in it.
	 */
	private boolean slopeStillThere() {
		Vec3 node = dockNode();
		if (node == null || level == null)
			return true;
		TrackNodeLocation location = new TrackNodeLocation(node).in(level);
		for (BlockPos pos : location.allAdjacent()) {
			if (!level.isLoaded(pos))
				return true;
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof ITrackBlock track) || state.getBlock() instanceof AngledTrackAnchorBlock)
				continue;
			boolean sloped = track.getTrackAxes(level, pos, state)
				.stream()
				.anyMatch(axis -> Math.abs(axis.y) > 1.0E-4);
			if (sloped && track.getConnected(level, pos, state, false, null)
				.contains(location))
				return true;
		}
		return false;
	}

	/**
	 * Takes this end off a slope that has gone, so that it is an ordinary end again, with a side free to
	 * carry the route on from.
	 *
	 * <p>Nothing tells an anchor that a block next to it has gone: the slope under the top of a dock is not
	 * even beside it, but one down and one along. So the anchor looks, on its tick. In the tick between,
	 * the graph has the node the slope took with it put back by the anchor, which still reports it, as
	 * Create rebuilds what was beside the slope; this takes it out again the way {@link #linkTo} does any
	 * node that stops being reported.
	 */
	private void undockIfSlopeGone() {
		if (!isSlopeDocked() || slopeStillThere())
			return;

		Map<AngledTrackAnchorBlockEntity, Vec3> reported = reportedBy(this, anchorAt(prev), anchorAt(next));
		Set<TrackNodeLocation> stale = detachNeighbourhood();
		slopeDock = false;
		invalidateNeighbourhood();
		keepAxisSigns(reported);
		pruneGraph(stale);
		attachNeighbourhood();
	}

	/** Drops {@link #slopeDock} from an anchor that is no longer an end, once its links have settled. */
	private void dropDockUnlessEnd() {
		if (!slopeDock || (prev == null) != (next == null))
			return;
		slopeDock = false;
		markChanged();
	}

	/**
	 * Chooses {@link #flipped} so that the axis this anchor reports now points the way
	 * {@code previous} did, for an anchor whose surroundings have just changed.
	 *
	 * <p>What changes is which of two axes it reports, or which way the second points. An end
	 * reports its block's discrete axis, whose sign is whatever the four vectors in
	 * {@link AnchorAxis} happen to be. An anchor with a route through it reports one measured along
	 * the route, whose sign is the way the route runs. Joining an end to something turns it from the
	 * first into the second, unlinking a neighbour turns it back, and where the route runs against
	 * the block's axis the sign changes with each. Turning the route round changes the second and
	 * leaves the first alone.
	 *
	 * <p>Has to run once the links are in place and before the graph is rebuilt around the anchor,
	 * which is queued for the next tick: a signal that finds its edge gone puts itself back by
	 * resolving this axis again.
	 */
	public void keepAxisSign(Vec3 previous) {
		boolean wanted = naturalAxis().dot(previous) < 0;
		if (wanted == flipped)
			return;

		flipped = wanted;
		markChanged();
	}

	/**
	 * What each of these anchors reports now, to be handed to {@link #keepAxisSigns} once something
	 * has changed their links. Only those with a curve are included: without one there is no edge
	 * for anything to stand on, and so nothing whose facing is worth keeping.
	 */
	private static Map<AngledTrackAnchorBlockEntity, Vec3> reportedBy(AngledTrackAnchorBlockEntity... anchors) {
		Map<AngledTrackAnchorBlockEntity, Vec3> reported = new LinkedHashMap<>();
		for (AngledTrackAnchorBlockEntity anchor : anchors)
			if (anchor != null && anchor.isLinked())
				reported.put(anchor, anchor.targetingAxis());
		return reported;
	}

	private static void keepAxisSigns(Map<AngledTrackAnchorBlockEntity, Vec3> reported) {
		reported.forEach(AngledTrackAnchorBlockEntity::keepAxisSign);
	}

	@Nullable
	public BlockPos getPrev() {
		return prev;
	}

	@Nullable
	public BlockPos getNext() {
		return next;
	}

	public boolean isLinked() {
		return prev != null || next != null;
	}

	/**
	 * Whether a curve could still be attached to this anchor.
	 *
	 * <p>An anchor holds one link in each direction, so it has two sides and a route runs through
	 * it rather than branching at it. A new curve takes whichever side is free: out of the next
	 * side, carrying the route on, or out of the prev side, going back the way the route came.
	 *
	 * <p>An end docked onto a slope has none. The side without a curve is the slope's, and a curve there
	 * would make the anchor one with a curve on each side, whose node is at its centre and not on the
	 * slope's end.
	 */
	public boolean hasFreeSide() {
		return (prev == null || next == null) && !isSlopeDocked();
	}

	/**
	 * Points the span at whichever of the four lattice-safe axes sits closest to the heading.
	 *
	 * <p>The span's ends have to land on the node lattice, so they can only be offset along those
	 * four. Taking the nearest keeps the offset within 22.5 degrees of the heading, which is what
	 * holds the span's sideways wander across the block down to something not worth seeing.
	 */
	private void alignAxisToYaw() {
		if (level == null || level.isClientSide)
			return;

		AnchorAxis wanted = AnchorAxis.nearestTo(heading());
		BlockState state = getBlockState();
		if (state.getValue(AngledTrackAnchorBlock.AXIS) == wanted)
			return;

		// Same block, only a property changes, so the block entity and its links survive this.
		level.setBlock(worldPosition, state.setValue(AngledTrackAnchorBlock.AXIS, wanted), Block.UPDATE_ALL);
	}

	/**
	 * Continues the route from this anchor to {@code other}, aiming both ends as it goes.
	 *
	 * <p>Aiming happens here rather than through separate calls either side of the link because
	 * each of those would tear the neighbourhood out of the track graph and queue it to be rebuilt.
	 * Doing all three at once means one teardown and one rebuild, and — more to the point — no
	 * intermediate state where one end has been turned and the other has not, which is a shape the
	 * graph would have had to accept as real for a tick.
	 *
	 * @param thisHeading  where this anchor should point afterwards, or null to leave it. The y of the
	 *                     vector is the climb, as {@link #tangentOf} writes it: a vector with none
	 *                     leaves the anchor level.
	 * @param otherHeading where {@code other} should point afterwards, or null to leave it.
	 */
	public void linkTo(AngledTrackAnchorBlockEntity other, @Nullable Vec3 thisHeading,
		@Nullable Vec3 otherHeading) {
		linkTo(other, thisHeading, otherHeading, false, false);
	}

	/**
	 * The same, docking either anchor onto Create's sloped track at the side the link leaves free: see
	 * {@link #slopeDock}. It is set in the same step as the link rather than before or after it, so that
	 * the graph is torn down around what the anchors reported and rebuilt around where their nodes are
	 * now, once, with nothing reported in between.
	 *
	 * <p>An anchor that already had a curve and is not docked here keeps whatever it was, which is how one
	 * docked stays so when its curve is moved to a new neighbour. One that had none is docked exactly when
	 * it is asked to be: it has just become an end, and nothing it was before is about this end. Putting a
	 * point on a curve or mending one takes the far anchor's link away and gives it a new one in the next
	 * call, so those pass on whether it was docked.
	 *
	 * @param thisDocks  whether this anchor's free side is to be the slope's end.
	 * @param otherDocks the same for {@code other}.
	 */
	public void linkTo(AngledTrackAnchorBlockEntity other, @Nullable Vec3 thisHeading, @Nullable Vec3 otherHeading,
		boolean thisDocks, boolean otherDocks) {
		if (other == this)
			return;
		boolean thisWasLone = !isLinked();
		boolean otherWasLone = !other.isLinked();

		// What every anchor this is about to change reports, so that each can go on reporting the
		// same way afterwards: see keepAxisSign. Whatever is displaced from either side loses a link
		// too, so it is included.
		Map<AngledTrackAnchorBlockEntity, Vec3> reported =
			reportedBy(this, other, anchorAt(next), other.anchorAt(other.prev));

		// Where the graph's nodes are about to stop being: see pruneGraph.
		Set<TrackNodeLocation> stale = detachNeighbourhood();
		stale.addAll(other.detachNeighbourhood());

		// Whatever used to follow this anchor, or lead into the other, loses its half of the link.
		clearNextOfThis();
		other.clearPrevOfThis();

		if (thisHeading != null) {
			yaw = yawOf(thisHeading);
			slope = (float) slopeOf(thisHeading);
			alignAxisToYaw();
		}
		if (otherHeading != null) {
			other.yaw = yawOf(otherHeading);
			other.slope = (float) slopeOf(otherHeading);
			other.alignAxisToYaw();
		}

		next = other.getBlockPos();
		other.prev = worldPosition;

		// Before the axes are held and the graph is pruned, both of which go by where the nodes are now.
		if (thisDocks || thisWasLone)
			slopeDock = thisDocks;
		if (otherDocks || otherWasLone)
			other.slopeDock = otherDocks;
		dropDockUnlessEnd();
		other.dropDockUnlessEnd();

		invalidateNeighbourhood();
		other.invalidateNeighbourhood();
		keepAxisSigns(reported);
		pruneGraph(stale);
		attachNeighbourhood();
		other.attachNeighbourhood();
	}

	/** Drops both of this anchor's links, and the matching halves on its neighbours. */
	public void unlink() {
		if (!isLinked())
			return;

		// The neighbours become ends, and an end reports a different axis from an anchor in the
		// middle of a route: see keepAxisSign. This one is left to report whatever it does, since with
		// no curve left it has no edge for anything to stand on.
		Map<AngledTrackAnchorBlockEntity, Vec3> reported = reportedBy(anchorAt(prev), anchorAt(next));
		AngledTrackAnchorBlockEntity before = anchorAt(prev);
		AngledTrackAnchorBlockEntity after = anchorAt(next);

		Set<TrackNodeLocation> stale = detachNeighbourhood();
		clearNextOfThis();
		clearPrevOfThis();
		// Neither this anchor nor a neighbour whose only curve this was has a curve left, and an anchor
		// docked onto a slope is an end of one.
		slopeDock = false;
		if (before != null)
			before.dropDockUnlessEnd();
		if (after != null)
			after.dropDockUnlessEnd();
		invalidateNeighbourhood();
		keepAxisSigns(reported);
		pruneGraph(stale);
		attachNeighbourhood();
	}

	private void clearNextOfThis() {
		AngledTrackAnchorBlockEntity following = anchorAt(next);
		if (following != null) {
			following.prev = null;
			following.markChanged();
			// It has just dropped out of this anchor's neighbourhood, so the rebuild queued at the
			// end of the operation will not reach it. Without this it stays out of the graph.
			following.attachNeighbourhood();
		}
		next = null;
		markChanged();
	}

	private void clearPrevOfThis() {
		AngledTrackAnchorBlockEntity leading = anchorAt(prev);
		if (leading != null) {
			leading.next = null;
			leading.markChanged();
			leading.attachNeighbourhood();
		}
		prev = null;
		markChanged();
	}

	// ---------------------------------------------------------------- route

	/**
	 * Every anchor in the route this one belongs to, in the order the route runs, or null if the
	 * route cannot be walked in full.
	 *
	 * <p>Null means a link leads somewhere that is not a loaded anchor, or that the two anchors on
	 * either end of a link do not agree about it, or that the walk went on past
	 * {@link #MAX_ROUTE_ANCHORS} or round in a circle. All of those would have a caller act on part
	 * of a route, and for turning one round that is worse than not doing it at all: the half left
	 * alone would go on running the old way.
	 */
	@Nullable
	public List<AngledTrackAnchorBlockEntity> route() {
		// Back to the first anchor and then forwards to the last, so the list comes out in the order
		// the route runs.
		Set<BlockPos> seen = new HashSet<>();
		seen.add(worldPosition);
		AngledTrackAnchorBlockEntity first = this;
		while (first.prev != null) {
			AngledTrackAnchorBlockEntity behind = first.anchorAt(first.prev);
			if (behind == null || !seen.add(behind.worldPosition) || seen.size() > MAX_ROUTE_ANCHORS)
				return null;
			first = behind;
		}

		List<AngledTrackAnchorBlockEntity> route = new ArrayList<>();
		AngledTrackAnchorBlockEntity cursor = first;
		while (true) {
			route.add(cursor);
			if (route.size() > MAX_ROUTE_ANCHORS)
				return null;
			if (cursor.next == null)
				break;

			// A link is held by both of its anchors, and the route is only the one it looks like if
			// they agree about it.
			AngledTrackAnchorBlockEntity ahead = cursor.anchorAt(cursor.next);
			if (ahead == null || !cursor.worldPosition.equals(ahead.prev))
				return null;
			cursor = ahead;
		}

		// Walking forwards has to pass through the anchor the walk back started from, or the two
		// halves were not the same route.
		return route.contains(this) ? route : null;
	}

	/**
	 * Turns the whole route this anchor belongs to end for end, so that what was its last anchor is
	 * its first and every curve in it runs the other way.
	 *
	 * <p>The track itself does not move, and that is why the graph is left alone: swapping an
	 * anchor's prev and next and pointing it the opposite way puts its entry node where its exit
	 * node was and the other way round, to the bit, because the offset is taken along an axis that
	 * has no sign and negated with the heading. The same nodes are reported, the same edges join
	 * them, and tearing the graph down to rebuild exactly what it already holds would only make
	 * Create throw away the signals and stations on the way.
	 *
	 * <p>Two things do change, both slightly. A curve is built by the anchor behind it, and a
	 * bezier's handles are worked out by Create from its second end, so a curve whose ends are not
	 * mirror images of one another comes out a little differently when the other end builds it — by a
	 * fifth of a block or less over twenty, for ends within 25 degrees of the chord. And the floor
	 * under each curve is laid by its owner, which is now the other anchor: it is renewed from there
	 * within a few seconds, and what falls outside the new shape expires on its own.
	 *
	 * @return how many anchors were turned round, or zero if the route could not be reached in full,
	 *         in which case nothing has changed.
	 */
	public int reverseRoute() {
		if (level == null || level.isClientSide)
			return 0;

		List<AngledTrackAnchorBlockEntity> route = route();
		if (route == null)
			return 0;

		// Every anchor's report is taken before any of them is turned, since each is measured off
		// its neighbours, and put right once all of them have been.
		Map<AngledTrackAnchorBlockEntity, Vec3> reported =
			reportedBy(route.toArray(new AngledTrackAnchorBlockEntity[0]));
		for (AngledTrackAnchorBlockEntity anchor : route)
			anchor.turnAround();
		keepAxisSigns(reported);
		return route.size();
	}

	private void turnAround() {
		BlockPos behind = prev;
		prev = next;
		next = behind;

		yaw = Mth.wrapDegrees(yaw + 180.0F);
		// Running the other way, what was a climb is a descent.
		slope = -slope;

		// What was recorded is the curve this anchor used to own, which it no longer does, and
		// clearing it would take up a floor that its new neighbour is about to renew.
		occupied = null;
		invalidateCurve();
		markChanged();
	}

	@Nullable
	private AngledTrackAnchorBlockEntity anchorAt(@Nullable BlockPos pos) {
		if (level == null || pos == null || !level.isLoaded(pos))
			return null;
		return level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor ? anchor : null;
	}

	private void markChanged() {
		connectionsStale = true;
		sendUpdate();

		// A curve is built from both its ends, and is held by whichever of them has the lower
		// position, so a change here can change what the anchor on either side is holding.
		for (BlockPos neighbour : new BlockPos[] { prev, next }) {
			AngledTrackAnchorBlockEntity anchor = anchorAt(neighbour);
			if (anchor != null) {
				anchor.connectionsStale = true;
				anchor.sendUpdate();
			}
		}
	}

	private void sendUpdate() {
		setChanged();
		if (level != null && !level.isClientSide)
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
	}

	// ---------------------------------------------------------- connections

	/**
	 * Whether the client is letting curves be picked at the moment. Set by the client, and false
	 * everywhere else.
	 *
	 * <p>Create's {@code pickCurves} replaces what the player is looking at with a miss whenever the
	 * crosshair is over a curve, and the wand reads that to know which block it is aimed at. So
	 * curves are only offered to be picked while a signal, station or the like is in hand — see
	 * {@code CurveTargeting}.
	 */
	private static volatile boolean curveInteraction = false;

	public static void setCurveInteraction(boolean enabled) {
		curveInteraction = enabled;
	}

	/**
	 * Whether Create should offer this anchor's curves to be picked.
	 *
	 * <p>Asked when block entity data arrives, to decide whether to register this anchor with the
	 * client's list of curves to pick from. Answering by the flag above is what keeps an anchor out
	 * of that list while a wand is in hand.
	 *
	 * <p>Only a client's anchors are ever offered. The list is the client's, and a server's anchors —
	 * on an integrated server, where the flag is set — have no business in it.
	 */
	@Override
	public boolean hasInteractableConnections() {
		return level != null && level.isClientSide && curveInteraction && super.hasInteractableConnections();
	}

	/** Whether this anchor holds a curve for Create's targeting, whatever the client is offering. */
	public boolean hasCurves() {
		return super.hasInteractableConnections();
	}

	/**
	 * Which of the two anchors a curve joins holds it for Create: the one at the lower position.
	 *
	 * <p>Create keeps a curve on the block entity at one end of it, as the primary connection, and a
	 * signal placed on the curve remembers that end and the other one. If the end chosen followed
	 * the route's direction, turning a route round would move every curve to its other end, and the
	 * signals on them would be left pointing at a connection that is no longer there. A position
	 * does not change when a route does.
	 */
	private boolean holdsCurveWith(BlockPos other) {
		return worldPosition.compareTo(other) < 0;
	}

	/** The same curve as its far end sees it, which is how that end holds it as the primary. */
	private static BezierConnection fromFarEnd(BezierConnection curve) {
		return new BezierConnection(curve.bePositions.swap(), curve.starts.swap(), curve.axes.swap(),
			curve.normals.swap(), true, curve.hasGirder, TrackMaterial.ANDESITE);
	}

	/**
	 * Whether the links, or a neighbour's, have changed since the connections were last brought into
	 * line with them.
	 *
	 * <p>Brought into line when they are next looked at rather than at every change. A link operation
	 * changes an anchor's state several times over, and a route turned round changes each of its
	 * anchors in turn, so doing it at each change builds curves out of states that are half way
	 * between two real ones, and builds them many times. It is looked at when Create asks for the
	 * connections and when the anchor is saved or sent to a client, which is after the operation.
	 */
	private boolean connectionsStale = true;

	/** The connections Create reads, brought into line with the links first if they have changed. */
	@Override
	public Map<BlockPos, BezierConnection> getConnections() {
		if (refreshConnections())
			sendUpdate();
		return super.getConnections();
	}

	/** @return whether the connections changed, which is whether the client needs to be told. */
	private boolean refreshConnections() {
		if (!connectionsStale || level == null || level.isClientSide)
			return false;
		connectionsStale = false;
		return syncConnections();
	}

	/**
	 * Whether a connection already held is the one wanted: the same curve, held from the same end, as
	 * the primary.
	 *
	 * <p>{@code equalsSansMaterial} is not enough. It takes a curve and its reverse for the same, and
	 * says nothing about the primary flag, and those are the two things a connection read back from a
	 * save or a client can have wrong while looking right.
	 */
	private static boolean sameConnection(@Nullable BezierConnection held, BezierConnection wanted) {
		return held != null && held.isPrimary() == wanted.isPrimary() && held.bePositions.getFirst()
			.equals(wanted.bePositions.getFirst()) && held.equalsSansMaterial(wanted);
	}

	/**
	 * Brings the connections Create sees up to date with the curves this anchor is a link of.
	 *
	 * <p>Create's curve targeting finds a curve by looking in a {@code TrackBlockEntity}'s
	 * connections, keyed by the block at the other end. This anchor's curves are built from its links
	 * on demand, so nothing is ever put there; this puts there the curves it holds — see
	 * {@link #holdsCurveWith} — as primary connections, and takes out whatever is no longer one.
	 *
	 * <p>A curve that cannot be built because the anchor at its other end is not loaded is left as
	 * it was. Taking it out would be an answer, and an unloaded chunk is not one: a signal on the
	 * curve would have nothing to stand on until the chunk came back. The same for a link the other
	 * anchor does not agree about, which is what the middle of turning a route round looks like. A
	 * block that is loaded and is not an anchor any more is an answer, and the curve goes.
	 *
	 * @return whether the connections changed, which is whether the client needs to be told.
	 */
	private boolean syncConnections() {
		if (level == null || level.isClientSide)
			return false;

		Map<BlockPos, BezierConnection> held = super.getConnections();
		Map<BlockPos, BezierConnection> wanted = new HashMap<>();

		for (boolean ahead : new boolean[] { true, false }) {
			BlockPos other = ahead ? next : prev;
			if (other == null || !holdsCurveWith(other))
				continue;

			AngledTrackAnchorBlockEntity neighbour = anchorAt(other);
			if (neighbour == null) {
				if (!level.isLoaded(other) && held.containsKey(other))
					wanted.put(other, held.get(other));
				continue;
			}

			// A link is held by both of its anchors, and the curve is the one it looks like only if
			// they agree about it.
			if (!worldPosition.equals(ahead ? neighbour.prev : neighbour.next)) {
				if (held.containsKey(other))
					wanted.put(other, held.get(other));
				continue;
			}

			BezierConnection curve = ahead ? buildCurveToNext() : buildCurveFromPrev();
			if (curve != null)
				wanted.put(other, ahead ? curve : fromFarEnd(curve));
		}

		boolean same = held.size() == wanted.size();
		for (Map.Entry<BlockPos, BezierConnection> entry : wanted.entrySet()) {
			if (!same)
				break;
			same = sameConnection(held.get(entry.getKey()), entry.getValue());
		}
		if (same)
			return false;

		held.clear();
		held.putAll(wanted);
		return true;
	}

	/** Whether a signal, station or the like stands on a curve this anchor is one end of. */
	public boolean carriesPoints() {
		if (level == null)
			return false;
		BezierConnection ahead = buildCurveToNext();
		BezierConnection behind = buildCurveFromPrev();
		return ahead != null && CurvePlanner.carriesPoints(level, ahead)
			|| behind != null && CurvePlanner.carriesPoints(level, behind);
	}

	/**
	 * Create asks for these to be taken out when a curve is broken by hand, and nothing is: a curve
	 * here is its links, and the way to undo one is the wand.
	 *
	 * <p>It is not asked. The packet that breaks a curve is stopped before it gets here, by
	 * {@code CurvedTrackDestroyPacketMixin}, because what it does around this call — handing out the
	 * curve's track and taking the block's nodes out of the graph — is not something this can stop.
	 * Kept so that nothing else can take the connections away from under the links.
	 */
	@Override
	public void removeConnection(BlockPos target) {}

	/**
	 * Create lays an invisible floor of its own along every curve it holds, here, which is what
	 * {@link CurveFillerBlock} is for. Doing both would be two floors to renew and take up.
	 */
	@Override
	public void lazyTick() {}

	/** As {@link #lazyTick}: Create's version takes up the floor it would have laid. */
	@Override
	public void remove() {}

	// ---------------------------------------------------------------- graph

	/**
	 * Tears this anchor and both neighbours out of the graph. All three have to go: a curve is
	 * reported by the anchor behind it, so changing this one's heading changes an edge that its
	 * predecessor owns. Must run <em>before</em> the state those edges derive from changes, or the
	 * propagator removes the wrong nodes and strands the rest.
	 *
	 * <p>That teardown does not leave the graph without them. The propagator ends by rebuilding what
	 * is next to the nodes it removed, from what those blocks report <em>now</em>, which is the state
	 * about to be changed — so the nodes just taken out are put straight back. They are then the
	 * propagator's to remove only for as long as something reports them.
	 *
	 * @return every location the three report before the change, to be given to {@link #pruneGraph}
	 *         once it has been made.
	 */
	private Set<TrackNodeLocation> detachNeighbourhood() {
		Set<TrackNodeLocation> reported = new LinkedHashSet<>();
		if (level == null || level.isClientSide)
			return reported;

		reported.addAll(GraphPruner.reportedBy(level, worldPosition, getBlockState()));
		TrackPropagator.onRailRemoved(level, worldPosition, getBlockState());
		for (BlockPos neighbour : new BlockPos[] { prev, next }) {
			AngledTrackAnchorBlockEntity anchor = anchorAt(neighbour);
			if (anchor != null) {
				reported.addAll(GraphPruner.reportedBy(level, neighbour, anchor.getBlockState()));
				TrackPropagator.onRailRemoved(level, neighbour, anchor.getBlockState());
			}
		}
		return reported;
	}

	/**
	 * Takes out of the graph the nodes that were where {@link #detachNeighbourhood} found the
	 * neighbourhood reporting them and are not now.
	 *
	 * <p>Without this they stay for good. The rebuild queued by {@link #attachNeighbourhood} only
	 * removes and replaces what the blocks report when it runs, so a node at a location that has
	 * stopped being reported is never reached again: it is the island Create's debug view draws as a
	 * coloured post where an anchor has been broken. Runs after the links have changed, so what the
	 * blocks report is what the graph is about to be built from, and before the rebuild is queued.
	 */
	private void pruneGraph(Set<TrackNodeLocation> stale) {
		if (level == null || level.isClientSide)
			return;
		GraphPruner.prune(level, stale);
	}

	// Deliberately no rebuild on chunk load. It looks like the obvious way to recover a graph that
	// was thrown away — by /createtrainnext resetgraph, or by a build that moved where nodes go —
	// but onRailAdded removes everything it can reach before rebuilding, and on load a neighbour's
	// chunk may not be there yet. buildCurveToNext then returns null for want of a loaded partner,
	// so the pass deletes a working edge and puts nothing back. Recovering a discarded graph has to
	// mean placing a block, where the surroundings are known to be loaded.

	/**
	 * Whether an anchor this one is linked to is in a chunk that is not loaded.
	 *
	 * <p>What the anchor reports to the graph is its curves, and a curve is built from the anchor at its
	 * other end, so with that one out of reach the anchor reports fewer curves than it has — not none that
	 * were removed, but none that can be seen. {@code onRailAdded} cannot tell the two apart. It removes the
	 * nodes this block reports and builds the graph again from what is reported, so run in that state it
	 * takes the link out and puts nothing back, and nothing is left to put it back later.
	 *
	 * <p>Links of Create's length, 32 blocks at most, are never far enough for this to happen, because the
	 * chunk of one end is the chunk next to the other's. A link of a hundred blocks is seven chunks long.
	 */
	public boolean hasUnloadedNeighbour() {
		return level != null && ((prev != null && !level.isLoaded(prev)) || (next != null && !level.isLoaded(next)));
	}

	/** Asks for this anchor's neighbourhood to be built into the graph again, {@code delay} ticks from now. */
	public void requestRebuild(int delay) {
		if (level != null && !level.isClientSide)
			level.scheduleTick(worldPosition, getBlockState().getBlock(), delay);
	}

	/**
	 * Whether a rebuild of this anchor's neighbourhood is already waiting to run. Until it has, the graph
	 * may lack the links only because they have not been put in yet.
	 */
	public boolean hasRebuildPending() {
		return level != null && !level.isClientSide && level.getBlockTicks()
			.hasScheduledTick(worldPosition, getBlockState().getBlock());
	}

	/**
	 * Whether the graph holds the curve to the anchor ahead, as an edge each way between its two ends.
	 *
	 * <p>True when there is nothing to check: no anchor ahead, or one that is not loaded, since a curve that
	 * cannot be built cannot be told apart from one that is not there.
	 */
	public boolean linkAheadIsInGraph() {
		if (level == null || level.isClientSide)
			return true;
		BezierConnection curve = buildCurveToNext();
		if (curve == null)
			return true;

		TrackNodeLocation from = new TrackNodeLocation(curve.starts.getFirst()).in(level);
		TrackNodeLocation to = new TrackNodeLocation(curve.starts.getSecond()).in(level);
		TrackGraph graph = Create.RAILWAYS.sided(level)
			.getGraph(level, from);
		TrackNode fromNode = graph == null ? null : graph.locateNode(from);
		TrackNode toNode = graph == null ? null : graph.locateNode(to);
		return fromNode != null && toNode != null && graph.getConnectionsFrom(fromNode)
			.containsKey(toNode)
			&& graph.getConnectionsFrom(toNode)
				.containsKey(fromNode);
	}

	/** Queues the rebuild, matching how Create defers propagation to a scheduled tick. */
	private void attachNeighbourhood() {
		if (level == null || level.isClientSide)
			return;
		level.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
		for (BlockPos neighbour : new BlockPos[] { prev, next }) {
			AngledTrackAnchorBlockEntity anchor = anchorAt(neighbour);
			if (anchor != null)
				level.scheduleTick(neighbour, anchor.getBlockState().getBlock(), 1);
		}
	}

	private void invalidateNeighbourhood() {
		invalidateCurve();
		markChanged();
		for (BlockPos neighbour : new BlockPos[] { prev, next }) {
			AngledTrackAnchorBlockEntity anchor = anchorAt(neighbour);
			if (anchor != null) {
				anchor.invalidateCurve();
				anchor.markChanged();
			}
		}
	}

	private void invalidateCurve() {
		cachedCurve = null;
		cachedKey = Long.MIN_VALUE;
	}

	// ---------------------------------------------------------------- curve

	/**
	 * Converts a Minecraft-style yaw into a horizontal unit vector.
	 * Yaw 0 points south (+Z) and increases clockwise, matching entity rotation.
	 */
	public static Vec3 directionFromYaw(float yawDegrees) {
		double radians = Math.toRadians(yawDegrees);
		return new Vec3(-Math.sin(radians), 0, Math.cos(radians));
	}

	/** The inverse of {@link #directionFromYaw}, so a heading can be stored as one. */
	public static float yawOf(Vec3 heading) {
		return (float) Math.toDegrees(Math.atan2(-heading.x, heading.z));
	}

	/** Which way the route runs through this anchor. */
	public Vec3 heading() {
		return directionFromYaw(yaw);
	}

	/**
	 * The direction station and signal placement compares against, which is not the heading.
	 *
	 * <p>{@code TrackGraphHelper.getGraphLocationAt} matches the axis a track block reports against
	 * {@code TrackEdge.getDirection}, and that is measured as the chord across the first quarter of
	 * an edge rather than the tangent at its end. On a curve the two differ by a degree or two,
	 * and the comparison allows about 0.9, so reporting the heading has placement refused anywhere
	 * the route is actually curving. Measuring the same chord here makes the two agree by
	 * construction.
	 *
	 * <p>The vector reported by an anchor with a curve on either side is deliberately <em>short</em>.
	 * See {@link #reachFor}: its length is what buys the slack that lets a signal or a station be
	 * placed facing either way along the route rather than only one.
	 *
	 * <p>Its sign is held where the anchor's surroundings change — the route turned round, an end
	 * joined to something, a neighbour unlinked — whatever the curves say: see {@link #flipped} for
	 * why.
	 */
	public Vec3 targetingAxis() {
		Vec3 axis = naturalAxis();
		return flipped ? axis.scale(-1) : axis;
	}

	/**
	 * What {@link #targetingAxis} reports before {@link #flipped} is applied: the block's own axis
	 * for an end, and the one measured along the route for a node at the block's centre.
	 */
	private Vec3 naturalAxis() {
		// An end resolves through the helper's second case, which compares against the direction of
		// this block's own linear span rather than any edge — and that span lies on the discrete
		// axis. Reporting the heading there had it refused at every angle where the two differ,
		// which is why only multiples of 45 worked. Both directions already work there, because the
		// span runs both ways out of the block's centre and each way matches the axis exactly.
		if (!usesCentreNode(heading(), isInterior(), slope != 0))
			return AngledTrackAnchorBlock.axisVector(getBlockState());

		return routeTargetingAxis();
	}

	/**
	 * What {@link #naturalAxis} gives for a node at the block's centre: measured off the curves
	 * either side, so it points the way the route runs.
	 */
	private Vec3 routeTargetingAxis() {
		// The two directions leaving this node, measured the way Create measures them.
		Vec3 forwards = chordOf(buildCurveToNext());
		BezierConnection behind = buildCurveFromPrev();
		Vec3 backwards = behind == null ? null : chordOf(behind.secondary());

		if (forwards == null && backwards == null)
			return heading();
		if (backwards == null)
			return forwards;
		if (forwards == null)
			return backwards.scale(-1);

		// These two ought to be exact opposites and are not. The tangents are — a route runs
		// through an interior anchor along one heading, so the curve ahead leaves along it and the
		// curve behind arrives along it — but getDirection measures a chord across a quarter of an
		// <em>entire</em> curve, and on a long one that lands 18 or 26 degrees off the tangent it
		// belongs to. Two curves of different lengths therefore disagree about which way the route
		// runs here by as much as 45 degrees.
		//
		// Matching one of them exactly is what the earlier version did, and it is exactly why a
		// signal could only be placed facing one way: the comparison is against the reported axis
		// negated, so which of the two edges a placement runs against depends on which way the
		// player faces, and only the matched one was ever inside the tolerance. Reporting both as
		// two axes is not an option either — more than one is read as a junction and refused.
		Vec3 bisector = forwards.subtract(backwards);
		if (bisector.lengthSqr() < 1.0E-6)
			return heading();
		bisector = bisector.normalize();

		// So: split the difference, then widen the tolerance until the difference fits inside it.
		double worst = Math.max(angleBetween(bisector, forwards), angleBetween(bisector.scale(-1), backwards));
		return bisector.scale(reachFor(worst));
	}

	/**
	 * How long the reported axis has to be for Create to accept an edge {@code deviation} radians
	 * away from it.
	 *
	 * <p>The check in {@code getGraphLocationAt} is
	 * {@code direction.scale(length).distanceToSqr(axis.scale(-1)) > 1/4096}, where {@code length}
	 * is the reported axis's own length and {@code direction} is a unit vector. Both sides of that
	 * comparison are therefore vectors of the same length L, so the squared distance between them
	 * is {@code 2L^2 (1 - cos t)} and the whole test reduces to an angle:
	 *
	 * <pre>2 L^2 (1 - cos t) &lt;= 1/4096   &lt;=&gt;   L &lt;= sqrt(1 / (8192 (1 - cos t)))</pre>
	 *
	 * <p>The tolerance is written as an absolute distance, so <em>the length of the axis is what
	 * decides how much angle it forgives</em>. A unit axis — which is what Create's own orthogonal
	 * track reports — allows about 0.9 degrees, and that is right for a block whose edges are
	 * straight and whose direction is therefore exact. An anchor mid-curve has no such exactness to
	 * offer, so it reports a shorter axis and asks for the slack it actually needs.
	 *
	 * <p>Bounded at both ends. Never longer than a unit vector, so this can only loosen the
	 * comparison, never tighten it past what Create already allows. And never loose enough to reach
	 * the <em>other</em> edge at the node, which sits close to 180 degrees away: past
	 * {@link #MAX_TARGETING_SLACK} a node this bent is better refused than resolved to whichever of
	 * its two edges happened to be iterated first.
	 */
	private static double reachFor(double deviation) {
		double allowed = Math.min(MAX_TARGETING_SLACK, deviation + TARGETING_MARGIN);
		double spread = 1 - Math.cos(allowed);
		return spread < 1.0E-9 ? 1 : Math.min(1, Math.sqrt(1 / (8192 * spread)));
	}

	/** The angle between two unit vectors, in radians. */
	private static double angleBetween(Vec3 a, Vec3 b) {
		return Math.acos(Math.max(-1, Math.min(1, a.dot(b))));
	}

	/**
	 * The direction {@code TrackEdge.getDirection} would report for this curve at its start: the
	 * chord across its first quarter, not the tangent.
	 */
	@Nullable
	private static Vec3 chordOf(@Nullable BezierConnection curve) {
		if (curve == null)
			return null;
		Vec3 chord = curve.getPosition(0.25)
			.subtract(curve.getPosition(0));
		return chord.lengthSqr() < 1.0E-6 ? null : chord.normalize();
	}

	/** An axis vector turned to point the way the route is going. */
	private static Vec3 forward(AnchorAxis candidate, Vec3 heading) {
		Vec3 axis = candidate.axis();
		return axis.dot(heading) < 0 ? axis.scale(-0.5) : axis.scale(0.5);
	}

	/**
	 * Half a step along this anchor's axis, pointing the way the route is going. Both of an end
	 * anchor's nodes are this far either side of the block's centre.
	 *
	 * <p>Both offsets deliberately use the same axis, which is what puts the nodes exactly where
	 * Create's own track puts its own — {@code blockCentre ± axis * 0.5} — so an adjacent piece of
	 * track reports the same node and the two merge.
	 *
	 * <p>An earlier version chose the two independently to bring the line between them closer to
	 * the heading. That mattered when every anchor had two nodes, but interior anchors collapse to
	 * a single centre now, so it only ever applied to the ends — where docking matters more than
	 * the last few degrees. It also broke docking outright at 45: an orthogonal entry paired with
	 * an orthogonal exit scores exactly as well there as a diagonal pair, and won on iteration
	 * order, putting the nodes on edge midpoints where Create's diagonals never have any.
	 */
	private Vec3 forwardOffset() {
		return forwardOffset(heading());
	}

	/**
	 * Half a step along the lattice-safe axis nearest {@code heading}, pointing the way the route
	 * is going.
	 *
	 * <p>Derived from the heading rather than read off {@code AXIS}, even though
	 * {@link #alignAxisToYaw} keeps the two in step. The point is that the curve wand has to work
	 * out where a node <em>would</em> land for an anchor that does not exist yet, or that is about
	 * to be turned, and it can only do that from a heading. Sharing one function with the live
	 * anchor is what makes the preview the same geometry as the result rather than a lookalike.
	 */
	public static Vec3 forwardOffset(Vec3 heading) {
		return forward(AnchorAxis.nearestTo(heading), heading);
	}

	/**
	 * Whether an anchor's two nodes collapse onto its block's centre, leaving it no span.
	 *
	 * <p>True in the middle of a chain, which is what {@code interior} says, and true of any anchor
	 * whose heading does not lie along one of the four lattice-safe axes — which is what stops a
	 * route being visibly forced onto 45 degrees at its ends.
	 *
	 * <p>The reasoning is the same in both cases. A span exists to carry the route across the
	 * anchor's own block, and its two ends have to be offsets from the centre that land back on the
	 * half-block lattice. Only the four axes do, so a span can only ever run along one of those
	 * four — and a route running at 30 degrees that is made to pass through a 45 degree span has a
	 * 45 degree piece of track sitting in the middle of it. A block's centre is on the lattice too,
	 * and unlike the boundary it is on the lattice from every direction, so an anchor with no span
	 * has no angle imposed on it at all.
	 *
	 * <p>Which leaves the question of what the span was for. It was for handing the route over to
	 * Create's own track, which runs on those same four axes — and an anchor doing that is aimed
	 * along the axis by the wand, so its heading is one of the four and it keeps its span. An
	 * anchor pointing anywhere else has nothing to hand over to: no piece of Create track could
	 * meet it squarely whatever it did. The bend the span used to carry, from the heading round to
	 * the nearest axis, was never reconciling anything.
	 */
	public static boolean usesCentreNode(Vec3 heading, boolean interior) {
		return usesCentreNode(heading, interior, false);
	}

	/**
	 * The same, for an anchor that may be climbing. One that is has its node at the block's centre
	 * whatever its heading: a span lying along an axis at the block's own height cannot continue a
	 * climb, and the curve leaving its end would bend away from it by the whole of the slope.
	 */
	public static boolean usesCentreNode(Vec3 heading, boolean interior, boolean sloped) {
		if (interior || sloped)
			return true;
		if (heading.lengthSqr() < 1.0E-8)
			return true;
		Vec3 axis = AnchorAxis.nearestTo(heading)
			.axis()
			.normalize();
		return Math.abs(heading.normalize()
			.dot(axis)) <= 1 - 1.0E-6;
	}

	/** Where {@link #entryNode} would fall for an anchor at {@code pos} pointed along {@code heading}. */
	public static Vec3 entryNode(BlockPos pos, Vec3 heading, boolean interior) {
		return entryNode(pos, heading, interior, false);
	}

	public static Vec3 entryNode(BlockPos pos, Vec3 heading, boolean interior, boolean sloped) {
		Vec3 centre = Vec3.atBottomCenterOf(pos);
		return usesCentreNode(heading, interior, sloped) ? centre : centre.subtract(forwardOffset(heading));
	}

	/** Where {@link #exitNode} would fall for an anchor at {@code pos} pointed along {@code heading}. */
	public static Vec3 exitNode(BlockPos pos, Vec3 heading, boolean interior) {
		return exitNode(pos, heading, interior, false);
	}

	public static Vec3 exitNode(BlockPos pos, Vec3 heading, boolean interior, boolean sloped) {
		Vec3 centre = Vec3.atBottomCenterOf(pos);
		return usesCentreNode(heading, interior, sloped) ? centre : centre.add(forwardOffset(heading));
	}

	/**
	 * The node a curve from {@link #prev} arrives at: the block's centre when there is one.
	 *
	 * <p>A centre is on the half-block lattice whatever the heading, so putting the node there
	 * costs nothing and removes the constraint that made routes rough. Where an anchor's nodes may
	 * sit is quantised; two of them meant the route had to pass through both, and the line between
	 * them could not follow the heading, so it swung out and back across every anchor. One node,
	 * and the curves either side simply meet there with opposite tangents.
	 *
	 * <p>An open side keeps its boundary node instead, because that is where Create's own track
	 * puts its nodes and sharing one is what lets the two dock. An end docked onto a slope has the one
	 * node, on the slope's end: see {@link #dockNode}.
	 */
	public Vec3 entryNode() {
		Vec3 dock = dockNode();
		return dock != null ? dock : entryNode(worldPosition, heading(), isInterior(), slope != 0);
	}

	/** The node the curve to {@link #next} leaves from. The block's centre when interior. */
	public Vec3 exitNode() {
		Vec3 dock = dockNode();
		return dock != null ? dock : exitNode(worldPosition, heading(), isInterior(), slope != 0);
	}

	/**
	 * The one node of an end docked onto a slope, or null for any other anchor: see {@link #slopeDock}.
	 *
	 * <p>The free side is the one the slope is on. With nothing behind, the route leaves the slope and
	 * the slope is behind; with nothing ahead, the route arrives at the slope and it is ahead. Turning the
	 * route round swaps the two and turns the heading round as well, so the node does not move.
	 */
	@Nullable
	private Vec3 dockNode() {
		return isSlopeDocked() ? slopeDockNode(worldPosition, heading(), prev == null) : null;
	}

	/**
	 * The normal of the rails at this anchor's node: the slope's for an end docked onto one, and straight up
	 * for every other.
	 *
	 * <p>The node is shared with the slope, and Create gives a straight edge the normal of its first node and
	 * puts a carriage's anchor a block along it. A node keeps the normal of whatever created it first, which
	 * is the anchor or the slope depending on which of them the graph was last rebuilt from, so the two have
	 * to say the same or the slope's edge is a few tenths of a block off the rail in one direction. Create's
	 * own curve between a slope and flat track does it the same way: the slope's normal at that end, straight
	 * up at the other, turning between them.
	 */
	public Vec3 nodeNormal() {
		return isSlopeDocked() ? slopeNormal(heading(), slope) : UP;
	}

	/** The normals of the two points {@link #seedPair} gives, in the same order. */
	public Couple<Vec3> seedNormals() {
		return isSlopeDocked() ? Couple.create(nodeNormal(), UP) : Couple.create(UP, UP);
	}

	/**
	 * Where the node of an anchor at {@code pos} docked onto a slope is: on the boundary of its block that
	 * faces the slope, at its floor. Shared with the planner, which has to put the same point under a
	 * curve to an anchor that does not exist yet.
	 *
	 * @param heading     which way the route runs through the anchor; it lies along one of the four axes,
	 *                    since a slope does.
	 * @param trackBehind whether the slope is behind the anchor, against its heading.
	 */
	public static Vec3 slopeDockNode(BlockPos pos, Vec3 heading, boolean trackBehind) {
		Vec3 centre = Vec3.atBottomCenterOf(pos);
		Vec3 offset = forwardOffset(heading);
		return trackBehind ? centre.subtract(offset) : centre.add(offset);
	}

	/**
	 * Whether this anchor has a chain running through it, rather than ending at it.
	 *
	 * <p>Only an interior anchor collapses to a single centre node. An end keeps both boundary
	 * nodes, and the reason is Create's track rendering rather than the graph: it cuts a curve
	 * into {@code (int) (length * 2)} segments and then draws each rail piece 2.2 times the
	 * segment's length. Half a block of span comes out as one segment with rails drawn well past
	 * the ends of it, splayed around a normal sampled from almost nothing. A full block behaves.
	 *
	 * <p>An end is also where the route is handing over to Create's discrete axes anyway, so the
	 * small amount of roughness that two boundary nodes reintroduce belongs there if anywhere.
	 */
	private boolean isInterior() {
		return prev != null && next != null;
	}

	/**
	 * Two distinct lattice points for {@code TrackPropagator}'s linear-only seeding query, which
	 * dereferences the start node it expects that to have found. An anchor in the middle of a chain
	 * has only its centre, so pair it with a boundary the real graph never uses: seeding stops at
	 * the first location it can make a node of, and reporting the centre first means that is the
	 * centre.
	 *
	 * <p>An end docked onto a slope has only the slope's end, and is paired with its own centre, which
	 * nothing else reports while it is docked. The slope's end is always where seeding stops, since its
	 * curve leaves from it.
	 */
	public Couple<Vec3> seedPair() {
		Vec3 dock = dockNode();
		if (dock != null)
			return Couple.create(dock, Vec3.atBottomCenterOf(worldPosition));
		Vec3 entry = entryNode();
		Vec3 exit = exitNode();
		if (entry.distanceToSqr(exit) > 1.0E-6)
			return Couple.create(entry, exit);
		return Couple.create(entry, entry.add(forwardOffset()));
	}

	/**
	 * The curve from this anchor to the one ahead of it, or null if there is none.
	 *
	 * <p>Each curve is owned by the anchor behind it, so it is described once and both ends agree
	 * without having to reconcile anything.
	 */
	@Nullable
	public BezierConnection buildCurveToNext() {
		AngledTrackAnchorBlockEntity ahead = anchorAt(next);
		if (ahead == null)
			return null;

		Vec3 start = exitNode();
		Vec3 end = ahead.entryNode();
		if (end.subtract(start)
			.lengthSqr() < 1.0E-4)
			return null;

		return new BezierConnection(Couple.create(worldPosition, next), Couple.create(start, end),
			// Both tangents point into the curve: ours forwards along the route, the far end's
			// backwards along it. Create's determineHandles negates the second before measuring
			// the turn, so a tangent pointing out of the curve there puts a cusp in the bezier.
			// Each carries its anchor's climb, which Create reads only for where the handles point.
			Couple.create(tangent(), ahead.tangent()
				.scale(-1)),
			// Straight up, except where an end is docked onto a slope, which the rails there lie on.
			Couple.create(nodeNormal(), ahead.nodeNormal()), true, false, TrackMaterial.ANDESITE);
	}

	/**
	 * The curve arriving from the anchor behind, which that anchor owns and builds.
	 *
	 * <p>Returned in its owner's orientation — running from the anchor behind into this one — which
	 * is <em>not</em> the orientation this anchor needs when it reports the curve to the graph.
	 * {@link AngledTrackAnchorBlock#getConnected} turns it around; see the note there on why the
	 * direction a bezier is reported in decides whether a train can cross the node.
	 *
	 * <p>Reported here as well so the graph can be walked backwards. {@code walkConnectedTracks}
	 * asks the blocks around a node, and a node at a block's centre reaches only its own block, so
	 * an anchor that reported nothing but the curve ahead of it could be traversed in one direction
	 * only. {@code onRailAdded} removes everything it can reach before rebuilding, and rebuilding
	 * is a forward walk, so a rebuild triggered anywhere along a chain deleted the curve behind it
	 * and never put it back — leaving anchors with one edge, or none, depending on whose tick ran
	 * last. Create sidesteps this by storing each bezier on both of its ends.
	 */
	@Nullable
	public BezierConnection buildCurveFromPrev() {
		AngledTrackAnchorBlockEntity behind = anchorAt(prev);
		return behind == null ? null : behind.buildCurveToNext();
	}

	/**
	 * The span across the anchor's own block, from {@link #entryNode} to {@link #exitNode}, or null
	 * where the anchor has no span at all.
	 *
	 * <p>Only an anchor whose heading lies along one of the four lattice-safe axes has one, which
	 * means only one that is handing the route over to Create's own track — see
	 * {@link #usesCentreNode}. So a span is always a straight line along the heading, and the
	 * tangents below are the heading either way round rather than anything reconciling.
	 *
	 * <p>It used to be more than that. Every end of a chain kept a span, and the outward tangent
	 * followed the block's nearest discrete axis whatever the heading was, on the reasoning that
	 * turning across a block spreads a direction change over an edge rather than concentrating it
	 * at a node — which matters, because {@code TrackEdge.canTravelTo} refuses a node where the
	 * direction changes by more than about 29 degrees. What that missed is that an end pointing
	 * between the axes has nothing on the far side to change direction <em>to</em>. There was no
	 * handover to spread; there was just a piece of track lying at up to 22.5 degrees off the route
	 * it belonged to, plainly visible at the end of every line that did not happen to finish on a
	 * multiple of 45.
	 */
	@Nullable
	public BezierConnection buildSpan() {
		Vec3 entry = entryNode();
		Vec3 exit = exitNode();

		// Nothing to span wherever both nodes are the block's centre, which is the middle of a
		// chain and every end that is not squared up to Create's track. The curves either side
		// meet at the centre directly, at exactly the angle the route runs.
		if (entry.distanceToSqr(exit) < 1.0E-6)
			return null;

		Vec3 along = exit.subtract(entry)
			.normalize();

		Vec3 up = new Vec3(0, 1, 0);

		return new BezierConnection(Couple.create(worldPosition, worldPosition), Couple.create(entry, exit),
			Couple.create(along, along.scale(-1)), Couple.create(up, up), true, false, TrackMaterial.ANDESITE);
	}

	/** Client-side accessor for the curve ahead. Caches until something it depends on moves. */
	@Nullable
	public BezierConnection getCurveForRender() {
		if (next == null) {
			cachedCurve = null;
			return null;
		}
		long key = renderKey();
		if (cachedCurve == null || key != cachedKey) {
			cachedCurve = buildCurveToNext();
			cachedKey = key;
		}
		return cachedCurve;
	}

	private long renderKey() {
		AngledTrackAnchorBlockEntity ahead = anchorAt(next);
		long key = next == null ? 0L : next.asLong();
		key = key * 31 + Float.floatToIntBits(yaw);
		key = key * 31 + Float.floatToIntBits(ahead == null ? 0.0F : ahead.yaw);
		key = key * 31 + Float.floatToIntBits(slope);
		key = key * 31 + Float.floatToIntBits(ahead == null ? 0.0F : ahead.slope);
		return key;
	}

	public AABB computeRenderBoundingBox() {
		// Always include the block itself: the span is drawn here even with no curve to show.
		AABB bounds = new AABB(worldPosition);
		BezierConnection curve = getCurveForRender();
		// The curve's own bounds, sampled along the actual bezier and already padded by Create.
		// More precise than boxing the two anchors, which would miss how far a curve bulges out.
		return curve == null ? bounds : bounds.minmax(curve.getBounds());
	}

	// --------------------------------------------------------------- index

	@Override
	public void onLoad() {
		super.onLoad();
		if (level != null)
			AnchorIndex.add(level, this);
	}

	/**
	 * Called whenever this stops being a live block entity, whether it was broken or its chunk went.
	 * {@code setRemoved} is final here, so this is where the index is told.
	 */
	@Override
	public void invalidate() {
		if (level == null)
			return;
		super.invalidate();
		AnchorIndex.remove(level, this);
	}

	// ----------------------------------------------------------- occupancy

	/**
	 * What holds someone up in this anchor's own block.
	 *
	 * <p>Create's two pixel slab across the whole block, as its own track has. Where the route climbs at the
	 * anchor that is wrong: at 45 degrees the rails cross the block a whole block higher at one side than the
	 * other, and may not cross it at all, but run under its floor, as they do into the foot of a slope, where
	 * a slab would be a ceiling over them. So a climbing anchor holds up only the floor of the curves through
	 * its block, the same quarter-block columns {@link CurveOccupancy} would lay if the block were empty, and
	 * the floor on either side, {@link CurveFillerBlock}'s, carries on from it. Both sides work it out alike,
	 * the client for where someone stands and the server for where they are, and work it out again only when
	 * one of the curves is not the one it was worked out from, which an anchor's cached curve makes a cheap
	 * thing to ask. With a curve that cannot be built, its far anchor not loaded, it is the slab.
	 */
	public VoxelShape collisionShape() {
		if (slope == 0)
			return AllShapes.TRACK_COLLISION;

		AngledTrackAnchorBlockEntity before = anchorAt(prev);
		BezierConnection in = before == null ? null : before.buildCurveToNext();
		BezierConnection out = next == null ? null : buildCurveToNext();
		if (climbingFloor != null && in == floorFromPrev && out == floorFromNext)
			return climbingFloor;

		byte[] floor = null;
		for (BezierConnection curve : new BezierConnection[] { in, out }) {
			if (curve == null)
				continue;
			byte[] here = CurveOccupancy.rasterise(curve)
				.get(worldPosition);
			if (here == null)
				continue;
			if (floor == null)
				floor = here;
			else
				for (int i = 0; i < floor.length; i++)
					if (here[i] != CurveOccupancy.EMPTY && (floor[i] == CurveOccupancy.EMPTY || here[i] < floor[i]))
						floor[i] = here[i];
		}
		boolean known = (prev == null || in != null) && (next == null || out != null);
		climbingFloor = floor != null ? CurveOccupancy.shapeOf(floor) : known ? Shapes.empty() : AllShapes.TRACK_COLLISION;
		floorFromPrev = in;
		floorFromNext = out;
		return climbingFloor;
	}

	/**
	 * Counts down to the next renewal of this anchor's floor.
	 *
	 * <p>The reason an anchor ticks, with looking for a slope it is docked onto having gone. See
	 * {@link OCCUPANCY_REFRESH_TICKS}.
	 */
	public void serverTick() {
		undockIfSlopeGone();
		if (--occupancyTimer > 0)
			return;
		occupancyTimer = OCCUPANCY_REFRESH_TICKS;
		updateOccupancy();
		// What puts connections on an anchor from a save that predates them, and mends any that a
		// neighbour's chunk coming back late left out of date.
		connectionsStale = true;
		if (refreshConnections())
			sendUpdate();
	}

	/**
	 * Brings the floor under this anchor's curve up to date with where the curve now is.
	 *
	 * <p>Called from the scheduled tick the block already uses to rebuild the graph, so every path
	 * that moves a curve refreshes its floor without having to remember to. Clearing runs before
	 * placing, so a curve that shifted by a little does not take up the floor it has just put down.
	 */
	public void updateOccupancy() {
		if (level == null || level.isClientSide)
			return;

		// With the anchor ahead out of reach there is no curve to build, which is not the same as there
		// being none: the link is still there and so is its floor. Clearing it here took the floor from
		// under a long link every time its far end was out of the loaded chunks. The floor is kept alive
		// as it was last laid, in the chunks that are here, since a floor nobody renews times out.
		if (next != null && !level.isLoaded(next)) {
			if (occupied != null)
				CurveOccupancy.place(level, occupied);
			return;
		}

		BezierConnection current = buildCurveToNext();
		if (occupied != null && (current == null || !occupied.equalsSansMaterial(current)))
			CurveOccupancy.clear(level, occupied);

		occupied = current;
		if (current != null)
			CurveOccupancy.place(level, current);
	}

	/**
	 * Takes up the floor entirely, for an anchor that is about to stop existing.
	 *
	 * <p>Falls back to rebuilding the curve when nothing has been recorded, which is the case for
	 * an anchor broken in the same session it was loaded in but before its first refresh.
	 */
	public void clearOccupancy() {
		if (level == null || level.isClientSide)
			return;

		BezierConnection current = occupied != null ? occupied : buildCurveToNext();
		if (current != null)
			CurveOccupancy.clear(level, current);
		occupied = null;
	}

	// ----------------------------------------------------------------- nbt

	/**
	 * Saved and sent through {@code write} and {@code read} rather than the usual overrides, because
	 * {@code SmartBlockEntity} owns those and routes both the save and the update packet through
	 * these. The keys are the ones the anchor has always used, so a save made before it was a
	 * {@code TrackBlockEntity} reads exactly as it did; Create adds its own {@code Connections}
	 * alongside.
	 */
	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		// What is saved, and what the client is sent, is the connections as the links have them now.
		refreshConnections();
		super.write(tag, registries, clientPacket);
		tag.putFloat(TAG_YAW, yaw);
		if (slope != 0)
			tag.putFloat(TAG_SLOPE, slope);
		if (flipped)
			tag.putBoolean(TAG_AXIS_FLIPPED, true);
		if (slopeDock)
			tag.putBoolean(TAG_SLOPE_DOCK, true);
		if (prev != null)
			tag.putLong(TAG_PREV, prev.asLong());
		if (next != null)
			tag.putLong(TAG_NEXT, next.asLong());
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		yaw = tag.getFloat(TAG_YAW);
		slope = tag.getFloat(TAG_SLOPE);
		prev = tag.contains(TAG_PREV) ? BlockPos.of(tag.getLong(TAG_PREV)) : null;
		next = tag.contains(TAG_NEXT) ? BlockPos.of(tag.getLong(TAG_NEXT)) : null;
		flipped = tag.getBoolean(TAG_AXIS_FLIPPED);
		slopeDock = tag.getBoolean(TAG_SLOPE_DOCK);
		invalidateCurve();
	}
}
