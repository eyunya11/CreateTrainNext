package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.signal.MultiAspectSignalBlock;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlockEntity;
import com.eyunya.createtrainnext.signal.SignalAspect;
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
 * Draws the five lamps, and the marker on the track the signal governs.
 *
 * <p>Only the lamps. The housing is a blockstate model, so it is part of the chunk mesh and lit
 * properly; there is no reason to draw something that never changes here. What does change is
 * which lamps are lit, and a baked model cannot express that without a blockstate property per
 * lamp.
 *
 * <p>A lit lamp is drawn at {@link LightTexture#FULL_BLOCK} rather than at the light where it
 * stands. Without that it is merely a coloured shape that goes dim at night, which is when a
 * signal most needs to read as lit. Create does the same for its own signals, and like Create's
 * this is only a rendering trick — the block emits no light and will not brighten anything near it.
 */
public class MultiAspectSignalRenderer extends SmartBlockEntityRenderer<MultiAspectSignalBlockEntity> {

	/**
	 * Vertical gap between neighbouring lamps, in blocks, measured off the model: its five sockets
	 * sit at y 3.25, 5.5, 7.75, 10 and 12.25.
	 */
	private static final float LAMP_SPACING = 2.25F / 16F;

	/**
	 * The socket the lamp models are drawn at, counted from 1 at the top. Every lamp model is the
	 * middle one, so the others are this many steps away from where the model already is.
	 */
	private static final int REFERENCE_POSITION = 3;

	public MultiAspectSignalRenderer(Context context) {
		super(context);
	}

	@Override
	protected void renderSafe(MultiAspectSignalBlockEntity be, float partialTicks, PoseStack ms,
		MultiBufferSource buffer, int light, int overlay) {
		super.renderSafe(be, partialTicks, ms, buffer, light, overlay);

		Level level = be.getLevel();
		if (level == null)
			return;

		renderLamps(be, be.getBlockState(), ms, buffer, light);
		renderTrackMarker(be, level, ms, buffer, light, overlay);
	}

	private void renderLamps(MultiAspectSignalBlockEntity be, BlockState state, PoseStack ms,
		MultiBufferSource buffer, int light) {
		if (!(state.getBlock() instanceof MultiAspectSignalBlock))
			return;

		SignalAspect aspect = be.getAspect();
		float yaw = MultiAspectSignalBlock.modelYaw(state);

		for (int position = 1; position <= SignalAspect.LAMP_COUNT; position++) {
			boolean lit = aspect.isLit(position);
			PartialModel model = lit ? CreateTrainNextClient.litLamp(SignalAspect.colourAt(position))
				: CreateTrainNextClient.SIGNAL_LAMP_OFF;

			// Position 1 is the top, so a lower number sits higher: the step is away from the
			// reference socket, not towards it.
			float offset = (REFERENCE_POSITION - position) * LAMP_SPACING;

			CachedBuffers.partial(model, state)
				// Negated because a blockstate variant's y turns the model the opposite way round
				// from this, and modelYaw is measured in the blockstate's sense so the housing and
				// the lamps agree.
				.rotateCenteredDegrees(-yaw, Direction.Axis.Y)
				// After the turn, so the lamps climb the housing rather than off to one side of it.
				// Harmless either way while the offset is vertical, but only by luck.
				.translate(0, offset, 0)
				.light(lit ? LightTexture.FULL_BLOCK : light)
				.renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));
		}
	}

	private void renderTrackMarker(MultiAspectSignalBlockEntity be, Level level, PoseStack ms,
		MultiBufferSource buffer, int light, int overlay) {
		TrackTargetingBehaviour<?> target = be.edgePoint;
		BlockPos targetPosition = target.getGlobalPosition();
		BlockState trackState = level.getBlockState(targetPosition);

		// The target is a remembered position: the track under it can have been mined since.
		if (!(trackState.getBlock() instanceof ITrackBlock))
			return;

		ms.pushPose();
		TransformStack.of(ms)
			.translate(targetPosition.subtract(be.getBlockPos()));
		TrackTargetingBehaviour.render(level, targetPosition, target.getTargetDirection(), target.getTargetBezier(),
			ms, buffer, light, overlay, RenderedTrackOverlayType.SIGNAL, 1);
		ms.popPose();
	}
}
