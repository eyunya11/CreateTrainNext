package com.eyunya.createtrainnext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.graph.TrackGraph;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

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
				.executes(context -> resetGraph(context.getSource()))));
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
			+ trainCount + " train(s). Break and replace one block of each track network to rebuild it, "
			+ "then re-link the anchors. Rejoin the world if the F3 overlay still shows old edges."), true);
		return graphCount;
	}
}
