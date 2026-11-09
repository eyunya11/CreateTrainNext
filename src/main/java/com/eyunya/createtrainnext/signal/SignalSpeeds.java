package com.eyunya.createtrainnext.signal;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.SignalBoundary;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * The speed a signal holds a train to as the train passes it.
 *
 * <p>What a driver goes by is the lamps of the head that faces the train, so that is what is asked:
 * the aspect that head shows, worked out afresh as the train passes rather than read from what it
 * showed up to half a second before. The speed is that aspect's, from the config, and it lasts until
 * the train passes the next signal, as a signal's does on a real line. A green signal imposes
 * nothing, so it lifts the one before it; the exception is the six-aspect signal's 進行, a single
 * green, which holds a train to a speed, and its 高速進行, two greens, which lifts it.
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

	/**
	 * The speed in km/h an aspect shown by a head of this type holds a train to, or {@link #NONE}.
	 *
	 * <p>The type matters for 進行 alone, which the six-aspect holds to a speed of its own and every
	 * other head holds to nothing.
	 */
	public static int speedOf(SignalType type, SignalAspect aspect) {
		return switch (aspect) {
			case PROCEED -> type.proceedSpeed() == SignalAspect.NO_LIMIT ? NONE : type.proceedSpeed();
			case HIGH_SPEED_PROCEED -> NONE;
			case STOP -> SignalAspect.RESTRICTED.getSpeedLimit();
			default -> aspect.getSpeedLimit();
		};
	}

	/**
	 * What a train heading for {@code toward} along the edge {@code signal} is on is held to.
	 *
	 * <p>The heads on the side the train meets the signal from, and the lowest of what they say if
	 * there are several. Signals without a head of this mod's — Create's own — say nothing, so they
	 * lift a limit like a green one does.
	 *
	 * <p>A signal in a chunk that is not loaded is asked all the same: what a head shows comes from the
	 * graph, and which head it is, or that it is one of Create's, from {@link SignalHeads}, so a train is
	 * held the same whether or not anyone is near. Only a signal the server has never seen loaded cannot be
	 * asked, and if such signals are all there is, the answer is that it is not known and the train's limit
	 * stays as it was.
	 *
	 * @param graph the graph the train is on, which the signal is in, or null to have it looked for.
	 * @return a speed in km/h, {@link #NONE} or {@link #UNKNOWN}.
	 */
	public static int seenFrom(MinecraftServer server, @Nullable TrackGraph graph, SignalBoundary signal,
		TrackNode toward) {
		return scan(server, graph, signal, toward, true);
	}

	/**
	 * What a train still some way off will meet at this signal: what the lamps show now, as they were
	 * last worked out, or for a head whose lamps are not being kept up (its chunk is not loaded, or is
	 * loaded without ticking), as they would be worked out now. A
	 * signal at stop says nothing here, since it is the train being stopped by it, and not a speed, that
	 * is going to happen.
	 *
	 * <p>The graph the signal is in is looked for among all of them if a head that is not loaded needs it;
	 * {@link #shownTo(MinecraftServer, TrackGraph, SignalBoundary, TrackNode)} saves that where the graph
	 * is known.
	 *
	 * @return a speed in km/h, {@link #NONE} or {@link #UNKNOWN}.
	 */
	public static int shownTo(MinecraftServer server, SignalBoundary signal, TrackNode toward) {
		return scan(server, null, signal, toward, false);
	}

	/** {@link #shownTo(MinecraftServer, SignalBoundary, TrackNode)} for a signal in {@code graph}. */
	public static int shownTo(MinecraftServer server, @Nullable TrackGraph graph, SignalBoundary signal,
		TrackNode toward) {
		return scan(server, graph, signal, toward, false);
	}

	private static int scan(MinecraftServer server, @Nullable TrackGraph graph, SignalBoundary signal,
		TrackNode toward, boolean fresh) {
		boolean side = signal.isPrimary(toward);
		ResourceKey<Level> dimension = signal.edgeLocation.getFirst().dimension;
		ServerLevel level = server.getLevel(dimension);
		if (level == null)
			return UNKNOWN;

		int result = NONE;
		boolean unknown = false;
		for (BlockPos pos : signal.blockEntities.get(side)
			.keySet()) {
			SignalType type;
			SignalAspect aspect = null;
			if (level.isLoaded(pos)) {
				if (!(level.getBlockEntity(pos) instanceof MultiAspectSignalBlockEntity head))
					continue;
				type = head.signalType();
				if (fresh)
					aspect = head.aspectSeenFrom(graph, signal, side);
				else if (level.shouldTickBlocksAt(pos))
					aspect = head.getAspect();
				// A head in a chunk that is loaded but not ticking, at the edge of what a player keeps
				// loaded, does not work its lamps out, and what they show may be from long ago; it is
				// worked out here instead, as one that is not loaded is.
			} else {
				type = SignalHeads.recordedType(server, dimension, pos);
				if (type == null) {
					// One of Create's own says nothing, as it does when loaded.
					if (!SignalHeads.recordedOther(server, dimension, pos))
						unknown = true;
					continue;
				}
			}
			if (aspect == null) {
				if (graph == null)
					graph = SignalHeads.graphOf(signal);
				aspect = SignalHeads.resolve(graph, signal, side, type);
			}
			if (!fresh && aspect == SignalAspect.STOP) {
				unknown = true;
				continue;
			}
			int speed = speedOf(type, aspect);
			if (speed != NONE)
				result = result == NONE ? speed : Math.min(result, speed);
		}
		return result == NONE && unknown ? UNKNOWN : result;
	}
}
