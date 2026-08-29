package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.SurveyMarkerBlockEntity;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Client-only setup.
 *
 * <p>Registered explicitly from the mod constructor rather than through
 * {@code @EventBusSubscriber}: that annotation's {@code bus} attribute is deprecated for
 * removal, and its default is the game bus, while {@link EntityRenderersEvent.RegisterRenderers}
 * is fired on the mod bus.
 *
 * <p>The cut-out render layer the track model needs is declared in the block models themselves
 * via {@code render_type}, rather than through the deprecated
 * {@code ItemBlockRenderTypes.setRenderLayer}.
 */
public class CreateTrainNextClient {

	/**
	 * The sign's geometry, which no blockstate points at — {@code SpeedLimitRenderer} draws it so
	 * it can be turned 22.5 degrees at a time.
	 *
	 * <p>Creating the PartialModel is all the registration there is; Minecraft loads whatever has
	 * been asked for by the time baking starts, and this class is touched from the mod
	 * constructor. It has to be held in a field: PartialModel's own registry keeps weak values,
	 * so one left unreferenced would be collected before it was ever baked.
	 */
	public static final PartialModel SPEED_LIMIT_SIGN =
		PartialModel.of(CreateTrainNext.asResource("block/track_speed_limit"));

	public static void init(IEventBus modEventBus, ModContainer modContainer) {
		modContainer.registerConfig(ModConfig.Type.CLIENT, CTNConfig.CLIENT_SPEC);
		modEventBus.addListener(CreateTrainNextClient::onRegisterRenderers);
		CurveWandPreview.init();
		SurveyMarkerPreview.init();
		NeoForge.EVENT_BUS.addListener(CreateTrainNextClient::onRightClickBlock);
	}

	/**
	 * Opens a survey marker's screen when it is right clicked with nothing in hand.
	 *
	 * <p>Handled here rather than from the block so that no client class is named anywhere the
	 * server can reach. Empty hand only: a wrench belongs to the scroll box on the block and the
	 * curve wand lays the line, and neither should be interrupted by a dialog.
	 */
	private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		if (!event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND)
			return;
		if (!event.getItemStack()
			.isEmpty() || event.getEntity()
				.isShiftKeyDown())
			return;
		if (!(event.getLevel()
			.getBlockEntity(event.getPos()) instanceof SurveyMarkerBlockEntity marker))
			return;

		Minecraft.getInstance()
			.setScreen(new SurveyMarkerScreen(event.getPos(), marker.getBearing(), marker.getLength()));
		event.setCanceled(true);
	}

	private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(CTNRegistry.ANGLED_TRACK_ANCHOR_ENTITY.get(),
			AngledTrackAnchorRenderer::new);
		event.registerBlockEntityRenderer(CTNRegistry.TRACK_SPEED_LIMIT_ENTITY.get(), SpeedLimitRenderer::new);
	}
}
