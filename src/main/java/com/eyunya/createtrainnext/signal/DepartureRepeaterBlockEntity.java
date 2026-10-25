package com.eyunya.createtrainnext.signal;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNRegistry;
import com.simibubi.create.api.contraption.transformable.TransformableBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A 出発反応標識: lit while the next signal shows anything but 停止.
 *
 * <p>It reads the signal as the relay does ({@link SignalLookahead}), and puts nothing in the track graph
 * for the same reason: it tells people something and trains nothing. Where the line divides before the
 * next signal it goes by the strictest, so it is lit only when every way on has cleared; a real one stands
 * for one starting signal, and two signals want two lamps.
 *
 * <p>Dark when there is no signal to read, as the relay is: a lamp that is lit says a signal has cleared,
 * and with no signal that would not be true.
 */
public class DepartureRepeaterBlockEntity extends SmartBlockEntity implements TransformableBlockEntity {

	private static final String TAG_LIT = "Lit";

	/** As the relay's: half a second late is soon enough for a guard, and the graph walk is not free. */
	private static final int TICK_RATE = 10;

	public TrackTargetingBehaviour<SignalBoundary> target;

	private boolean lit;

	public DepartureRepeaterBlockEntity(BlockPos pos, BlockState state) {
		super(CTNRegistry.DEPARTURE_REPEATER_ENTITY.get(), pos, state);
		setLazyTickRate(TICK_RATE);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		behaviours.add(target = new SignalLookahead.Targeting(this));
	}

	public boolean isLit() {
		return lit;
	}

	@Override
	public void lazyTick() {
		super.lazyTick();
		if (level == null || level.isClientSide())
			return;

		boolean resolved = resolveLit();
		if (resolved == lit)
			return;
		lit = resolved;
		// The lamp is drawn on the client, so a change is only seen once it has been sent there.
		notifyUpdate();
	}

	/** Whether the lamp should be lit now, from what the signal ahead shows at this moment. */
	public boolean resolveLit() {
		return level != null && litFor(SignalLookahead.strictestAhead(level, target));
	}

	/** Lit for any aspect but 停止, and dark with none at all. */
	public static boolean litFor(@Nullable SignalAspect strictest) {
		return strictest != null && strictest != SignalAspect.STOP;
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		tag.putBoolean(TAG_LIT, lit);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		lit = tag.getBoolean(TAG_LIT);
	}

	@Override
	protected AABB createRenderBoundingBox() {
		// Reaches the targeted track, as the relay's does, for the marker drawn on it.
		return new AABB(Vec3.atLowerCornerOf(worldPosition), Vec3.atLowerCornerOf(target.getGlobalPosition()))
			.inflate(2);
	}

	@Override
	public void transform(BlockEntity be, StructureTransform transform) {
		target.transform(be, transform);
	}
}
