package com.eyunya.createtrainnext.speed;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CreateTrainNext;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackNode;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Which speed limit each train is under, and what that does to how fast it may go.
 *
 * <p>A limit begins where a train's front passes a sign facing its way and lasts until it passes the
 * next one, a release sign being a limit as high as any train goes. So what has to be remembered is
 * the last sign each train passed, and that is kept here, by train, and saved with the world: a
 * train that was held to 60 when the server stopped is still held to 60 when it starts again.
 *
 * <p>It acts through {@code Train#maxSpeed}, which everything that decides how fast a train should
 * go asks — the schedule's navigation for the speed it aims at, and the controls for the top speed
 * a driver can reach. A train over the limit is not stopped short; it slows at its own rate of
 * braking, as it would for a signal, so it enters a limit at the speed it had and leaves the sign
 * behind it still slowing. The braking distance is the train's own and is not looked for ahead.
 *
 * <p>Only the server's trains are held to anything. The client has copies of the trains too and
 * asks them the same questions, but the state here belongs to the server's thread.
 */
public final class TrainSpeedLimits {

	/** A block a tick is 20 metres a second, and a metre a second is 3.6 km/h. */
	private static final double KMH_PER_BLOCK_PER_TICK = SpeedLimitEdgePoint.KMH_PER_BLOCK_PER_SECOND * 20;

	private TrainSpeedLimits() {}

	/**
	 * Called as the front of a train passes a point on the track. Only a speed limit does anything.
	 *
	 * @param toward the node the train is heading for on the edge the point is on.
	 */
	public static void passed(Train train, SpeedLimitEdgePoint point, TrackNode toward) {
		// A sign faces one way along the track, and a train going the other way has it behind it.
		if (!point.appliesTo(toward))
			return;
		TrainSpeedLimitData data = data();
		if (data == null)
			return;
		if (point.isRelease())
			data.release(train.id);
		else
			data.set(train.id, point.getLimit());
	}

	/** The limit a train is under in km/h, or null if it is under none. */
	@Nullable
	public static Integer limitOf(Train train) {
		TrainSpeedLimitData data = data();
		return data == null ? null : data.get(train.id);
	}

	/**
	 * A top speed, in blocks a tick, held to the train's limit.
	 *
	 * @param topSpeed what Create gave: the top speed the config allows.
	 */
	public static float cap(Train train, float topSpeed) {
		Integer limit = limitOf(train);
		return limit == null ? topSpeed : Math.min(topSpeed, (float) (limit / KMH_PER_BLOCK_PER_TICK));
	}

	/** The saved limits, or null where there is no server thread to ask: on a client, or off the thread. */
	@Nullable
	private static TrainSpeedLimitData data() {
		MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
		if (server == null || !server.isSameThread())
			return null;
		return TrainSpeedLimitData.get(server);
	}

	/** The limits that outlast a restart, saved with the overworld the way Create's own railway data is. */
	public static class TrainSpeedLimitData extends SavedData {

		private static final String NAME = CreateTrainNext.ID + "_train_speed_limits";
		private static final String TAG_TRAIN = "Train";
		private static final String TAG_LIMIT = "Limit";

		private final Map<UUID, Integer> limits = new HashMap<>();

		static TrainSpeedLimitData get(MinecraftServer server) {
			return server.overworld()
				.getDataStorage()
				.computeIfAbsent(new SavedData.Factory<>(TrainSpeedLimitData::new, TrainSpeedLimitData::load, null),
					NAME);
		}

		@Nullable
		public Integer get(UUID train) {
			return limits.get(train);
		}

		public void set(UUID train, int kmh) {
			if (Integer.valueOf(kmh)
				.equals(limits.put(train, kmh)))
				return;
			setDirty();
		}

		public void release(UUID train) {
			if (limits.remove(train) != null)
				setDirty();
		}

		@Override
		public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
			ListTag list = new ListTag();
			limits.forEach((train, kmh) -> {
				CompoundTag entry = new CompoundTag();
				entry.putUUID(TAG_TRAIN, train);
				entry.putInt(TAG_LIMIT, kmh);
				list.add(entry);
			});
			tag.put("Limits", list);
			return tag;
		}

		public static TrainSpeedLimitData load(CompoundTag tag, HolderLookup.Provider registries) {
			TrainSpeedLimitData data = new TrainSpeedLimitData();
			for (Tag each : tag.getList("Limits", Tag.TAG_COMPOUND)) {
				CompoundTag entry = (CompoundTag) each;
				data.limits.put(entry.getUUID(TAG_TRAIN), entry.getInt(TAG_LIMIT));
			}
			return data;
		}
	}
}
