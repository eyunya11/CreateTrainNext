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

	public AngledTrackAnchorBlockEntity(BlockPos pos, BlockState state) {
		super(CTNRegistry.ANGLED_TRACK_ANCHOR_ENTITY.get(), pos, state);
		// Start the yaw already agreeing with the axis the block was placed on, so a fresh anchor
		// is straight rather than bent until it is first turned.
		yaw = state.getValue(AngledTrackAnchorBlock.AXIS)
			.defaultYaw();
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

	public void rotateYaw(float degrees) {
		detachNeighbourhood();
		yaw = Mth.wrapDegrees(yaw + degrees);
		alignAxisToYaw();
		invalidateNeighbourhood();
		attachNeighbourhood();
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

	/** Continues the route from this anchor to {@code other}. */
	public void linkTo(AngledTrackAnchorBlockEntity other) {
		if (other == this)
			return;

		detachNeighbourhood();
		other.detachNeighbourhood();

		// Whatever used to follow this anchor, or lead into the other, loses its half of the link.
		clearNextOfThis();
		other.clearPrevOfThis();

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

	/** Which way the route runs through this anchor. */
	public Vec3 heading() {
		return directionFromYaw(yaw);
	}

	/** An axis vector turned to point the way the route is going. */
	private static Vec3 forward(AnchorAxis candidate, Vec3 heading) {
		Vec3 axis = candidate.axis();
		return axis.dot(heading) < 0 ? axis.scale(-0.5) : axis.scale(0.5);
	}

	/**
	 * Where this anchor's two nodes sit, relative to the block's bottom centre: the entry is that
	 * much behind it, the exit that much ahead.
	 *
	 * <p>The two are chosen independently, and that is the point. Both must land on the half-block
	 * lattice, so each is half a step along one of {@link AnchorAxis}'s four directions — but
	 * nothing says it has to be the <em>same</em> direction for both. Using one axis for both puts
	 * the line between them on that axis, up to 22.5 degrees off the heading, and the route has to
	 * swing out and back across every single anchor to reach it. Mixing them lets the line between
	 * the nodes sit much closer to the heading: at 30 degrees an orthogonal entry with a diagonal
	 * exit gives 26.57, where either axis alone gives 0 or 45.
	 */
	private Couple<Vec3> nodeOffsets() {
		Vec3 heading = heading();

		Vec3 bestEntry = forward(AnchorAxis.Z_ORTHO, heading);
		Vec3 bestExit = bestEntry;
		double bestScore = -Double.MAX_VALUE;

		for (AnchorAxis entryAxis : AnchorAxis.values()) {
			Vec3 entry = forward(entryAxis, heading);
			for (AnchorAxis exitAxis : AnchorAxis.values()) {
				Vec3 exit = forward(exitAxis, heading);

				Vec3 between = entry.add(exit);
				if (between.lengthSqr() < 1.0E-6)
					continue;

				double score = between.normalize()
					.dot(heading);
				if (score <= bestScore)
					continue;

				bestScore = score;
				bestEntry = entry;
				bestExit = exit;
			}
		}

		return Couple.create(bestEntry, bestExit);
	}

	/** The node a curve from {@link #prev} arrives at. */
	public Vec3 entryNode() {
		return Vec3.atBottomCenterOf(worldPosition)
			.subtract(nodeOffsets().getFirst());
	}

	/** The node the curve to {@link #next} leaves from. */
	public Vec3 exitNode() {
		return Vec3.atBottomCenterOf(worldPosition)
			.add(nodeOffsets().getSecond());
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
