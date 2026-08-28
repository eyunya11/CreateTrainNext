package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.signal.SignalAspect.LampColour;

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

	/**
	 * The signal's lamps, one model per state a socket can be in.
	 *
	 * <p>Four models rather than twenty: every socket is drawn from the same lamp, moved up or down
	 * the housing by the renderer, so adding or removing a lamp from the column costs no assets at
	 * all. The housing itself is a blockstate model and is not here.
	 */
	public static final PartialModel SIGNAL_LAMP_OFF = signalLamp("light_blank");

	public static final PartialModel SIGNAL_LAMP_RED = signalLamp("light_lit_red");

	public static final PartialModel SIGNAL_LAMP_YELLOW = signalLamp("light_lit_yellow");

	public static final PartialModel SIGNAL_LAMP_GREEN = signalLamp("light_lit_green");

	/**
	 * The halo drawn over a lit lamp, one square for all five sockets and all three colours.
	 *
	 * <p>White, and tinted per colour by the renderer, because a halo is the same shape whatever
	 * colour it is; three near-identical textures would only be three chances for one of them to
	 * drift.
	 */
	public static final PartialModel SIGNAL_LAMP_GLOW = signalLamp("light_glow");

	private static PartialModel signalLamp(String name) {
		return PartialModel.of(CreateTrainNext.asResource("block/multi_aspect_signal/" + name));
	}

	/** The lit lamp for a colour. Kept beside the fields so a new colour cannot be half-added. */
	public static PartialModel litLamp(LampColour colour) {
		return switch (colour) {
			case RED -> SIGNAL_LAMP_RED;
			case YELLOW -> SIGNAL_LAMP_YELLOW;
			case GREEN -> SIGNAL_LAMP_GREEN;
		};
	}

	public static void init(IEventBus modEventBus, ModContainer modContainer) {
		modContainer.registerConfig(ModConfig.Type.CLIENT, CTNConfig.CLIENT_SPEC);
		modEventBus.addListener(CreateTrainNextClient::onRegisterRenderers);
	}

	private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(CTNRegistry.ANGLED_TRACK_ANCHOR_ENTITY.get(),
			AngledTrackAnchorRenderer::new);
		event.registerBlockEntityRenderer(CTNRegistry.TRACK_SPEED_LIMIT_ENTITY.get(), SpeedLimitRenderer::new);
		event.registerBlockEntityRenderer(CTNRegistry.MULTI_ASPECT_SIGNAL_ENTITY.get(),
			MultiAspectSignalRenderer::new);
	}
}
