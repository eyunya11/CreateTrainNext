package com.eyunya.createtrainnext.speed;

import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.signal.SingleBlockEntityEdgePoint;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * A speed limit sitting at one point along a track edge.
 *
 * <p>This is the half of the block that lives in Create's track graph rather than in the world:
 * the graph is what trains consult while driving, and it outlives the chunk the sign is in. The
 * block entity only holds a {@code TrackTargetingBehaviour} pointing at this.
 *
 * <p>Extending {@link SingleBlockEntityEdgePoint} rather than {@code TrackEdgePoint} directly means
 * one sign owns one point — unlike a signal, where the two blocks facing each other across a
 * boundary share one. It also declines to merge, so two signs cannot end up on the same spot.
 *
 * <p>The limit is the player's, set through the value box on the sign — see
 * {@link SpeedLimitBlockEntity}, which owns the setting and pushes it here. Nothing reads
 * {@link #getLimit()} back out yet; enforcing it against passing trains is the next step.
 */
public class SpeedLimitEdgePoint extends SingleBlockEntityEdgePoint {

	private static final String TAG_LIMIT = "Limit";

	/**
	 * Limits are held in the same unit Create shows on a train's speed readout, so a sign reading
	 * 40 and a schedule set to 40 mean the same thing. Zero is a legal setting and means stop.
	 */
	public static final int MIN_LIMIT = 0;
	public static final int MAX_LIMIT = 100;
	public static final int DEFAULT_LIMIT = 40;

	private int limit = DEFAULT_LIMIT;

	public int getLimit() {
		return limit;
	}

	public void setLimit(int limit) {
		this.limit = Mth.clamp(limit, MIN_LIMIT, MAX_LIMIT);
	}

	@Override
	public void blockEntityAdded(BlockEntity blockEntity, boolean front) {
		super.blockEntityAdded(blockEntity, front);
		// The sign exists before its point does — the track under it has to be walked first — so the
		// value the player dialled in is sitting on the block entity by the time we get here, and
		// this is the only chance to pick it up. Without it a sign set to 60 before the graph caught
		// up would come back as the default.
		ScrollValueBehaviour scrollValue = BlockEntityBehaviour.get(blockEntity, ScrollValueBehaviour.TYPE);
		if (scrollValue != null)
			setLimit(scrollValue.getValue());
	}

	@Override
	public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean migration, DimensionPalette dimensions) {
		super.read(nbt, registries, migration, dimensions);
		// Only a fallback, and normally overwritten moments later: blockEntityAdded runs after this
		// and takes the sign's own setting, which is the authoritative copy. This matters for the
		// window where a point is rebuilt from migration data before its block entity is consulted.
		if (nbt.contains(TAG_LIMIT))
			limit = nbt.getInt(TAG_LIMIT);
	}

	@Override
	public void write(CompoundTag nbt, HolderLookup.Provider registries, DimensionPalette dimensions) {
		super.write(nbt, registries, dimensions);
		nbt.putInt(TAG_LIMIT, limit);
	}

	@Override
	public void read(FriendlyByteBuf buffer, DimensionPalette dimensions) {
		super.read(buffer, dimensions);
		limit = buffer.readVarInt();
	}

	@Override
	public void write(FriendlyByteBuf buffer, DimensionPalette dimensions) {
		super.write(buffer, dimensions);
		buffer.writeVarInt(limit);
	}
}
