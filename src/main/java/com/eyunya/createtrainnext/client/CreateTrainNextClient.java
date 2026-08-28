package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

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
	}

	private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(CTNRegistry.ANGLED_TRACK_ANCHOR_ENTITY.get(),
			AngledTrackAnchorRenderer::new);
		event.registerBlockEntityRenderer(CTNRegistry.TRACK_SPEED_LIMIT_ENTITY.get(), SpeedLimitRenderer::new);
	}
}
