package com.eyunya.createtrainnext.client;

import java.util.Map;

import com.eyunya.createtrainnext.track.AnchorIndex;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlockOutline;
import com.simibubi.create.content.trains.track.TrackBlockOutline.BezierPointSelection;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Lets the player pick a curve to put a signal or a station on, and only then.
 *
 * <p>Create picks curves every frame, from every {@code TrackBlockEntity} it has been told about,
 * and a curve under the crosshair replaces whatever block the player was looking at with a miss.
 * That is right with a signal in hand and wrong with anything else here: the wand reads the block it
 * is aimed at for the ghost and for every click, and a curve between the player and that block would
 * take it away. So the anchors are registered with Create's list of curves to pick from while the
 * main hand holds a {@link TrackTargetingBlockItem} — the signals, stations and observers of Create
 * and this mod's own — and are taken off it again when it does not.
 *
 * <p>The list is Create's {@code TrackBlockOutline.TRACKS_WITH_TURNS}. An anchor puts itself on or
 * off it whenever its data arrives, going by {@link AngledTrackAnchorBlockEntity#setCurveInteraction},
 * and this is what sets that and brings the ones already loaded into line when the hand changes.
 *
 * <p>Curves can be broken from the same place: holding the attack key over a picked one starts
 * taking it apart. An anchor's curve is its links, which are undone with the wand and not by
 * punching, so that is stopped here before it begins.
 */
public final class CurveTargeting {

	private static boolean offered = false;

	private CurveTargeting() {}

	public static void init() {
		// Ahead of Create's own tick handler, which reads the pick this can take back.
		NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, CurveTargeting::onClientTick);
	}

	private static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;

		boolean wanted = level != null && player != null
			&& player.getMainHandItem()
				.getItem() instanceof TrackTargetingBlockItem;

		if (wanted != offered) {
			offered = wanted;
			AngledTrackAnchorBlockEntity.setCurveInteraction(wanted);
			if (level != null)
				bringLoadedAnchorsInto(level, wanted);
		}

		if (wanted && mc.options.keyAttack.isDown()) {
			BezierPointSelection picked = TrackBlockOutline.result;
			if (picked != null && picked.blockEntity() instanceof AngledTrackAnchorBlockEntity)
				TrackBlockOutline.result = null;
		}
	}

	private static void bringLoadedAnchorsInto(ClientLevel level, boolean offer) {
		Map<BlockPos, TrackBlockEntity> pickable = TrackBlockOutline.TRACKS_WITH_TURNS.get(level);
		for (AngledTrackAnchorBlockEntity anchor : AnchorIndex.in(level)) {
			if (offer && anchor.hasCurves())
				pickable.put(anchor.getBlockPos(), anchor);
			else
				pickable.remove(anchor.getBlockPos());
		}
	}
}
