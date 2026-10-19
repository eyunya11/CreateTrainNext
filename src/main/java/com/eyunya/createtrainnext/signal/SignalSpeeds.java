package com.eyunya.createtrainnext.signal;

import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.SignalBoundary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The speed a signal holds a train to as the train passes it.
 *
 * <p>What a driver goes by is the lamps of the head that faces the train, so that is what is asked:
 * the aspect that head shows, worked out afresh as the train passes rather than read from what it
 * showed up to half a second before. The speed is that aspect's, from the config, and it lasts until
 * the train passes the next signal, as a signal's does on a real line. A green signal imposes
 * nothing, so it lifts the one before it.
 *
 * <p>A train passing at red, which a driver at the controls can do, is held to the speed of the
 * lowest aspect that has one, 警戒. The lamps never lie about being at stop, and a driver who has
 * gone through one is not told to carry on at line speed.
 */
public final class SignalSpeeds {

	/** The heads there are for the train to go by could not be looked at, so nothing is known. */
	public static final int UNKNOWN = -2;

	/** Nothing is imposed: a green signal, or one without a head of this mod's. */
	public static final int NONE = -1;

	private SignalSpeeds() {}

	/** The speed in km/h an aspect holds a train to, or {@link #NONE}. */
	public static int speedOf(SignalAspect aspect) {
		return switch (aspect) {
			case PROCEED -> NONE;
			case STOP -> SignalAspect.RESTRICTED.getSpeedLimit();
			default -> aspect.getSpeedLimit();
		};
	}

	/**
	 * What a train heading for {@code toward} along the edge {@code signal} is on is held to.
	 *
	 * <p>The heads on the side the train meets the signal from, and the lowest of what they say if
	 * there are several. Signals without a head of this mod's — Create's own — say nothing, so they
	 * lift a limit like a green one does. Heads in a chunk that is not loaded cannot be asked, and
	 * if they are all there is, the answer is that it is not known and the train's limit stays as it
	 * was.
	 *
	 * @return a speed in km/h, {@link #NONE} or {@link #UNKNOWN}.
	 */
	public static int seenFrom(MinecraftServer server, TrackGraph graph, SignalBoundary signal, TrackNode toward) {
		boolean side = signal.isPrimary(toward);
		ServerLevel level = server.getLevel(signal.edgeLocation.getFirst().dimension);
		if (level == null)
			return UNKNOWN;

		int result = NONE;
		boolean unloaded = false;
		for (BlockPos pos : signal.blockEntities.get(side)
			.keySet()) {
			if (!level.isLoaded(pos)) {
				unloaded = true;
				continue;
			}
			if (!(level.getBlockEntity(pos) instanceof MultiAspectSignalBlockEntity head))
				continue;
			int speed = speedOf(head.aspectSeenFrom(graph, signal, side));
			if (speed != NONE)
				result = result == NONE ? speed : Math.min(result, speed);
		}
		return result == NONE && unloaded ? UNKNOWN : result;
	}
}
