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
 * Holds one end of a free-angle curve.
 *
 * <p>The curve's endpoints sit at the ends of each anchor's straight stub, which are discrete
 * half-block positions. That is deliberate: {@code TrackNodeLocation} rounds every graph node
 * to a half-block lattice, so an endpoint anywhere else would be snapped away from the curve
 * the player actually sees, and trains would drift off the rails. Only the <em>tangents</em>
 * are free, and those the graph never quantises.
 *
 * <p>Both anchors of a pair store the link. One is marked primary and draws the curve; the
 * other builds the mirrored version, so the two report identical node positions to the graph.
 */
public class AngledTrackAnchorBlockEntity extends BlockEntity {

	private static final String TAG_YAW = "Yaw";
	private static final String TAG_PARTNER = "Partner";
	private static final String TAG_PRIMARY = "Primary";

	private float yaw = 0.0F;

	@Nullable
	private BlockPos partner = null;

	/** Only the primary end renders the curve; Create's renderer skips secondary connections. */
	private boolean primary = false;

	@Nullable
	private BezierConnection cachedCurve = null;
	private long cachedKey = Long.MIN_VALUE;

	public AngledTrackAnchorBlockEntity(BlockPos pos, BlockState state) {
		super(CTNRegistry.ANGLED_TRACK_ANCHOR_ENTITY.get(), pos, state);
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
		invalidateCurve();
		sync();
		attachToGraph();
	}

	/** Links this anchor (as primary) to {@code other}, replacing any existing links on either end. */
	public void linkTo(AngledTrackAnchorBlockEntity other) {
		if (other == this)
			return;

		unlinkPartner();
		other.unlinkPartner();

		detachFromGraph();
		other.detachFromGraph();

		this.partner = other.getBlockPos();
		this.primary = true;
		other.partner = this.worldPosition;
		other.primary = false;

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
		primary = false;
		invalidateCurve();
		sync();

		if (other != null) {
			other.partner = null;
			other.primary = false;
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

		Vec3 hereCenter = Vec3.atBottomCenterOf(worldPosition);
		Vec3 thereCenter = Vec3.atBottomCenterOf(partner);
		Vec3 hereToThere = thereCenter.subtract(hereCenter);
		if (hereToThere.lengthSqr() < 1.0E-4)
			return null;

		// Endpoints: the end of each anchor's straight stub, facing the other anchor. Discrete,
		// so they land exactly on the node lattice. The flip tests are written so that the
		// partner computing this from its own side arrives at the same two points.
		Vec3 dockHere = AngledTrackAnchorBlock.axisVector(getBlockState());
		if (dockHere.dot(hereToThere) < 0)
			dockHere = dockHere.scale(-1);

		Vec3 dockThere = AngledTrackAnchorBlock.axisVector(other.getBlockState());
		if (dockThere.dot(hereToThere) > 0)
			dockThere = dockThere.scale(-1);

		Vec3 startHere = hereCenter.add(dockHere.scale(0.5));
		Vec3 startThere = thereCenter.add(dockThere.scale(0.5));

		// Tangents: the free part. Nothing downstream rounds these.
		Vec3 axisHere = directionFromYaw(yaw);
		if (axisHere.dot(hereToThere) < 0)
			axisHere = axisHere.scale(-1);

		Vec3 axisThere = directionFromYaw(other.yaw);
		if (axisThere.dot(hereToThere) > 0)
			axisThere = axisThere.scale(-1);

		Vec3 up = new Vec3(0, 1, 0);

		return new BezierConnection(
			Couple.create(worldPosition, partner),
			Couple.create(startHere, startThere),
			Couple.create(axisHere, axisThere),
			Couple.create(up, up),
			primary,
			false, // no girder
			TrackMaterial.ANDESITE);
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
		tag.putBoolean(TAG_PRIMARY, primary);
		if (partner != null)
			tag.putLong(TAG_PARTNER, partner.asLong());
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		yaw = tag.getFloat(TAG_YAW);
		primary = tag.getBoolean(TAG_PRIMARY);
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
