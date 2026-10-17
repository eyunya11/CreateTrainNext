package com.eyunya.createtrainnext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.eyunya.createtrainnext.track.AnchorIndex;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Development commands.
 *
 * <p>{@code /createtrainnext resetgraph} exists because Create persists the whole track graph to
 * the world (as {@code create_tracks}), and the graph records node <em>positions</em>. Change where
 * this mod puts its nodes — which happens whenever the anchor's docking logic is revised — and the
 * nodes written by the previous build are left behind at coordinates nothing generates any more.
 * {@code TrackPropagator} removes nodes by walking out from what a block currently reports, so it
 * cannot reach those, and they survive breaking and replacing the blocks. In game they show up as a
 * second set of edges that trains happily drive along.
 *
 * <p>The alternative is deleting {@code saves/<world>/data/create_tracks.dat} with the game shut
 * down, which is the same thing with more steps and no undo.
 */
public class CTNCommands {

	public static void init(IEventBus modEventBus) {
		// RegisterCommandsEvent is a game bus event, not a mod bus one.
		NeoForge.EVENT_BUS.addListener(CTNCommands::onRegisterCommands);
	}

	private static void onRegisterCommands(RegisterCommandsEvent event) {
		register(event.getDispatcher());
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal(CreateTrainNext.ID)
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("resetgraph")
				.executes(context -> resetGraph(context.getSource())))
			.then(Commands.literal("probe")
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
					.executes(context -> probe(context.getSource(),
						BlockPosArgument.getLoadedBlockPos(context, "pos")))))
			.then(Commands.literal("junction")
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
					.executes(context -> junction(context.getSource(),
						BlockPosArgument.getLoadedBlockPos(context, "pos"), 2))
					.then(Commands.argument("radius", IntegerArgumentType.integer(0, 8))
						.executes(context -> junction(context.getSource(),
							BlockPosArgument.getLoadedBlockPos(context, "pos"),
							IntegerArgumentType.getInteger(context, "radius"))))))
			.then(Commands.literal("reverse")
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
					.executes(context -> reverse(context.getSource(),
						BlockPosArgument.getLoadedBlockPos(context, "pos")))))
			.then(Commands.literal("curves")
				.executes(context -> curves(context.getSource(), 128))
				.then(Commands.argument("radius", IntegerArgumentType.integer(1, 1024))
					.executes(context -> curves(context.getSource(),
						IntegerArgumentType.getInteger(context, "radius"))))));
	}

	/**
	 * Lists the curves whose shape depends on which way they are read, worst first.
	 *
	 * <p>Create builds the graph's edge for each direction of travel from the same curve but not as
	 * the same curve, so one with unequal angles at its ends is drawn one way and driven another way
	 * the other way round — a train running against the route follows a path that can be blocks away
	 * from the rails, and the F3 graph shows whichever direction it happens to draw. See
	 * {@link CurvePlanner#MAX_ORIENTATION_DEVIATION}. This is for finding them.
	 */
	private static int curves(CommandSourceStack source, int radius) {
		ServerLevel level = source.getLevel();
		Vec3 centre = source.getPosition();

		record Link(BlockPos from, BlockPos to, double length, double deviation) {}

		List<Link> links = new ArrayList<>();
		for (AngledTrackAnchorBlockEntity anchor : AnchorIndex.in(level)) {
			BezierConnection curve = anchor.buildCurveToNext();
			if (curve == null || Vec3.atCenterOf(anchor.getBlockPos())
				.distanceTo(centre) > radius)
				continue;
			links.add(new Link(anchor.getBlockPos(), anchor.getNext(), curve.getLength(),
				CurvePlanner.orientationDeviation(curve)));
		}
		links.sort(Comparator.comparingDouble(Link::deviation)
			.reversed());

		double limit = CurvePlanner.MAX_ORIENTATION_DEVIATION;
		long over = links.stream()
			.filter(link -> link.deviation() > limit)
			.count();
		say(source, links.size() + " curves within " + radius + " blocks, " + over + " of them differ by more than "
			+ fmt(limit) + " blocks between the two directions");

		// Every one over the limit, up to a screenful; and if there are none, the worst three for scale.
		int shown = 0;
		for (Link link : links) {
			if (shown >= 15 || (link.deviation() <= limit && shown >= 3))
				break;
			say(source, (link.deviation() > limit ? " !! " : "    ") + link.from()
				.toShortString() + " -> "
				+ link.to()
					.toShortString()
				+ "   length " + fmt(link.length()) + "   deviation " + fmt(link.deviation()));
			shown++;
		}
		return links.size();
	}

	/**
	 * Turns the route an anchor belongs to end for end.
	 *
	 * <p>Here so that reversing a route can be tried on its own. The wand does the same thing when
	 * it joins two routes that were laid away from each other, and a fault in that is much easier to
	 * pin on the reversal or on the joining if the two can be run apart. Running it twice puts
	 * everything back.
	 */
	private static int reverse(CommandSourceStack source, BlockPos pos) {
		ServerLevel level = source.getLevel();
		if (!(level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor)) {
			source.sendFailure(Component.literal("Not an anchor: " + level.getBlockState(pos)
				.getBlock()));
			return 0;
		}

		List<AngledTrackAnchorBlockEntity> route = anchor.route();
		int turned = anchor.reverseRoute();
		if (route == null || turned == 0) {
			source.sendFailure(Component.literal("Part of this route is not loaded, or its links do not agree "
				+ "with one another. Nothing was changed."));
			return 0;
		}

		// The route has just been turned round, so its old last anchor is its new first.
		say(source, "Turned a route of " + turned + " anchors round. It now runs from "
			+ route.get(route.size() - 1)
				.getBlockPos()
				.toShortString()
			+ " to " + route.get(0)
				.getBlockPos()
				.toShortString() + ".");
		return turned;
	}

	/**
	 * Prints everything {@code TrackGraphHelper.getGraphLocationAt} looks at for one anchor, and the
	 * comparisons it makes, so a refusal to place a station or signal can be read off rather than
	 * guessed at. Both of its cases are covered: the node at the block's centre with the directions
	 * of the edges leaving it, and the block's linear query with the direction to each end.
	 */
	private static int probe(CommandSourceStack source, BlockPos pos) {
		ServerLevel level = source.getLevel();
		BlockState state = level.getBlockState(pos);

		if (!(state.getBlock() instanceof ITrackBlock track)) {
			source.sendFailure(Component.literal("Not a track block: " + state.getBlock()));
			return 0;
		}

		List<Vec3> axes = track.getTrackAxes(level, pos, state);
		Vec3 axis = axes.get(0);
		say(source, "reported axis = " + fmt(axis) + "   (" + axes.size() + " total)");

		if (level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor)
			say(source, "yaw = " + anchor.getYaw() + "  prev=" + (anchor.getPrev() != null) + " next="
				+ (anchor.getNext() != null) + "  flipped=" + anchor.isFlipped());

		// Case 1: is the block's centre a node, and does any edge leaving it match?
		Vec3 centre = Vec3.atBottomCenterOf(pos)
			.add(0, track.getElevationAtCenter(level, pos, state), 0);
		TrackNodeLocation centreLocation = new TrackNodeLocation(centre).in(level);
		TrackGraph graph = Create.RAILWAYS.sided(level)
			.getGraph(level, centreLocation);
		TrackNode node = graph == null ? null : graph.locateNode(centreLocation);

		say(source, "-- case 1: centre " + fmt(centre) + (node == null ? " is NOT a node" : " is a node")
			+ (graph == null ? "  [no graph here]" : ""));
		if (node != null) {
			var connections = graph.getConnectionsFrom(node);
			say(source, "   edges from centre: " + (connections == null ? "null" : connections.size()));
			if (connections != null)
				for (TrackEdge edge : connections.values()) {
					Vec3 direction = edge.getDirection(true);
					say(source, "   edge dir " + fmt(direction) + "  turn=" + edge.isTurn() + "  d2(+)="
						+ fmt(direction.distanceToSqr(axis)) + "  d2(-)="
						+ fmt(direction.distanceToSqr(axis.scale(-1))) + "   (needs < " + fmt(1 / 4096f) + ")");
				}
		}

		// Where do the edges actually live? Check every position this anchor might have put a node
		// at, plus its neighbours' centres, and say which of them the graph knows about.
		say(source, "-- node census");
		if (level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity a) {
			censusAt(source, level, "centre", centre);
			censusAt(source, level, "entryNode", a.entryNode());
			censusAt(source, level, "exitNode", a.exitNode());
			for (BlockPos neighbour : new BlockPos[] { a.getPrev(), a.getNext() })
				if (neighbour != null)
					censusAt(source, level, "neighbour " + neighbour, Vec3.atBottomCenterOf(neighbour));
		}

		// Case 2: the linear query, and the direction from the centre out to each end it reports.
		say(source, "-- case 2: linear ends");
		for (DiscoveredLocation end : track.getConnected(level, pos, state, true, null)) {
			Vec3 toEnd = end.getLocation()
				.subtract(centre);
			if (toEnd.lengthSqr() < 1.0E-9) {
				say(source, "   end " + fmt(end.getLocation()) + " is the centre itself");
				continue;
			}
			Vec3 unit = toEnd.normalize();
			say(source, "   end " + fmt(end.getLocation()) + "  dir " + fmt(unit) + "  d2(+)="
				+ fmt(unit.distanceToSqr(axis.normalize())) + "  d2(-)="
				+ fmt(unit.distanceToSqr(axis.normalize()
					.scale(-1))));
		}

		return 1;
	}

	/**
	 * Prints the track graph around a position, and every decision a train makes crossing it.
	 *
	 * <p>For when a train stops with "reached the end of its track" and the track plainly carries
	 * on. That message means one thing: standing on some edge, arriving at some node, Create found
	 * no edge leaving it that it was willing to turn onto — either because there is no edge, or
	 * because {@code TrackEdge.canTravelTo} refused the turn. Those are very different faults with
	 * the same symptom, and nothing in game distinguishes them.
	 *
	 * <p>So this prints both halves. First what the blocks say: every track block in range and the
	 * node locations it reports, with whether the graph actually holds a node there — a block
	 * reporting a location the graph does not have is a graph that never got built. Then what the
	 * graph says: every edge at each node, and for each way through it the dot product Create
	 * compares against 7/8, marked OK or BLOCKED.
	 */
	private static int junction(CommandSourceStack source, BlockPos pos, int radius) {
		ServerLevel level = source.getLevel();

		say(source, "== blocks around " + pos.toShortString() + " (radius " + radius + ")");
		Set<TrackNodeLocation> reported = new LinkedHashSet<>();

		for (BlockPos cursor : BlockPos.betweenClosed(pos.offset(-radius, -radius, -radius),
			pos.offset(radius, radius, radius))) {
			BlockState state = level.getBlockState(cursor);
			if (!(state.getBlock() instanceof ITrackBlock track))
				continue;

			BlockPos at = cursor.immutable();
			say(source, " " + at.toShortString() + "  " + state);

			for (DiscoveredLocation location : track.getConnected(level, at, state, false, null)) {
				reported.add(location);
				say(source, "    reports " + fmt(location.getLocation()) + "  turn="
					+ (location.getTurn() != null) + "  force=" + location.shouldForceNode() + "  dir="
					+ (location.getDirection() == null ? "none" : fmt(location.getDirection())) + "  -> "
					+ nodeSummary(level, location));
			}
		}

		say(source, "== ways through each node");
		for (TrackNodeLocation location : reported) {
			TrackGraph graph = Create.RAILWAYS.sided(level)
				.getGraph(level, location);
			TrackNode node = graph == null ? null : graph.locateNode(location);
			if (node == null)
				continue;

			Map<TrackNode, TrackEdge> leaving = graph.getConnectionsFrom(node);
			say(source, " " + fmt(location.getLocation()) + "  graph#" + System.identityHashCode(graph) + "  "
				+ leaving.size() + " edge(s)");

			for (Map.Entry<TrackNode, TrackEdge> arrivingFrom : leaving.entrySet()) {
				// The edge coming in is the far node's edge back to this one, which is the one
				// canTravelTo is asked about — not the one leaving towards it.
				TrackEdge arriving = graph.getConnectionsFrom(arrivingFrom.getKey())
					.get(node);
				if (arriving == null) {
					say(source, "    ONE WAY: no edge back from " + fmt(arrivingFrom.getKey()
						.getLocation()
						.getLocation()));
					continue;
				}

				for (Map.Entry<TrackNode, TrackEdge> onwards : leaving.entrySet()) {
					if (onwards.getKey() == arrivingFrom.getKey())
						continue;
					double dot = arriving.getDirection(false)
						.dot(onwards.getValue()
							.getDirection(true));
					say(source, "    " + fmt(arrivingFrom.getKey()
						.getLocation()
						.getLocation()) + " -> here -> "
						+ fmt(onwards.getKey()
							.getLocation()
							.getLocation())
						+ "   dot=" + fmt(dot) + (dot > 7 / 8f ? "  OK" : "  BLOCKED (needs > 0.875)")
						+ "   [in turn=" + arriving.isTurn() + " len=" + fmt(arriving.getLength())
						+ ", out turn=" + onwards.getValue()
							.isTurn()
						+ " len=" + fmt(onwards.getValue()
							.getLength())
						+ "]");
				}
			}
		}

		return 1;
	}

	private static String nodeSummary(ServerLevel level, TrackNodeLocation location) {
		TrackGraph graph = Create.RAILWAYS.sided(level)
			.getGraph(level, location);
		if (graph == null)
			return "NO GRAPH";
		TrackNode node = graph.locateNode(location);
		if (node == null)
			return "NO NODE";
		return graph.getConnectionsFrom(node)
			.size() + " edge(s) in graph#" + System.identityHashCode(graph);
	}

	/** Reports whether the graph has a node at one position, and how many edges leave it. */
	private static void censusAt(CommandSourceStack source, ServerLevel level, String label, Vec3 at) {
		TrackNodeLocation location = new TrackNodeLocation(at).in(level);
		TrackGraph graph = Create.RAILWAYS.sided(level)
			.getGraph(level, location);
		TrackNode node = graph == null ? null : graph.locateNode(location);
		int edges = node == null ? -1 : graph.getConnectionsFrom(node)
			.size();
		// Graph identity matters as much as the counts: an isolated node with a graph of its own
		// means the network fragmented rather than that an edge simply went missing.
		String where = graph == null ? "no graph"
			: node == null ? "no node" : edges + " edge(s) in graph#" + System.identityHashCode(graph);
		say(source, "   " + label + " " + fmt(at) + " -> " + where);

		// Which blocks does the propagator ask about this location? A node on a block boundary is
		// shared by the blocks either side; one at a centre may reach only its own.
		if (node != null)
			say(source, "      allAdjacent -> " + location.allAdjacent());
	}

	private static void say(CommandSourceStack source, String line) {
		source.sendSuccess(() -> Component.literal(line), false);
		CreateTrainNext.LOGGER.info("[CTN-PROBE] {}", line);
	}

	private static String fmt(Vec3 v) {
		return String.format("(%.4f, %.4f, %.4f)", v.x, v.y, v.z);
	}

	private static String fmt(double d) {
		return String.format("%.6f", d);
	}

	private static int resetGraph(CommandSourceStack source) {
		GlobalRailwayManager railways = Create.RAILWAYS;

		// Trains hold onto their graph, so they have to go first or they are left pointing at
		// something that no longer exists.
		List<UUID> trains = new ArrayList<>(railways.trains.keySet());
		for (UUID id : trains)
			railways.removeTrain(id);

		List<TrackGraph> graphs = new ArrayList<>(railways.trackNetworks.values());
		for (TrackGraph graph : graphs) {
			railways.sync.graphRemoved(graph);
			railways.removeGraphAndGroup(graph);
		}
		railways.trackNetworks.clear();
		railways.markTracksDirty();

		int graphCount = graphs.size();
		int trainCount = trains.size();
		source.sendSuccess(() -> Component.literal("Cleared " + graphCount + " track graph(s) and "
			+ trainCount + " train(s). Nothing rebuilds on its own: break and replace one block of "
			+ "each track network, and re-link the anchors, or they will silently have no edges."),
			true);
		return graphCount;
	}
}
