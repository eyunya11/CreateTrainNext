package com.eyunya.createtrainnext.track;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNRegistry;
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

	private static final String TAG_YAW = "Yaw";
	private static final String TAG_PREV = "Prev";
	private static final String TAG_NEXT = "Next";

	private float yaw = 0.0F;

	/** The anchor this route arrives from, and the one it continues to. Either may be absent. */
	@Nullable
	private BlockPos prev = null;
	@Nullable
	private BlockPos next = null;

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

		detachNeighbourhood();
		other.detachNeighbourhood();

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
		attachNeighbourhood();
		other.attachNeighbourhood();
	}

	/** Drops both of this anchor's links, and the matching halves on its neighbours. */
	public void unlink() {
		if (!isLinked())
			return;

		detachNeighbourhood();
		clearNextOfThis();
		clearPrevOfThis();
		invalidateNeighbourhood();
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
	 */
	private void detachNeighbourhood() {
		if (level == null || level.isClientSide)
			return;
		TrackPropagator.onRailRemoved(level, worldPosition, getBlockState());
		for (BlockPos neighbour : new BlockPos[] { prev, next }) {
			AngledTrackAnchorBlockEntity anchor = anchorAt(neighbour);
			if (anchor != null)
				TrackPropagator.onRailRemoved(level, neighbour, anchor.getBlockState());
		}
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
	 */
	public Vec3 targetingAxis() {
		// An end resolves through the helper's second case, which compares against the direction of
		// this block's own linear span rather than any edge — and that span lies on the discrete
		// axis. Reporting the heading there had it refused at every angle where the two differ,
		// which is why only multiples of 45 worked.
		if (!isInterior())
			return AngledTrackAnchorBlock.axisVector(getBlockState());

		// Matched to the curve ahead exactly, which means placement works from one side only.
		//
		// The comparison is against this axis negated, so which of the two edges at the node it
		// runs against depends on which way the player faces, and reporting two axes is not an
		// option — more than one is read as a junction and refused. A bisector was tried, on the
		// assumption the two edges were nearly collinear here: the tangents are, exactly, but
		// getDirection measures the chord across a quarter of an <em>entire</em> curve, and on a
		// long one that came out 18 and 26 degrees off the tangent, the two edges 45 degrees from
		// collinear. Split between them and neither is within the 0.9 degrees allowed; matched to
		// one and that one is exact.
		//
		// The underlying limit is Create's, not this mod's: that tolerance assumes a block with a
		// straight edge, and an interior anchor is mid-curve by construction. Create places its own
		// stations and signals on straight track for the same reason.
		Vec3 ahead = chordOf(buildCurveToNext());
		if (ahead != null)
			return ahead;

		BezierConnection behind = buildCurveFromPrev();
		Vec3 back = behind == null ? null : chordOf(behind.secondary());
		return back == null ? heading() : back.scale(-1);
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

	/** Where {@link #entryNode} would fall for an anchor at {@code pos} pointed along {@code heading}. */
	public static Vec3 entryNode(BlockPos pos, Vec3 heading, boolean interior) {
		Vec3 centre = Vec3.atBottomCenterOf(pos);
		return interior ? centre : centre.subtract(forwardOffset(heading));
	}

	/** Where {@link #exitNode} would fall for an anchor at {@code pos} pointed along {@code heading}. */
	public static Vec3 exitNode(BlockPos pos, Vec3 heading, boolean interior) {
		Vec3 centre = Vec3.atBottomCenterOf(pos);
		return interior ? centre : centre.add(forwardOffset(heading));
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
	 * Whether the span across this block is a straight line rather than a bend.
	 *
	 * <p>True at every anchor that docks onto Create's own track, because the wand aims those along
	 * the track's own axis, and the span's other tangent follows that axis too. False where the
	 * heading was chosen freely, since the span then has to turn from the heading to the nearest
	 * lattice-safe axis across the block.
	 *
	 * <p>Worth telling apart because a straight span should be reported to the graph as a straight
	 * edge, exactly as Create reports a stretch of its own track — see
	 * {@link AngledTrackAnchorBlock#getConnected}.
	 */
	public boolean spanIsStraight() {
		if (isInterior())
			return false;
		Vec3 along = exitNode().subtract(entryNode());
		if (along.lengthSqr() < 1.0E-6)
			return false;
		return Math.abs(heading().dot(along.normalize())) > 1 - 1.0E-6;
	}

	/**
	 * The span across the anchor's own block, from {@link #entryNode} to {@link #exitNode}.
	 *
	 * <p>In the middle of a chain both tangents are the heading, so the span carries the route
	 * straight through and the joints either side of it are smooth. At the ends of a chain the
	 * outward tangent instead follows the block's discrete axis, so Create's own track can dock
	 * against it squarely.
	 *
	 * <p>That end-of-chain bend is the whole reason the span is a curve and not a straight edge.
	 * {@code TrackEdge.canTravelTo} refuses a node where the direction changes by more than about
	 * 29 degrees, measured across a quarter of each edge rather than at the node itself. Turning
	 * across the block spreads that change over an edge; turning at its end concentrates it at a
	 * node, and past the threshold the track stops being passable at all.
	 */
	@Nullable
	public BezierConnection buildSpan() {
		Vec3 entry = entryNode();
		Vec3 exit = exitNode();

		// Nothing to span in the middle of a chain: both sides use the centre, and the curves
		// either side of it meet there directly. This is what removes the roughness — there is no
		// longer a stretch of route pinned between two quantised points.
		if (entry.distanceToSqr(exit) < 1.0E-6)
			return null;

		// The line the two nodes actually lie on, which an open end hands to whatever docks there.
		Vec3 axisForward = exit.subtract(entry)
			.normalize();

		// Into the span from each end. A linked side follows the heading so it stays collinear
		// with the curve there; an open side follows the axis so plain track can meet it.
		Vec3 tangentAtEntry = prev != null ? heading() : axisForward;
		Vec3 tangentAtExit = next != null ? heading().scale(-1) : axisForward.scale(-1);

		Vec3 up = new Vec3(0, 1, 0);

		return new BezierConnection(Couple.create(worldPosition, worldPosition), Couple.create(entry, exit),
			Couple.create(tangentAtEntry, tangentAtExit), Couple.create(up, up), true, false,
			TrackMaterial.ANDESITE);
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
