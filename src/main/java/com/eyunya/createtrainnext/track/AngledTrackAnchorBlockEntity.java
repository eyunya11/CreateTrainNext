package com.eyunya.createtrainnext.track;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNRegistry;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackPropagator;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * Holds one end of a free-angle curve.
 *
 * <p>The curve's endpoints sit at the ends of each anchor's straight stub, which are discrete
 * half-block positions. That is deliberate: {@code TrackNodeLocation} rounds every graph node
 * to a half-block lattice, so an endpoint anywhere else would be snapped away from the curve
 * the player actually sees, and trains would drift off the rails. Only the <em>tangents</em>
 * are free, and those the graph never quantises.
 *
 * <p>Both anchors of a pair store the link, and either can build the curve. Which end counts as
 * the start is derived from the two block positions, so the two ends always agree on one curve
 * rather than each describing its own.
 */
public class AngledTrackAnchorBlockEntity extends BlockEntity {

	private static final String TAG_YAW = "Yaw";
	private static final String TAG_PARTNER = "Partner";

	private float yaw = 0.0F;

	@Nullable
	private BlockPos partner = null;


	@Nullable
	private BezierConnection cachedCurve = null;
	private long cachedKey = Long.MIN_VALUE;

	public AngledTrackAnchorBlockEntity(BlockPos pos, BlockState state) {
		super(CTNRegistry.ANGLED_TRACK_ANCHOR_ENTITY.get(), pos, state);
		// Start the yaw already agreeing with the stub the block was placed with, so a freshly
		// placed anchor is straight rather than bent 90 degrees until it is first rotated.
		yaw = state.getValue(AngledTrackAnchorBlock.AXIS)
			.defaultYaw();
	}

	// ---------------------------------------------------------------- state

	public float getYaw() {
		return yaw;
	}

	@Nullable
	public BlockPos getPartner() {
		return partner;
	}

	public void rotateYaw(float degrees) {
		detachFromGraph();
		yaw = Mth.wrapDegrees(yaw + degrees);
		alignStubToYaw();
		invalidateCurve();
		sync();
		attachToGraph();
	}

	/**
	 * Points the straight stub at whichever of the four lattice-safe axes sits closest to the yaw.
	 *
	 * <p>The stub cannot follow the yaw exactly — it has to stay on one of those axes to keep its
	 * graph nodes on the half-block lattice. Having the diagonals available as well as the two
	 * orthogonals means it is never more than 22.5 degrees away from the curve leaving it.
	 */
	private void alignStubToYaw() {
		if (level == null || level.isClientSide)
			return;

		AnchorAxis wanted = AnchorAxis.nearestTo(directionFromYaw(yaw));

		BlockState state = getBlockState();
		if (state.getValue(AngledTrackAnchorBlock.AXIS) == wanted)
			return;

		// Same block, only a property changes, so the block entity and its link survive this.
		level.setBlock(worldPosition, state.setValue(AngledTrackAnchorBlock.AXIS, wanted), Block.UPDATE_ALL);
	}

	/** Links this anchor to {@code other}, replacing any existing links on either end. */
	public void linkTo(AngledTrackAnchorBlockEntity other) {
		if (other == this)
			return;

		unlinkPartner();
		other.unlinkPartner();

		detachFromGraph();
		other.detachFromGraph();

		this.partner = other.getBlockPos();
		other.partner = this.worldPosition;

		invalidateCurve();
		other.invalidateCurve();
		sync();
		other.sync();

		attachToGraph();
		other.attachToGraph();
	}

	/** Clears the link on this anchor and on whatever it pointed at. */
	public void unlinkPartner() {
		if (partner == null)
			return;

		AngledTrackAnchorBlockEntity other =
			level != null && level.getBlockEntity(partner) instanceof AngledTrackAnchorBlockEntity o ? o : null;

		detachFromGraph();
		if (other != null)
			other.detachFromGraph();

		partner = null;
		invalidateCurve();
		sync();

		if (other != null) {
			other.partner = null;
			other.invalidateCurve();
			other.sync();
			other.attachToGraph();
		}
		attachToGraph();
	}

	private void invalidateCurve() {
		cachedCurve = null;
		cachedKey = Long.MIN_VALUE;
	}

	private void sync() {
		setChanged();
		if (level != null && !level.isClientSide)
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
	}

	// ---------------------------------------------------------------- graph

	/**
	 * Tears this anchor's edges out of the track graph. Must run <em>before</em> the state it
	 * derives them from changes, or the propagator removes the wrong nodes and leaves orphans.
	 */
	private void detachFromGraph() {
		if (level == null || level.isClientSide)
			return;
		TrackPropagator.onRailRemoved(level, worldPosition, getBlockState());
	}

	/** Queues a graph rebuild, matching how Create defers propagation to a scheduled tick. */
	private void attachToGraph() {
		if (level == null || level.isClientSide)
			return;
		level.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
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

	/**
	 * Builds the curve between this anchor and its partner, or null if it cannot be formed.
	 * Safe to call on either side; both ends produce mirrored curves with identical endpoints.
	 */
	@Nullable
	public BezierConnection buildCurve() {
		Level level = getLevel();
		if (level == null || partner == null)
			return null;
		if (!level.isLoaded(partner))
			return null;
		if (!(level.getBlockEntity(partner) instanceof AngledTrackAnchorBlockEntity other))
			return null;

		if (Vec3.atBottomCenterOf(partner)
			.subtract(Vec3.atBottomCenterOf(worldPosition))
			.lengthSqr() < 1.0E-4)
			return null;

		// Which end is the start is derived from the two positions, not stored. It used to come
		// from a persisted 'primary' flag that the two anchors had to hold in exact opposition,
		// and any way of ending up with both set the same — old saves, a half-applied link —
		// made each end name itself the start. The tangent convention is asymmetric, so the two
		// then describe genuinely different curves rather than one curve traversed both ways:
		// both get drawn, both go into the graph, and a train picks whichever it likes. Deriving
		// the order from BlockPos cannot disagree between the two ends.
		boolean startsHere = worldPosition.asLong() < partner.asLong();
		AngledTrackAnchorBlockEntity from = startsHere ? this : other;
		AngledTrackAnchorBlockEntity to = startsHere ? other : this;

		// Create requires both tangents to point *into* the curve — see determineHandles, which
		// negates axis2 before measuring the turn angle. So the far end's tangent is the reverse
		// of its heading. Note these are fixed negations, not tests against the line joining the
		// anchors: such a test flips through 180 degrees as it crosses perpendicular, and an
		// earlier version of this method did exactly that. Feeding Create a tangent pointing out
		// of the curve puts a cusp in the bezier, where the derivative approaches zero and
		// incrementT explodes — which shows up in game as a stopped train jittering back and forth.
		Vec3 axisFrom = directionFromYaw(from.yaw);
		Vec3 axisTo = directionFromYaw(to.yaw)
			.scale(-1);

		// Endpoints: the end of each anchor's stub that its tangent faces. Discrete, so they land
		// exactly on the node lattice.
		//
		// Pinning both curves at an anchor to one shared end was tried, on the reasoning that two
		// curves meeting there carry exactly opposite tangents and would then join smoothly. They
		// do — but a curve whose tangent faces the other way has to overshoot the block and double
		// back to reach that end, and the resulting loop crosses the stub. Create reads crossing
		// curves as a junction, and trains stop routing through it. Following the tangent keeps the
		// geometry honest, at the cost of the corner this was trying to remove.
		Vec3 startFrom = Vec3.atBottomCenterOf(from.getBlockPos())
			.add(dockOffset(from.getBlockState(), axisFrom));
		Vec3 startTo = Vec3.atBottomCenterOf(to.getBlockPos())
			.add(dockOffset(to.getBlockState(), axisTo));

		Vec3 up = new Vec3(0, 1, 0);

		return new BezierConnection(
			Couple.create(from.getBlockPos(), to.getBlockPos()),
			Couple.create(startFrom, startTo),
			Couple.create(axisFrom, axisTo),
			Couple.create(up, up),
			this == from,
			false, // no girder
			TrackMaterial.ANDESITE);
	}

	/** Half a block along the anchor's stub, on whichever side the given tangent points. */
	private static Vec3 dockOffset(BlockState state, Vec3 tangent) {
		Vec3 axis = AngledTrackAnchorBlock.axisVector(state);
		if (axis.dot(tangent) < 0)
			axis = axis.scale(-1);
		return axis.scale(0.5);
	}

	/** Client-side accessor used by the renderer. Caches until an endpoint actually moves. */
	@Nullable
	public BezierConnection getCurveForRender() {
		if (partner == null) {
			cachedCurve = null;
			return null;
		}
		long key = renderKey();
		if (cachedCurve == null || key != cachedKey) {
			cachedCurve = buildCurve();
			cachedKey = key;
		}
		return cachedCurve;
	}

	private long renderKey() {
		float partnerYaw = 0.0F;
		if (level != null && partner != null
			&& level.getBlockEntity(partner) instanceof AngledTrackAnchorBlockEntity other)
			partnerYaw = other.yaw;

		long key = partner == null ? 0L : partner.asLong();
		key = key * 31 + Float.floatToIntBits(yaw);
		key = key * 31 + Float.floatToIntBits(partnerYaw);
		return key;
	}

	public AABB computeRenderBoundingBox() {
		BezierConnection curve = getCurveForRender();
		if (curve == null)
			return new AABB(worldPosition);
		// The curve's own bounds, sampled along the actual bezier and already padded by Create.
		// More precise than boxing the two anchors, which would miss how far a curve bulges out.
		return curve.getBounds();
	}

	// ----------------------------------------------------------------- nbt

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putFloat(TAG_YAW, yaw);
		if (partner != null)
			tag.putLong(TAG_PARTNER, partner.asLong());
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		yaw = tag.getFloat(TAG_YAW);
		partner = tag.contains(TAG_PARTNER) ? BlockPos.of(tag.getLong(TAG_PARTNER)) : null;
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
