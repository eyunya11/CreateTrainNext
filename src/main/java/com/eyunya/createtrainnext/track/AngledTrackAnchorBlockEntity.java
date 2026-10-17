package com.eyunya.createtrainnext.track;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNRegistry;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackPropagator;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
 */
public class AngledTrackAnchorBlockEntity extends BlockEntity {

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

	private static final String TAG_YAW = "Yaw";
	private static final String TAG_PREV = "Prev";
	private static final String TAG_NEXT = "Next";
	/** What earlier versions called the flag, when it only meant anything at the centre of a block. */
	private static final String TAG_FLIPPED = "Flipped";
	private static final String TAG_AXIS_FLIPPED = "AxisFlipped";

	private float yaw = 0.0F;

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

	@Nullable
	private BezierConnection cachedCurve = null;
	private long cachedKey = Long.MIN_VALUE;

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

	/** See {@link #flipped}. */
	public boolean isFlipped() {
		return flipped;
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
	 */
	public boolean hasFreeSide() {
		return prev == null || next == null;
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
	 * @param thisHeading  where this anchor should point afterwards, or null to leave it.
	 * @param otherHeading where {@code other} should point afterwards, or null to leave it.
	 */
	public void linkTo(AngledTrackAnchorBlockEntity other, @Nullable Vec3 thisHeading,
		@Nullable Vec3 otherHeading) {
		if (other == this)
			return;

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
			alignAxisToYaw();
		}
		if (otherHeading != null) {
			other.yaw = yawOf(otherHeading);
			other.alignAxisToYaw();
		}

		next = other.getBlockPos();
		other.prev = worldPosition;

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

		Set<TrackNodeLocation> stale = detachNeighbourhood();
		clearNextOfThis();
		clearPrevOfThis();
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
		setChanged();
		if (level != null && !level.isClientSide)
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
	}

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
		if (!usesCentreNode(heading(), isInterior()))
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
		if (interior)
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
		Vec3 centre = Vec3.atBottomCenterOf(pos);
		return usesCentreNode(heading, interior) ? centre : centre.subtract(forwardOffset(heading));
	}

	/** Where {@link #exitNode} would fall for an anchor at {@code pos} pointed along {@code heading}. */
	public static Vec3 exitNode(BlockPos pos, Vec3 heading, boolean interior) {
		Vec3 centre = Vec3.atBottomCenterOf(pos);
		return usesCentreNode(heading, interior) ? centre : centre.add(forwardOffset(heading));
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
	 * puts its nodes and sharing one is what lets the two dock.
	 */
	public Vec3 entryNode() {
		return entryNode(worldPosition, heading(), isInterior());
	}

	/** The node the curve to {@link #next} leaves from. The block's centre when interior. */
	public Vec3 exitNode() {
		return exitNode(worldPosition, heading(), isInterior());
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
	 */
	public Couple<Vec3> seedPair() {
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

		Vec3 up = new Vec3(0, 1, 0);

		return new BezierConnection(Couple.create(worldPosition, next), Couple.create(start, end),
			// Both tangents point into the curve: ours forwards along the route, the far end's
			// backwards along it. Create's determineHandles negates the second before measuring
			// the turn, so a tangent pointing out of the curve there puts a cusp in the bezier.
			Couple.create(heading(), ahead.heading()
				.scale(-1)),
			Couple.create(up, up), true, false, TrackMaterial.ANDESITE);
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

	@Override
	public void setRemoved() {
		super.setRemoved();
		if (level != null)
			AnchorIndex.remove(level, this);
	}

	// ----------------------------------------------------------- occupancy

	/**
	 * Counts down to the next renewal of this anchor's floor.
	 *
	 * <p>The only reason an anchor ticks at all. See {@link OCCUPANCY_REFRESH_TICKS}.
	 */
	public void serverTick() {
		if (--occupancyTimer > 0)
			return;
		occupancyTimer = OCCUPANCY_REFRESH_TICKS;
		updateOccupancy();
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

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putFloat(TAG_YAW, yaw);
		if (flipped)
			tag.putBoolean(TAG_AXIS_FLIPPED, true);
		if (prev != null)
			tag.putLong(TAG_PREV, prev.asLong());
		if (next != null)
			tag.putLong(TAG_NEXT, next.asLong());
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		yaw = tag.getFloat(TAG_YAW);
		prev = tag.contains(TAG_PREV) ? BlockPos.of(tag.getLong(TAG_PREV)) : null;
		next = tag.contains(TAG_NEXT) ? BlockPos.of(tag.getLong(TAG_NEXT)) : null;

		// Earlier versions wrote this as Flipped, and it meant something only at the centre of a
		// block: an end ignored it. It applies to an end now, so a value from before is read the way
		// it was then — kept at a centre, dropped on an end — or an end it happened to be set on
		// would start reporting the opposite of what it always had.
		flipped = tag.contains(TAG_AXIS_FLIPPED) ? tag.getBoolean(TAG_AXIS_FLIPPED)
			: tag.getBoolean(TAG_FLIPPED) && usesCentreNode(heading(), isInterior());
		invalidateCurve();
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	@Nullable
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
