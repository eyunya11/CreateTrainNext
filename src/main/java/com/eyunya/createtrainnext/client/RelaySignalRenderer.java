package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.signal.RelayIndication;
import com.eyunya.createtrainnext.signal.RelaySignalBlock;
import com.eyunya.createtrainnext.signal.RelaySignalBlockEntity;
import com.eyunya.createtrainnext.signal.RelaySocket;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour.RenderedTrackOverlayType;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the relay's seven lamps, and the marker on the track it reads.
 *
 * <p>Built the same way as {@link MultiAspectSignalRenderer} and for the same reasons: the housing
 * never changes so it stays a blockstate model, and the lamps do, so they are drawn here from one
 * model moved to each socket. Only the socket layout differs — a bank of seven across and up rather
 * than a column of five, so the offset has an x as well as a y.
 *
 * <p>Every lamp is the same white. The relay says what it has to say with the angle of the lit bar,
 * not with colour, which is exactly what makes it worth reading at the distance it is meant to be
 * read from — and why the halo matters more here than on the signal itself. See that renderer for
 * what the halo is for.
 */
public class RelaySignalRenderer extends SmartBlockEntityRenderer<RelaySignalBlockEntity> {

	/** Centre of the socket the lamp models are authored at, the point everything here scales about. */
	private static final float LAMP_CENTRE_X = 8F / 16F;

	private static final float LAMP_CENTRE_Y = 5.375F / 16F;

	private static final float LAMP_CENTRE_Z = 10.0125F / 16F;

	/** As on the five-aspect head: enough to clear the bezel ringing each socket, which ends at 10.05. */
	private static final float BEZEL_CLEARANCE = 0.1F / 16F;

	/**
	 * In front of the bezel and of an enlarged lamp, and still behind the hood over each socket.
	 * Safe to be in front only because {@link CTNRenderTypes#glow()} does not write depth.
	 */
	private static final float GLOW_Z = 10.25F / 16F;

	/** The lamps are white; the halo is the colour the lit lens texture is filled with. */
	private static final int GLOW_TINT = 0xF1EDC1;

	public RelaySignalRenderer(Context context) {
		super(context);
	}

	@Override
	protected void renderSafe(RelaySignalBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
		int light, int overlay) {
		super.renderSafe(be, partialTicks, ms, buffer, light, overlay);

		Level level = be.getLevel();
		if (level == null)
			return;

		renderLamps(be, be.getBlockState(), ms, buffer, light);
		renderTrackMarker(be, level, ms, buffer, light, overlay);
	}

	private void renderLamps(RelaySignalBlockEntity be, BlockState state, PoseStack ms, MultiBufferSource buffer,
		int light) {
		if (!(state.getBlock() instanceof RelaySignalBlock))
			return;

		RelayIndication indication = be.getIndication();
		float yaw = RelaySignalBlock.modelYaw(state);
		float litScale = CTNConfig.CLIENT.signalLitLampScale.get()
			.floatValue();
		boolean glow = CTNConfig.CLIENT.signalGlow.get();

		for (RelaySocket socket : RelaySocket.values()) {
			boolean lit = indication.isLit(socket);
			PartialModel model = lit ? CreateTrainNextClient.RELAY_LAMP_LIT : CreateTrainNextClient.RELAY_LAMP_OFF;

			float scale = lit ? litScale : 1F;
			float forward = scale > 1F ? BEZEL_CLEARANCE : 0F;

			CachedBuffers.partial(model, state)
				// Negated because a blockstate variant's y turns the model the opposite way round
				// from this, and modelYaw is measured in the blockstate's sense so the housing and
				// the lamps agree.
				.rotateCenteredDegrees(-yaw, Direction.Axis.Y)
				// Issued after the rotation, so - per Flywheel's Affine, which composes each call
				// inside the ones before it - this runs first, in the same south-authored local
				// space the housing was drawn in, and the rotation above carries it round with the
				// housing rather than along already-turned axes.
				.translate(socket.localX(), socket.localY(), forward)
				// Scaling happens about the model origin, which is the block's corner, so the lamp
				// has to be walked to the origin and back around it.
				.translate(LAMP_CENTRE_X, LAMP_CENTRE_Y, LAMP_CENTRE_Z)
				.scale(scale)
				.translate(-LAMP_CENTRE_X, -LAMP_CENTRE_Y, -LAMP_CENTRE_Z)
				.light(lit ? LightTexture.FULL_BLOCK : light)
				.renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));

			if (lit && glow)
				renderGlow(state, socket, yaw, ms, buffer);
		}
	}

	private void renderGlow(BlockState state, RelaySocket socket, float yaw, PoseStack ms, MultiBufferSource buffer) {
		float brightness = CTNConfig.CLIENT.signalGlowBrightness.get()
			.floatValue();
		float size = CTNConfig.CLIENT.signalGlowSize.get()
			.floatValue();

		int red = Math.round(((GLOW_TINT >> 16) & 0xFF) * brightness);
		int green = Math.round(((GLOW_TINT >> 8) & 0xFF) * brightness);
		int blue = Math.round((GLOW_TINT & 0xFF) * brightness);

		CachedBuffers.partial(CreateTrainNextClient.SIGNAL_LAMP_GLOW, state)
			.rotateCenteredDegrees(-yaw, Direction.Axis.Y)
			.translate(socket.localX(), socket.localY(), 0)
			// The halo model is one pixel square and centred on the model origin, so scaling it
			// there makes the factor its width in pixels, and the translate that follows carries it
			// onto the socket.
			.translate(LAMP_CENTRE_X, LAMP_CENTRE_Y, GLOW_Z)
			.scale(size)
			.light(LightTexture.FULL_BRIGHT)
			.color(red, green, blue, 0xFF)
			.disableDiffuse()
			.renderInto(ms, buffer.getBuffer(CTNRenderTypes.glow()));
	}

	private void renderTrackMarker(RelaySignalBlockEntity be, Level level, PoseStack ms, MultiBufferSource buffer,
		int light, int overlay) {
		TrackTargetingBehaviour<?> target = be.target;
		BlockPos targetPosition = target.getGlobalPosition();
		BlockState trackState = level.getBlockState(targetPosition);

		// The target is a remembered position: the track under it can have been mined since.
		if (!(trackState.getBlock() instanceof ITrackBlock))
			return;

		ms.pushPose();
		TransformStack.of(ms)
			.translate(targetPosition.subtract(be.getBlockPos()));
		TrackTargetingBehaviour.render(level, targetPosition, target.getTargetDirection(), target.getTargetBezier(), ms,
			buffer, light, overlay, RenderedTrackOverlayType.SIGNAL, 1);
		ms.popPose();
	}
}
