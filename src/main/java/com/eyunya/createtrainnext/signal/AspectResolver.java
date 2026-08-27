package com.eyunya.createtrainnext.signal;

import java.util.Map;
import java.util.UUID;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import com.simibubi.create.content.trains.signal.SignalPropagator;

/**
 * Works out which aspect a signal should be showing, by counting how far ahead the line is clear.
 *
 * <p>The count is the whole of the Japanese five-aspect system bar one exception: the signal
 * protecting an occupied block is at {@link SignalAspect#STOP}, the one behind it
 * {@link SignalAspect#APPROACH}, the one behind that {@link SignalAspect#REDUCED_SPEED}, and
 * anything further back {@link SignalAspect#PROCEED}. So three blocks is as far as anything needs
 * to look — a fourth clear block cannot make the aspect any less restrictive.
 *
 * <p>Every block this walks over is Create's own. A block here is a {@code SignalEdgeGroup}, the
 * same thing Create's signals divide the track into and the same thing its trains reserve, so this
 * cannot disagree with what Create thinks is occupied. Nothing here controls a train: Create's
 * navigation brakes for its own {@code SignalBoundary}, and this only decides which lamps to light.
 * That is also why it is cheap to run this at a slower rate than the game ticks.
 */
public class AspectResolver {

	/**
	 * Blocks to look ahead. Three is not a tuning knob — it is where the aspect ladder tops out, so
	 * counting further would cost graph walks that could not change the answer.
	 */
	private static final int LOOKAHEAD = 3;

	/**
	 * @param restrictedInsteadOfApproach show 警戒 rather than 注意 with one block clear. Not
	 *        derivable from the count: real railways insert it where the block ahead is too short
	 *        to stop in from 注意 speed, which is a property of the layout rather than of traffic.
	 */
	public static SignalAspect resolve(TrackGraph graph, SignalBoundary signal, boolean side,
		boolean restrictedInsteadOfApproach) {
		return switch (countClearBlocks(graph, signal, side, LOOKAHEAD)) {
			case 0 -> SignalAspect.STOP;
			case 1 -> restrictedInsteadOfApproach ? SignalAspect.RESTRICTED : SignalAspect.APPROACH;
			case 2 -> SignalAspect.REDUCED_SPEED;
			default -> SignalAspect.PROCEED;
		};
	}

	/**
	 * How many consecutive blocks beyond this signal are clear, never more than {@code limit}.
	 *
	 * <p>Recursion is bounded by {@code limit} alone, which is what makes a loop of track safe to
	 * walk: a circular line chains back into itself, and depth rather than a visited set is what
	 * stops it.
	 */
	private static int countClearBlocks(TrackGraph graph, SignalBoundary signal, boolean side, int limit) {
		if (limit <= 0)
			return 0;
		if (isBlockOccupied(signal, side))
			return 0;

		Map<UUID, Boolean> ahead = SignalPropagator.collectChainedSignals(graph, signal, side);
		if (ahead.isEmpty())
			// Nothing further along to restrict this signal, so there is no reason to hold a train
			// back. Open line reads as clear rather than as unknown.
			return limit;

		// A junction hands back one entry per branch, and which one a train takes is not known
		// here. The most restrictive branch is the only safe answer.
		int beyond = limit - 1;
		for (Map.Entry<UUID, Boolean> entry : ahead.entrySet()) {
			SignalBoundary next = graph.getPoint(EdgePointType.SIGNAL, entry.getKey());
			if (next == null)
				continue;
			beyond = Math.min(beyond, countClearBlocks(graph, next, entry.getValue(), limit - 1));
		}
		return 1 + beyond;
	}

	/**
	 * Whether the block on the given side of a signal is occupied.
	 *
	 * <p>Anything unresolved counts as occupied. A signal whose group has not been worked out yet
	 * is a signal that does not know what is ahead of it, and the aspect that says so is 停止.
	 */
	private static boolean isBlockOccupied(SignalBoundary signal, boolean side) {
		if (signal.isForcedRed(side))
			return true;

		UUID groupId = signal.groups.get(side);
		if (groupId == null)
			return true;

		SignalEdgeGroup group = Create.RAILWAYS.signalEdgeGroups.get(groupId);
		if (group == null)
			return true;

		// isOccupiedUnless rather than a plain emptiness test, because it also follows the group's
		// intersections - a crossing whose other arm is occupied blocks this one too.
		return group.isOccupiedUnless(signal);
	}

	private AspectResolver() {}
}
