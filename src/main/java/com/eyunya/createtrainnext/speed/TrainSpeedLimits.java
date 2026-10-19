package com.eyunya.createtrainnext.speed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.signal.SignalSpeeds;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.entity.TravellingPoint.ITrackSelector;
import com.simibubi.create.content.trains.entity.TravellingPoint.SteerDirection;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.SignalBoundary;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

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
 * a driver can reach.
 *
 * <h2>Slowing in time</h2>
 *
 * <p>A train has to be at the speed of a limit when its front passes the sign, not begin slowing
 * there. So the top speed it is given is also held to what it could still slow from, at its own rate
 * of braking, to arrive at the lowest limit ahead of it at that limit's speed: the speed {@code v}
 * at distance {@code d} from a limit of {@code L} that braking at {@code a} gets down to {@code L}
 * is {@code sqrt(L² + 2ad)}. The signs and signals ahead are found by scouting down the track from the
 * train's front the way Create scouts for its signals, along the path it is navigating or the way its
 * driver is steering, as far as it could need to brake from its top speed. A limit is only counted
 * from the moment it is ahead; the sign a train has just passed is behind it and already its limit.
 *
 * <p>The braking assumed is a little gentler than the train can manage, so that a train which has to
 * follow the curve down, one step of braking at a time, keeps up with it. A train that is already too
 * fast for a limit that appears close ahead cannot be helped, and brakes as hard as it can.
 *
 * <p>Only the server's trains are held to anything. The client has copies of the trains too and
 * asks them the same questions, but the state here belongs to the server's thread.
 */
public final class TrainSpeedLimits {

	/** A block a tick is 20 metres a second, and a metre a second is 3.6 km/h. */
	private static final double KMH_PER_BLOCK_PER_TICK = SpeedLimitEdgePoint.KMH_PER_BLOCK_PER_SECOND * 20;

	/** How much of the train's own braking is relied on to arrive at a limit's speed. */
	private static final double BRAKING_SHARE = 0.8;

	/**
	 * Blocks of track scouted past the braking distance, for the inaccuracy {@code TravellingPoint}
	 * has over a long distance (Create adds fifty for its own scouting).
	 */
	private static final double REACH_MARGIN = 30;

	/** The slowing is finished this far before the point, for the same reason. */
	private static final double ARRIVE_EARLY = 1;

	/** The scouting done for a train in a tick, since the speed is asked for several times in one. */
	private static final Map<Train, Scouted> SCOUTED = new WeakHashMap<>();

	private record Scouted(int tick, float topSpeed, float allowed) {}

	private static boolean warned;

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
	 * A top speed, in blocks a tick, held to the slower of the train's limits and to what it can still
	 * slow from in time for the limits ahead.
	 *
	 * @param topSpeed what Create gave: the top speed the config allows.
	 */
	public static float cap(Train train, float topSpeed) {
		MinecraftServer server = server();
		TrainSpeedLimitData data = server == null ? null : TrainSpeedLimitData.get(server);
		if (data == null)
			return topSpeed;

		Integer sign = data.sign(train.id);
		Integer signal = data.signal(train.id);
		float capped = topSpeed;
		if (sign != null)
			capped = Math.min(capped, (float) (sign / KMH_PER_BLOCK_PER_TICK));
		if (signal != null)
			capped = Math.min(capped, (float) (signal / KMH_PER_BLOCK_PER_TICK));
		return Math.min(capped, scoutAhead(server, data, train, topSpeed, sign, signal));
	}

	/** A sign or a signal found ahead of a train, as the scouting finds it. */
	public record Ahead(double distance, boolean sign, int kmh) {

		/** The speed in km/h that this sets, or {@link SignalSpeeds#NONE} where it lifts the limit it replaces. */
		static final int LIFTS = SignalSpeeds.NONE;
	}

	/**
	 * The speed the train can have now and still arrive at every limit ahead at its speed, in blocks a
	 * tick, or the top speed where nothing ahead asks for less.
	 */
	private static float scoutAhead(MinecraftServer server, TrainSpeedLimitData data, Train train, float topSpeed,
		@Nullable Integer sign, @Nullable Integer signal) {
		// A train at a standstill has no front to speak of yet, and nothing to slow down from.
		if (train.graph == null || train.carriages.isEmpty() || train.speed == 0 || topSpeed <= 0)
			return topSpeed;

		int tick = server.getTickCount();
		Scouted known = SCOUTED.get(train);
		if (known != null && known.tick() == tick && known.topSpeed() == topSpeed)
			return known.allowed();

		double braking = BRAKING_SHARE * train.acceleration();
		float allowed = topSpeed;
		if (braking > 0) {
			boolean forward = train.speed > 0;
			TravellingPoint front = forward ? train.carriages.get(0)
				.getLeadingPoint()
				: train.carriages.get(train.carriages.size() - 1)
					.getTrailingPoint();
			if (front != null && front.edge != null) {
				TravellingPoint probe = new TravellingPoint(front.node1, front.node2, front.edge, front.position,
					front.upsideDown);
				ITrackSelector selector = train.navigation.destination != null ? train.navigation.controlSignalScout()
					: probe.steer(train.manualSteer == null ? SteerDirection.NONE : train.manualSteer, new Vec3(0, 1, 0));
				double reach = brakingDistance(topSpeed, braking) + REACH_MARGIN;
				try {
					List<Ahead> ahead = scan(server, train.graph, probe, forward, reach, selector);
					allowed = allowedAhead(ahead, sign == null ? Double.POSITIVE_INFINITY : sign,
						signal == null ? Double.POSITIVE_INFINITY : signal, braking, topSpeed);
				} catch (RuntimeException e) {
					// Scouting is a convenience on top of the limits and must not be what stops a train, or
					// the server: the limits themselves still hold without it.
					if (!warned) {
						warned = true;
						CreateTrainNext.LOGGER.warn("Could not look ahead for speed limits", e);
					}
				}
			}
		}
		SCOUTED.put(train, new Scouted(tick, topSpeed, allowed));
		return allowed;
	}

	/** How far a train at {@code speed} needs to slow to nothing at {@code braking} a tick, in blocks. */
	static double brakingDistance(double speed, double braking) {
		return speed * speed / (2 * braking);
	}

	/**
	 * The signs and signals along the track from {@code probe}, in the order they come, as far as
	 * {@code reach} blocks. The probe is moved; it is meant to be a copy.
	 */
	public static List<Ahead> scan(@Nullable MinecraftServer server, TrackGraph graph, TravellingPoint probe,
		boolean forward, double reach, ITrackSelector selector) {
		List<Ahead> found = new ArrayList<>();
		probe.travel(graph, forward ? reach : -reach, selector, (distance, passed) -> {
			if (passed.getFirst()instanceof SpeedLimitEdgePoint point && point.appliesTo(passed.getSecond()
				.getSecond()))
				found.add(new Ahead(distance, true, point.isRelease() ? Ahead.LIFTS : point.getLimit()));
			else if (server != null && passed.getFirst()instanceof SignalBoundary boundary) {
				int shown = SignalSpeeds.shownTo(server, boundary, passed.getSecond()
					.getSecond());
				if (shown != SignalSpeeds.UNKNOWN)
					found.add(new Ahead(distance, false, shown));
			}
			return false;
		});
		return found;
	}

	/**
	 * The fastest a train may be going now to be down to the speed of every limit ahead when it gets
	 * to it, given the limits it is under, which are replaced one by one as each is reached.
	 *
	 * @param signKmh   the sign limit it is under now, or infinity.
	 * @param signalKmh the signal limit it is under now, or infinity.
	 * @param braking   how much it slows in a tick, in blocks a tick, that can be relied on.
	 */
	public static float allowedAhead(List<Ahead> ahead, double signKmh, double signalKmh, double braking,
		float topSpeed) {
		double allowed = topSpeed;
		for (Ahead each : ahead) {
			double kmh = each.kmh() == Ahead.LIFTS ? Double.POSITIVE_INFINITY : each.kmh();
			if (each.sign())
				signKmh = kmh;
			else
				signalKmh = kmh;
			double lowest = Math.min(signKmh, signalKmh);
			if (Double.isInfinite(lowest))
				continue;
			double limit = lowest / KMH_PER_BLOCK_PER_TICK;
			double room = Math.max(0, each.distance() - ARRIVE_EARLY);
			allowed = Math.min(allowed, Math.sqrt(limit * limit + 2 * braking * room));
		}
		return (float) allowed;
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
