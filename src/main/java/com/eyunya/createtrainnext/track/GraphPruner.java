package com.eyunya.createtrainnext.track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphSync;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.ITrackBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Takes nodes out of Create's track graph that no track block reports.
 *
 * <p>Create keeps the graph in step with the world by walking out from what blocks report: placing a
 * block rebuilds everything reachable from the locations it reports, and removing one removes the
 * nodes at the locations it reported. A node at a location that <em>nothing</em> reports is outside
 * both. Nothing walks to it, so nothing ever takes it out, and it sits in the graph as an island of
 * its own for as long as the world is kept — a coloured post in the F3 view where an anchor used to
 * be, and a graph of its own that has trains and signals for company they cannot reach.
 *
 * <p>An anchor leaves some every time what it reports moves. The nodes of an end are not the nodes of
 * an anchor in the middle of a route, and a curve's ends are wherever its anchors put them, so
 * carrying a route on, joining two, unlinking an anchor and breaking one all change where the
 * graph's nodes should be. The teardown in front of those changes ends by rebuilding the neighbours
 * from what they report <em>then</em>, which puts the old nodes straight back, and the rebuild
 * afterwards only deals in what is reported by then. So the old locations are noted before the
 * change and pruned after it: see {@link AngledTrackAnchorBlockEntity#linkTo}.
 *
 * <p>A node is only taken out when a track block next to it can be seen not to report it. If any of
 * the blocks that could report it is in a chunk that is not loaded, the node is left alone, since
 * the answer is not known.
 */
public final class GraphPruner {

	private GraphPruner() {}

	/** What a pass looked at, and what it did about it. */
	public record Result(int examined, int removed, int undecided) {

		static final Result NONE = new Result(0, 0, 0);
	}

	/**
	 * Every graph location the block at {@code pos} reports, which is where its nodes may be.
	 *
	 * <p>The state is passed in rather than read from the world, because an anchor that is being
	 * broken is already gone from the world by the time it is asked, and what it reported is exactly
	 * what has to be remembered.
	 */
	public static Set<TrackNodeLocation> reportedBy(Level level, BlockPos pos, BlockState state) {
		Set<TrackNodeLocation> reported = new LinkedHashSet<>();
		if (state.getBlock() instanceof ITrackBlock track)
			reported.addAll(track.getConnected(level, pos, state, false, null));
		return reported;
	}

	/**
	 * Removes every one of these locations from the graph that no track block reports now.
	 *
	 * <p>Server side only. Locations that are not nodes of any graph are skipped, so it is safe to
	 * hand it everything a block used to report.
	 */
	public static Result prune(Level level, Collection<? extends TrackNodeLocation> candidates) {
		if (level.isClientSide || candidates.isEmpty())
			return Result.NONE;
		return removeUnreported(level, candidates);
	}

	/**
	 * Does the same for every node within {@code radius} blocks of {@code centre}, which is how nodes
	 * that were left behind before they were prevented are cleaned out of an existing world.
	 */
	public static Result pruneAround(Level level, Vec3 centre, double radius) {
		if (level.isClientSide)
			return Result.NONE;

		List<TrackNodeLocation> nearby = new ArrayList<>();
		for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values())
			for (TrackNodeLocation location : graph.getNodes())
				if (level.dimension()
					.equals(location.getDimension())
					&& location.getLocation()
						.distanceTo(centre) <= radius)
					nearby.add(location);

		return removeUnreported(level, nearby);
	}

	private static Result removeUnreported(Level level, Collection<? extends TrackNodeLocation> candidates) {
		GlobalRailwayManager manager = Create.RAILWAYS;
		TrackGraphSync sync = manager.sync;

		int examined = 0;
		int removed = 0;
		int undecided = 0;
		Set<TrackGraph> touched = new HashSet<>();
		Set<TrackNodeLocation> seen = new HashSet<>();

		for (TrackNodeLocation location : candidates) {
			if (!seen.add(location))
				continue;

			List<TrackGraph> graphs = manager.getGraphs(level, location);
			if (graphs.isEmpty())
				continue;

			examined++;
			Reported reported = reportedNextTo(level, location);
			if (reported == Reported.UNKNOWN)
				undecided++;
			if (reported != Reported.NO)
				continue;

			for (TrackGraph graph : graphs) {
				TrackNode node = graph.locateNode(location);
				if (node == null)
					continue;

				graph.removeNode(level, location);
				sync.nodeRemoved(graph, node);
				removed++;

				if (graph.isEmpty()) {
					manager.removeGraphAndGroup(graph);
					sync.graphRemoved(graph);
					touched.remove(graph);
				} else
					touched.add(graph);
			}
		}

		// A node can be all that joined two stretches of track, and a graph in two pieces is two
		// graphs: the same last step Create takes when it removes a block.
		for (TrackGraph graph : touched)
			manager.updateSplitGraph(level, graph);

		if (removed > 0)
			manager.markTracksDirty();
		return new Result(examined, removed, undecided);
	}

	private enum Reported {
		YES, NO, UNKNOWN
	}

	/**
	 * Whether a track block beside this location reports it. Those are the blocks Create asks when it
	 * walks to a location, so a location none of them reports is one no walk can reach.
	 */
	private static Reported reportedNextTo(Level level, TrackNodeLocation location) {
		boolean unknown = false;
		for (BlockPos pos : location.allAdjacent()) {
			if (!level.isLoaded(pos)) {
				unknown = true;
				continue;
			}

			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof ITrackBlock track && track.getConnected(level, pos, state, false, null)
				.contains(location))
				return Reported.YES;
		}
		return unknown ? Reported.UNKNOWN : Reported.NO;
	}
}
