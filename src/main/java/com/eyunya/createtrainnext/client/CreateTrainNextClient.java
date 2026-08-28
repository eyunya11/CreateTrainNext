package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.signal.SignalAspect.LampColour;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
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

	/**
	 * The relay's lamps, two models for seven sockets.
	 *
	 * <p>Two rather than the signal's four because a relay has no colours: every lamp on it is the
	 * same white, and what it says is said by which three of the seven are lit. The halo above is
	 * shared with the signal, tinted white where it is used here.
	 */
	public static final PartialModel RELAY_LAMP_OFF = relayLamp("light_blank");

	public static final PartialModel RELAY_LAMP_LIT = relayLamp("light_lit");

	private static PartialModel signalLamp(String name) {
		return PartialModel.of(CreateTrainNext.asResource("block/multi_aspect_signal/" + name));
	}

	private static PartialModel relayLamp(String name) {
		return PartialModel.of(CreateTrainNext.asResource("block/relay_signal/" + name));
	}

	/** The lit lamp for a colour. Kept beside the fields so a new colour cannot be half-added. */
	public static PartialModel litLamp(LampColour colour) {
		return switch (colour) {
			case RED -> SIGNAL_LAMP_RED;
			case YELLOW -> SIGNAL_LAMP_YELLOW;
			case GREEN -> SIGNAL_LAMP_GREEN;
		};
	}

	/**
	 * How far away a signal's lamps are still drawn, in blocks.
	 *
	 * <p>Minecraft draws block entities out to 64 blocks and no further, which for a signal means
	 * that four chunks away the housing carries on being drawn — it is part of the terrain — while
	 * the lamps on it stop. An unlit signal is not a signal that is hard to read, it is one that is
	 * telling you something untrue, so this is the one default worth overriding outright.
	 */
	public static int signalViewDistance() {
		return viewDistance(CTNConfig.CLIENT.signalViewDistance.get());
	}

	/**
	 * How far away a speed limit sign is still drawn, in blocks.
	 *
	 * <p>The same 64 block default, and a worse consequence of it than the signal has. A sign's
	 * block model is empty — the plate is drawn by its renderer so it can be turned 22.5 degrees at
	 * a time — so there is no terrain left behind when the renderer stops. The sign does not go
	 * blank at four chunks, it goes away.
	 */
	public static int signViewDistance() {
		return viewDistance(CTNConfig.CLIENT.signViewDistance.get());
	}

	/**
	 * Resolves a configured view distance, where zero asks for as far as there is anything to draw.
	 *
	 * <p>Read every frame per block entity, which is why it is arithmetic on an option rather than
	 * anything that has to look at the world. The render distance is the real ceiling either way:
	 * past it the chunk is not loaded on this client, so there is no block entity to draw whatever
	 * this returns.
	 */
	private static int viewDistance(int configured) {
		return configured > 0 ? configured : Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
	}

	/**
	 * Whether the camera is within {@code blocks} of a block entity.
	 *
	 * <p>For the parts of a renderer that want a shorter leash than the renderer itself has. Off
	 * the camera rather than the player, so it stays right in third person and while spectating.
	 */
	public static boolean isWithin(BlockEntity blockEntity, int blocks) {
		return Minecraft.getInstance()
			.gameRenderer.getMainCamera()
			.getPosition()
			.closerThan(Vec3.atCenterOf(blockEntity.getBlockPos()), blocks);
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
		event.registerBlockEntityRenderer(CTNRegistry.RELAY_SIGNAL_ENTITY.get(), RelaySignalRenderer::new);
	}
}
