package com.eyunya.createtrainnext.speed;

import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.signal.SingleBlockEntityEdgePoint;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.infrastructure.config.AllConfigs;
import com.simibubi.create.infrastructure.config.CTrains;

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
	 * Limits are held in kilometres per hour, which is the number the sign shows. Zero is a legal
	 * setting and means stop.
	 *
	 * <p>Create counts speed in blocks per second, and a block is taken to be a metre, so a limit
	 * becomes the speed trains are compared with by dividing by {@link #KMH_PER_BLOCK_PER_SECOND}.
	 */
	public static final int MIN_LIMIT = 0;
	public static final int DEFAULT_LIMIT = 40;

	/** How many km/h one block per second is: a metre a second is 3.6 km/h. */
	public static final double KMH_PER_BLOCK_PER_SECOND = 3.6;

	/**
	 * The highest limit a sign can be set to: the fastest a train can go under Create's config,
	 * in km/h. A sign above that would promise a speed nothing reaches.
	 *
	 * <p>The larger of the two top speeds, standard and powered, so that a limit can be set for
	 * either kind. It follows the config rather than being a constant, and so is asked for each
	 * time; it is rounded down, so that a sign never reads faster than a train can run.
	 */
	public static int maxLimit() {
		CTrains trains = AllConfigs.server().trains;
		double fastest = Math.max(trains.trainTopSpeed.getF(), trains.poweredTrainTopSpeed.getF());
		// The small allowance is for 40 x 3.6 coming out as 143.99999999999997.
		return Math.max(MIN_LIMIT + 1, (int) Math.floor(fastest * KMH_PER_BLOCK_PER_SECOND + 1.0E-9));
	}

	private int limit = DEFAULT_LIMIT;

	/** The limit in km/h, held to the config as it stands now: a config lowered since is no excuse for a faster sign. */
	public int getLimit() {
		return Math.min(limit, maxLimit());
	}

	public void setLimit(int limit) {
		this.limit = Mth.clamp(limit, MIN_LIMIT, maxLimit());
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
