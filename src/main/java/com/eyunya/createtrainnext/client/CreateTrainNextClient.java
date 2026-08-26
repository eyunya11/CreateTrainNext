package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.CTNRegistry;

import net.neoforged.bus.api.IEventBus;
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

	public static void init(IEventBus modEventBus) {
		modEventBus.addListener(CreateTrainNextClient::onRegisterRenderers);
	}

	private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(CTNRegistry.ANGLED_TRACK_ANCHOR_ENTITY.get(),
			AngledTrackAnchorRenderer::new);
	}
}
