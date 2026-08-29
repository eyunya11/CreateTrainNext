package com.eyunya.createtrainnext.signal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import com.simibubi.create.content.trains.graph.EdgeData;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.SignalBoundary;

import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Iterate;

/**
 * Finds the first signal ahead of a point on the track.
 *
 * <p>Create has this for signals — {@code SignalPropagator.collectChainedSignals} — but only
 * starting from a signal, and a relay is deliberately not one. What is here is the same walk with
 * the same rules, started from a bare {@link TrackGraphLocation} instead, and it reads nothing it
 * does not own: no group is assigned, no train is notified, no point is added to the graph.
 *
 * <p>Two things about the frame it walks in, both worth writing down because getting either
 * backwards would point every relay the wrong way:
 *
 * <p>First, the direction is already in {@code loc.edge}. {@code TrackGraphHelper} builds it from
 * the targeted axis scaled by the target direction, so the couple's own order is the way the block
 * is looking: the walk always runs from the first node toward the second, and the target direction
 * itself is not consulted again. That falls out of Create's own arithmetic — a point placed facing
 * backwards has its edge stored swapped and its position measured from the other end, and those two
 * inversions cancel — so a relay set up the same way as a signal looks the same way that signal
 * looks.
 *
 * <p>Second, which of a found signal's two sides is the one facing away from us is
 * {@code !boundary.isPrimary(node)}, where {@code node} is the one we were standing on when we
 * found it. That is the side whose block lies beyond the signal, which is the side its own head
 * displays, and it is the same expression Create uses when it chains signals together.
 */
public class NextSignalFinder {

	/**
	 * A signal ahead, and which of its sides faces on down the line rather than back at us.
	 *
	 * @param side the index into the boundary's own two-sided state — groups, block entities,
	 *        cached states. Not a direction in the world.
	 */
	public record Ahead(SignalBoundary boundary, boolean side) {}

	/**
	 * Every signal reachable from {@code loc} without passing another one first.
	 *
	 * <p>More than one only at a junction, where the line divides before the next signal and which
	 * branch a train will take is not knowable from here. Returning all of them leaves that decision
	 * to the caller rather than silently picking a branch.
	 */
	public static List<Ahead> find(TrackGraph graph, TrackGraphLocation loc) {
		TrackNode node1 = graph.locateNode(loc.edge.getFirst());
		TrackNode node2 = graph.locateNode(loc.edge.getSecond());
		if (node1 == null || node2 == null)
			return List.of();

		TrackEdge startEdge = graph.getConnectionsFrom(node1)
			.get(node2);
		if (startEdge == null)
			return List.of();

		// A signal further along the very edge this stands on comes first, before anything the
		// graph walk could reach.
		SignalBoundary immediate = startEdge.getEdgeData()
			.next(EdgePointType.SIGNAL, loc.position);
		if (immediate != null)
			return List.of(new Ahead(immediate, !immediate.isPrimary(node1)));

		return walk(graph, node1, node2);
	}

	private static List<Ahead> walk(TrackGraph graph, TrackNode from, TrackNode to) {
		List<Ahead> found = new ArrayList<>();
		Set<TrackEdge> visited = new HashSet<>();
		List<Couple<TrackNode>> frontier = new ArrayList<>();
		frontier.add(Couple.create(to, from));

		while (!frontier.isEmpty()) {
			Couple<TrackNode> step = frontier.remove(0);
			TrackNode currentNode = step.getFirst();
			TrackNode prevNode = step.getSecond();

			for (Entry<TrackNode, TrackEdge> entry : graph.getConnectionsFrom(currentNode)
				.entrySet()) {
				TrackNode nextNode = entry.getKey();
				TrackEdge edge = entry.getValue();

				if (nextNode == prevNode)
					continue;
				// The visited set is what makes a loop of track safe to walk: a circular line comes
				// back to an edge it has already been down, and this is where it stops.
				if (!visited.add(edge))
					continue;

				// A turnout can be entered from one end and not the other. Without this the walk
				// would find signals down branches no train arriving this way could ever take.
				TrackEdge incoming = graph.getConnectionsFrom(prevNode)
					.get(currentNode);
				if (incoming == null || !incoming.canTravelTo(edge))
					continue;

				TrackEdge oppositeEdge = graph.getConnectionsFrom(nextNode)
					.get(currentNode);
				visited.add(oppositeEdge);

				boolean boundaryHere = false;
				for (boolean flip : Iterate.falseAndTrue) {
					// Our own orientation first, then the other one. A point is registered on both,
					// so the first pass is what normally finds it - and finds the nearest, since
					// distances on the far orientation are measured from the wrong end.
					TrackEdge currentEdge = flip ? oppositeEdge : edge;
					if (currentEdge == null)
						continue;
					EdgeData data = currentEdge.getEdgeData();
					if (!data.hasSignalBoundaries())
						continue;
					SignalBoundary next = data.next(EdgePointType.SIGNAL, 0);
					if (next == null)
						continue;
					found.add(new Ahead(next, !next.isPrimary(currentNode)));
					boundaryHere = true;
					break;
				}

				// Stop at the first signal down each branch. Anything past it is that signal's
				// business, not this one's.
				if (!boundaryHere)
					frontier.add(Couple.create(nextNode, currentNode));
			}
		}

		return found;
	}

	private NextSignalFinder() {}
}
