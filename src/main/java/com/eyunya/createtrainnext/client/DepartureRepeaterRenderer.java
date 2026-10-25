package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.signal.DepartureRepeaterBlock;
import com.eyunya.createtrainnext.signal.DepartureRepeaterBlockEntity;
import com.eyunya.createtrainnext.signal.SignalRotation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour.RenderedTrackOverlayType;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;

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
 * Draws the departure repeater's lamp, its lit end when the signal ahead has cleared, and the marker on
 * the track it reads.
 *
 * <p>Built as {@link RelaySignalRenderer} is: the pole is the blockstate's, the head turns here, and a lit
 * lamp is drawn at full brightness with the same halo the signals have, tinted white.
 */
public class DepartureRepeaterRenderer extends SmartBlockEntityRenderer<DepartureRepeaterBlockEntity> {

	/** The middle of the lit end, in blocks: the middle of {@link DepartureRepeaterBlock#HEAD_BOUNDS} across, at its front. */
	private static final float FACE_X = (float) ((DepartureRepeaterBlock.HEAD_BOUNDS[0]
		+ DepartureRepeaterBlock.HEAD_BOUNDS[3]) / 32);

	private static final float FACE_Y = (float) ((DepartureRepeaterBlock.HEAD_BOUNDS[1]
		+ DepartureRepeaterBlock.HEAD_BOUNDS[4]) / 32);

	/** Just in front of the lit end, so the halo is not lost in it. */
	private static final float GLOW_Z = (float) ((DepartureRepeaterBlock.HEAD_BOUNDS[5] + 0.25) / 16);

	/** White, as a JR lamp of this kind is when lit. */
	private static final int GLOW_TINT = 0xF1EDC1;

	/** As the relay's: the marker is for setting up, looked at from close by. */
	private static final int MARKER_VIEW_DISTANCE = 64;

	public DepartureRepeaterRenderer(Context context) {
		super(context);
	}

	@Override
	public int getViewDistance() {
		return CreateTrainNextClient.signalViewDistance();
	}

	@Override
	protected void renderSafe(DepartureRepeaterBlockEntity be, float partialTicks, PoseStack ms,
		MultiBufferSource buffer, int light, int overlay) {
		super.renderSafe(be, partialTicks, ms, buffer, light, overlay);

		Level level = be.getLevel();
		BlockState state = be.getBlockState();
		if (level == null || !(state.getBlock() instanceof DepartureRepeaterBlock))
			return;

		float yaw = SignalRotation.modelYaw(state);
		CachedBuffers.partial(CreateTrainNextClient.DEPARTURE_REPEATER_HEAD, state)
			.rotateCenteredDegrees(-yaw, Direction.Axis.Y)
			.light(light)
			.renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));

		if (be.isLit()) {
			CachedBuffers.partial(CreateTrainNextClient.DEPARTURE_REPEATER_LIT, state)
				.rotateCenteredDegrees(-yaw, Direction.Axis.Y)
				.light(LightTexture.FULL_BLOCK)
				.renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));
			if (CTNConfig.CLIENT.signalGlow.get())
				renderGlow(state, yaw, ms, buffer);
		}

		renderTrackMarker(be, level, ms, buffer, light, overlay);
	}

	private void renderGlow(BlockState state, float yaw, PoseStack ms, MultiBufferSource buffer) {
		float brightness = CTNConfig.CLIENT.signalGlowBrightness.get()
			.floatValue();
		float size = CTNConfig.CLIENT.signalGlowSize.get()
			.floatValue();

		CachedBuffers.partial(CreateTrainNextClient.SIGNAL_LAMP_GLOW, state)
			.rotateCenteredDegrees(-yaw, Direction.Axis.Y)
			.translate(FACE_X, FACE_Y, GLOW_Z)
			.scale(size)
			.light(LightTexture.FULL_BRIGHT)
			.color(Math.round(((GLOW_TINT >> 16) & 0xFF) * brightness), Math.round(((GLOW_TINT >> 8) & 0xFF) * brightness),
				Math.round((GLOW_TINT & 0xFF) * brightness), 0xFF)
			.disableDiffuse()
			.renderInto(ms, buffer.getBuffer(CTNRenderTypes.glow()));
	}

	private void renderTrackMarker(DepartureRepeaterBlockEntity be, Level level, PoseStack ms, MultiBufferSource buffer,
		int light, int overlay) {
		if (!CreateTrainNextClient.isWithin(be, MARKER_VIEW_DISTANCE))
			return;

		TrackTargetingBehaviour<?> target = be.target;
		BlockPos targetPosition = target.getGlobalPosition();
		if (!(level.getBlockState(targetPosition)
			.getBlock() instanceof ITrackBlock))
			return;

		ms.pushPose();
		TransformStack.of(ms)
			.translate(targetPosition.subtract(be.getBlockPos()));
		TrackTargetingBehaviour.render(level, targetPosition, target.getTargetDirection(), target.getTargetBezier(), ms,
			buffer, light, overlay, RenderedTrackOverlayType.SIGNAL, 1);
		ms.popPose();
	}
}
