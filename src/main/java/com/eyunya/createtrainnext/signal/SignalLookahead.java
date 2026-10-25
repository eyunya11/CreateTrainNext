package com.eyunya.createtrainnext.signal;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.signal.NextSignalFinder.Ahead;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * What the next signal down the line is showing, for the blocks that only read it: the relay, which
 * repeats it to a driver, and the departure repeater, which tells a guard whether it has cleared.
 *
 * <p>Neither has any say over a train, and both find out where they stand the same way, by a piece of
 * track picked when they were placed ({@link Targeting}), and look ahead from there with
 * {@link NextSignalFinder}.
 */
public final class SignalLookahead {

	private SignalLookahead() {}

	/**
	 * The strictest aspect among the signals a train leaving the targeted point could meet next, or null
	 * when there is none to read: no track targeted, no graph there, or no signal ahead.
	 *
	 * <p>At a junction there is a signal down each branch and no way to know which one a train will meet.
	 * The most restrictive of them is the only one safe to promise, and since the aspects run from 停止
	 * upward, that is simply the lowest.
	 */
	@Nullable
	public static SignalAspect strictestAhead(Level level, TrackTargetingBehaviour<?> target) {
		if (!target.hasValidTrack())
			return null;

		TrackGraphLocation loc = target.determineGraphLocation();
		if (loc == null)
			return null;

		SignalAspect strictest = null;
		for (Ahead next : NextSignalFinder.find(loc.graph, loc))
			strictest = stricter(strictest, aspectOf(level, loc.graph, next));
		return strictest;
	}

	/** The stricter of two aspects, which is the lower; either may be null for none, and loses. */
	@Nullable
	public static SignalAspect stricter(@Nullable SignalAspect a, @Nullable SignalAspect b) {
		if (a == null)
			return b;
		if (b == null)
			return a;
		return b.ordinal() < a.ordinal() ? b : a;
	}

	/**
	 * What the signal ahead is showing.
	 *
	 * <p>Asked of the head itself where there is one, rather than worked out again from the track. A
	 * reader that recomputed would be a second opinion, and could disagree with the signal it is supposed
	 * to be reading — most easily over which ladder the head steps up, which is a fact about the type of
	 * head and not something any amount of looking at the track could reveal.
	 *
	 * <p>Falls back to resolving it only when the head cannot be read: an ordinary Create signal with no
	 * aspect of its own, or one whose chunk is not loaded. The fallback assumes a short ladder, one that
	 * can only ever make the reader more restrictive than a signal of this mod's, never less.
	 */
	@Nullable
	private static SignalAspect aspectOf(Level level, TrackGraph graph, Ahead ahead) {
		for (BlockPos pos : ahead.boundary().blockEntities.get(ahead.side())
			.keySet())
			if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof MultiAspectSignalBlockEntity head)
				return head.getAspect();

		return AspectResolver.resolve(graph, ahead.boundary(), ahead.side(), SignalAspect.Ladder.FOUR);
	}

	/**
	 * Targeting without ever becoming a point on the track.
	 *
	 * <p>{@link TrackTargetingBehaviour} normally registers an edge point on the graph the first time it
	 * ticks, which is what makes a station a station and a signal a signal. Refusing to is the whole of
	 * what keeps a reader out of the way: no point means nothing for a train to reserve, obey, brake for,
	 * or trip. Everything else the behaviour does — remembering which track was clicked and from which
	 * side, surviving being moved by a contraption, drawing the marker — is wanted and kept.
	 *
	 * <p>The type it is constructed with is inert, since the only thing that ever reads it is the
	 * creation being overridden here. Create's own SIGNAL is passed rather than a type of this mod's so
	 * that the placement check the item runs treats a reader like a signal for the purpose of not stacking
	 * two points on one spot; nothing else follows from it.
	 */
	public static class Targeting extends TrackTargetingBehaviour<SignalBoundary> {

		public Targeting(SmartBlockEntity be) {
			super(be, EdgePointType.SIGNAL);
		}

		@Override
		public SignalBoundary createEdgePoint() {
			return null;
		}
	}
}
