package com.eyunya.createtrainnext.speed;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.signal.SignalSpeeds;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.SignalBoundary;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Which speed limits each train is under, and what that does to how fast it may go.
 *
 * <p>There are two, and they run side by side. A speed limit sign begins where a train's front passes
 * one facing its way and lasts until it passes the next sign, a release sign being a limit as high as
 * any train goes. A signal begins where the front passes one and lasts until it passes the next
 * signal, and says how fast according to the aspect its head shows; a green one says nothing. The
 * train is held to the slower of the two. What has to be remembered is the last of each that every
 * train passed, and that is kept here, by train, and saved with the world: a train that was held to
 * 60 when the server stopped is still held to 60 when it starts again.
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
	 * Called as the front of a train passes a speed limit sign.
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
			data.releaseSign(train.id);
		else
			data.setSign(train.id, point.getLimit());
	}

	/** Called as the front of a train passes a signal, which is the same thing for a green one as for a signal of Create's own. */
	public static void passedSignal(Train train, SignalBoundary signal, TrackNode toward) {
		MinecraftServer server = server();
		if (server == null)
			return;
		applySignal(train, SignalSpeeds.seenFrom(server, train.graph, signal, toward));
	}

	/** What a signal said, as {@link SignalSpeeds#seenFrom} gives it, becomes the train's signal limit. */
	public static void applySignal(Train train, int said) {
		TrainSpeedLimitData data = data();
		if (data == null || said == SignalSpeeds.UNKNOWN)
			return;
		if (said == SignalSpeeds.NONE)
			data.releaseSignal(train.id);
		else
			data.setSignal(train.id, said);
	}

	/** The limit a train is under from a sign in km/h, or null if it is under none. */
	@Nullable
	public static Integer limitOf(Train train) {
		TrainSpeedLimitData data = data();
		return data == null ? null : data.sign(train.id);
	}

	/** The limit a train is under from the last signal it passed in km/h, or null if it is under none. */
	@Nullable
	public static Integer signalLimitOf(Train train) {
		TrainSpeedLimitData data = data();
		return data == null ? null : data.signal(train.id);
	}

	/**
	 * A top speed, in blocks a tick, held to the slower of the train's limits.
	 *
	 * @param topSpeed what Create gave: the top speed the config allows.
	 */
	public static float cap(Train train, float topSpeed) {
		TrainSpeedLimitData data = data();
		if (data == null)
			return topSpeed;
		Integer sign = data.sign(train.id);
		Integer signal = data.signal(train.id);
		float capped = topSpeed;
		if (sign != null)
			capped = Math.min(capped, (float) (sign / KMH_PER_BLOCK_PER_TICK));
		if (signal != null)
			capped = Math.min(capped, (float) (signal / KMH_PER_BLOCK_PER_TICK));
		return capped;
	}

	@Nullable
	private static MinecraftServer server() {
		MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
		return server == null || !server.isSameThread() ? null : server;
	}

	/** The saved limits, or null where there is no server thread to ask: on a client, or off the thread. */
	@Nullable
	private static TrainSpeedLimitData data() {
		MinecraftServer server = server();
		return server == null ? null : TrainSpeedLimitData.get(server);
	}

	/** The limits that outlast a restart, saved with the overworld the way Create's own railway data is. */
	public static class TrainSpeedLimitData extends SavedData {

		private static final String NAME = CreateTrainNext.ID + "_train_speed_limits";
		private static final String TAG_TRAIN = "Train";
		private static final String TAG_LIMIT = "Limit";
		private static final String TAG_SIGNS = "Limits";
		private static final String TAG_SIGNALS = "SignalLimits";

		private final Map<UUID, Integer> signs = new HashMap<>();
		private final Map<UUID, Integer> signals = new HashMap<>();

		static TrainSpeedLimitData get(MinecraftServer server) {
			return server.overworld()
				.getDataStorage()
				.computeIfAbsent(new SavedData.Factory<>(TrainSpeedLimitData::new, TrainSpeedLimitData::load, null),
					NAME);
		}

		@Nullable
		public Integer sign(UUID train) {
			return signs.get(train);
		}

		@Nullable
		public Integer signal(UUID train) {
			return signals.get(train);
		}

		public void setSign(UUID train, int kmh) {
			put(signs, train, kmh);
		}

		public void setSignal(UUID train, int kmh) {
			put(signals, train, kmh);
		}

		public void releaseSign(UUID train) {
			remove(signs, train);
		}

		public void releaseSignal(UUID train) {
			remove(signals, train);
		}

		private void put(Map<UUID, Integer> limits, UUID train, int kmh) {
			if (Integer.valueOf(kmh)
				.equals(limits.put(train, kmh)))
				return;
			setDirty();
		}

		private void remove(Map<UUID, Integer> limits, UUID train) {
			if (limits.remove(train) != null)
				setDirty();
		}

		@Override
		public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
			tag.put(TAG_SIGNS, write(signs));
			tag.put(TAG_SIGNALS, write(signals));
			return tag;
		}

		private static ListTag write(Map<UUID, Integer> limits) {
			ListTag list = new ListTag();
			limits.forEach((train, kmh) -> {
				CompoundTag entry = new CompoundTag();
				entry.putUUID(TAG_TRAIN, train);
				entry.putInt(TAG_LIMIT, kmh);
				list.add(entry);
			});
			return list;
		}

		public static TrainSpeedLimitData load(CompoundTag tag, HolderLookup.Provider registries) {
			TrainSpeedLimitData data = new TrainSpeedLimitData();
			read(tag.getList(TAG_SIGNS, Tag.TAG_COMPOUND), data.signs);
			read(tag.getList(TAG_SIGNALS, Tag.TAG_COMPOUND), data.signals);
			return data;
		}

		private static void read(ListTag list, Map<UUID, Integer> into) {
			for (Tag each : list) {
				CompoundTag entry = (CompoundTag) each;
				into.put(entry.getUUID(TAG_TRAIN), entry.getInt(TAG_LIMIT));
			}
		}
	}
}
