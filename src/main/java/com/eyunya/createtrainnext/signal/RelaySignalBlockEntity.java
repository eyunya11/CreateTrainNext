package com.eyunya.createtrainnext.signal;

import java.util.List;

import com.eyunya.createtrainnext.CTNRegistry;
import com.simibubi.create.api.contraption.transformable.TransformableBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A 中継信号機 — a relay, which repeats the next signal and does nothing else.
 *
 * <p>It has no authority over anything. A train passing it neither reads it nor changes it; it
 * reserves no block, holds nothing back, and imposes no speed. That is not a simplification, it is
 * what a relay is: it exists so a driver who cannot yet see a signal — round a curve, over a crest,
 * behind a platform roof — learns what it is showing in time to act on it.
 *
 * <p>Which is why, unlike {@link MultiAspectSignalBlockEntity}, this puts <em>nothing</em> in the
 * track graph. It keeps a {@link TrackTargetingBehaviour} for the sake of the placement it gives —
 * Create's click-the-track-then-place, and the marker drawn on the track it points at — but
 * {@link SignalLookahead.Targeting} refuses to create the edge point that behaviour would normally
 * register. A point on an edge is the only way a trackside block can affect a train, and this does
 * not have one, so it cannot. The targeting is used for one thing only: to work out where on the
 * graph this stands, so {@link NextSignalFinder} knows where to start looking.
 */
public class RelaySignalBlockEntity extends SmartBlockEntity implements TransformableBlockEntity {

	private static final String TAG_INDICATION = "Indication";

	/**
	 * Slower than the game ticks. A relay is a repeater of something already a block or more away;
	 * half a second of lag on it cannot matter, and the graph walk behind it is not free.
	 */
	private static final int INDICATION_TICK_RATE = 10;

	public TrackTargetingBehaviour<SignalBoundary> target;

	private RelayIndication indication = RelayIndication.DARK;

	public RelaySignalBlockEntity(BlockPos pos, BlockState state) {
		super(CTNRegistry.RELAY_SIGNAL_ENTITY.get(), pos, state);
		setLazyTickRate(INDICATION_TICK_RATE);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		behaviours.add(target = new SignalLookahead.Targeting(this));
	}

	public RelayIndication getIndication() {
		return indication;
	}

	@Override
	public void lazyTick() {
		super.lazyTick();
		if (level == null || level.isClientSide())
			return;

		RelayIndication resolved = resolveIndication();
		if (resolved == indication)
			return;

		indication = resolved;
		// notifyUpdate rather than setChanged: the lamps are drawn on the client, so a changed
		// indication is only visible once it has been sent there.
		notifyUpdate();
	}

	/**
	 * What to show for the strictest of the signals ahead. See {@link SignalLookahead} for how they are
	 * found and read.
	 */
	private RelayIndication resolveIndication() {
		return level == null ? RelayIndication.DARK : RelayIndication.of(SignalLookahead.strictestAhead(level, target));
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		NBTHelper.writeEnum(tag, TAG_INDICATION, indication);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		if (tag.contains(TAG_INDICATION))
			indication = NBTHelper.readEnum(tag, TAG_INDICATION, RelayIndication.class);
	}

	@Override
	protected AABB createRenderBoundingBox() {
		// Reaches the targeted track, which can be several blocks away, because the placement
		// marker drawn on it belongs to this block entity.
		return new AABB(Vec3.atLowerCornerOf(worldPosition), Vec3.atLowerCornerOf(target.getGlobalPosition()))
			.inflate(2);
	}

	@Override
	public void transform(BlockEntity be, StructureTransform transform) {
		target.transform(be, transform);
	}
}
