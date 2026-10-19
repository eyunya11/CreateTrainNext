package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.speed.SpeedLimitBlock;
import com.eyunya.createtrainnext.speed.SpeedLimitBlockEntity;
import com.eyunya.createtrainnext.speed.SpeedReleaseBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour.RenderedTrackOverlayType;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;

import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the sign itself, and the marker on the piece of track it governs.
 *
 * <p>The sign is drawn here rather than by its blockstate, and at 22.5 degree steps that is a
 * choice rather than a necessity. A blockstate could just about reach these sixteen angles: a
 * variant's {@code y} rotation gives four, and a model element's own rotation adds -45, -22.5 and
 * 22.5 to each, which covers every multiple of 22.5 across four model files. That would light and
 * batch better, since the sign would be part of the chunk mesh instead of a per-block draw with one
 * flat light value.
 *
 * <p>Drawing it costs that and buys back the angle. One model serves every position, and the step
 * is a constant in {@link SpeedLimitBlock} rather than a shape baked into four files and sixteen
 * variants — which matters here, because the step has already changed twice. If it settles at 22.5
 * for good, moving to baked models is a worthwhile trade.
 *
 * <p>Either way the block model is empty and carries nothing but the particle texture, the same
 * arrangement vanilla uses for its own signs.
 *
 * <p>The track marker exists so the rail the sign points at is visibly claimed, the way Create
 * marks the track under a station or a signal. Without it a placed sign gives no clue which of the
 * rails around it was actually targeted.
 *
 * <p>Deliberately no {@code VisualizationManager} bail-out, unlike Create's own track-point
 * renderers: those skip when Flywheel is drawing because they have a matching {@code Visual} that
 * would otherwise draw the marker twice. This has none, so skipping would lose both the marker and
 * the sign whenever Flywheel is on.
 */
public class SpeedLimitRenderer extends SmartBlockEntityRenderer<SpeedLimitBlockEntity> {

	/** Middle of the plate, which spans y 5 to 14 in the model. */
	private static final float PLATE_CENTRE_Y = 9.5F / 16F;

	/** How far the plate's two faces sit either side of the block's middle: it spans z 7 to 9. */
	private static final float PLATE_HALF_DEPTH = 1F / 16F;

	/**
	 * How tall a digit is in the font's own units. Not {@code Font#lineHeight}, which is 9 and
	 * includes the room below the baseline that digits do not use — centring on that would sit the
	 * number a pixel high on the sign.
	 */
	private static final float DIGIT_HEIGHT = 7F;

	/**
	 * How tall a digit is drawn at a font scale of 1, in blocks — the reference the configured
	 * scale multiplies, not the size anything ships at. Set where it is because the white part of
	 * the sign is about 7.4 sixteenths across and a three digit limit is 17 font units wide, so a
	 * scale of 1 is the point at which "100" still clears the red ring. The default scale is
	 * deliberately above that, trading the margin for legibility at a distance.
	 */
	private static final float DIGIT_HEIGHT_AT_SCALE_ONE = 2.5F / 16F;

	/**
	 * How far away the marker on the targeted track is still drawn, in blocks.
	 *
	 * <p>Deliberately not as far as the sign. The marker is a setting-up aid — it says which piece
	 * of track this sign governs — and is only ever looked at from close enough to be standing at
	 * the thing. Left uncapped it would scatter overlays across every line in view once the sign
	 * started being drawn to the render distance, and pay for drawing them.
	 *
	 * <p>Vanilla's own block entity limit, which is what this used to be capped by for free.
	 */
	private static final int MARKER_VIEW_DISTANCE = 64;

	public SpeedLimitRenderer(Context context) {
		super(context);
	}

	/**
	 * How far the sign is drawn.
	 *
	 * <p>Overridden because vanilla's answer is 64 blocks and everything about this sign is drawn
	 * here — the plate as much as the number on it, since the block model is empty so that the plate
	 * can be turned 22.5 degrees at a time. Past four chunks there would be nothing beside the track
	 * at all.
	 */
	@Override
	public int getViewDistance() {
		return CreateTrainNextClient.signViewDistance();
	}

	@Override
	protected void renderSafe(SpeedLimitBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
		int light, int overlay) {
		super.renderSafe(be, partialTicks, ms, buffer, light, overlay);

		Level level = be.getLevel();
		if (level == null)
			return;

		renderSign(be.getBlockState(), ms, buffer, light);
		// The sign that ends a limit has no number: its plate says it all.
		if (!be.isRelease())
			renderLimit(be, be.getBlockState(), ms, buffer, light);
		renderTrackMarker(be, level, ms, buffer, light, overlay);
	}

	/**
	 * Draws the limit across the sign's face, in black and centred.
	 *
	 * <p>The front only. The plate carries the sign texture on its back as well, so the back reads
	 * as a blank sign rather than the back of one — that is the model's to fix, not this method's.
	 *
	 * <p>The transform is vanilla's for sign text, with one turn added: after the rotation a sign's
	 * text is drawn on local +z, and this model's face is north at segment 0, which is local -z.
	 * Hence the 180.
	 */
	private void renderLimit(SpeedLimitBlockEntity be, BlockState state, PoseStack ms, MultiBufferSource buffer,
		int light) {
		if (!(state.getBlock() instanceof SpeedLimitBlock))
			return;

		Font font = Minecraft.getInstance().font;
		String limit = Integer.toString(be.getLimit());
		float scale = (float) (DIGIT_HEIGHT_AT_SCALE_ONE / DIGIT_HEIGHT * CTNConfig.CLIENT.signFontScale.get());

		// Deliberately not sized to fit whatever the current number is: a sign whose digits grew
		// when the limit dropped from 100 to 40 would read as broken. Every number gets the size
		// the widest one needs.
		float x = -(font.width(limit) - 1) / 2F;

		ms.pushPose();
		ms.translate(0.5F, PLATE_CENTRE_Y, 0.5F);
		ms.mulPose(Axis.YP.rotationDegrees(180 - SpeedLimitBlock.modelYaw(state)));
		ms.translate(0, 0, PLATE_HALF_DEPTH);
		// Negative y because the font measures downwards.
		ms.scale(scale, -scale, scale);
		// POLYGON_OFFSET is what keeps the glyphs out of the plate they lie flat against, rather
		// than nudging them along z and hoping the gap survives at every angle.
		font.drawInBatch(limit, x, -DIGIT_HEIGHT / 2F, 0xFF000000, false, ms.last()
			.pose(), buffer, Font.DisplayMode.POLYGON_OFFSET, 0, light);
		ms.popPose();
	}

	private void renderSign(BlockState state, PoseStack ms, MultiBufferSource buffer, int light) {
		if (!(state.getBlock() instanceof SpeedLimitBlock))
			return;
		// The model is authored facing north, which is segment 0, so the whole of the turn is this
		// one rotation about the block's centre.
		CachedBuffers.partial(state.getBlock() instanceof SpeedReleaseBlock ? CreateTrainNextClient.SPEED_RELEASE_SIGN
			: CreateTrainNextClient.SPEED_LIMIT_SIGN, state)
			.rotateCenteredDegrees(-SpeedLimitBlock.modelYaw(state), Direction.Axis.Y)
			.light(light)
			.renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));
	}

	private void renderTrackMarker(SpeedLimitBlockEntity be, Level level, PoseStack ms, MultiBufferSource buffer,
		int light, int overlay) {
		if (!CreateTrainNextClient.isWithin(be, MARKER_VIEW_DISTANCE))
			return;

		TrackTargetingBehaviour<?> target = be.edgePoint;
		BlockPos targetPosition = target.getGlobalPosition();
		BlockState trackState = level.getBlockState(targetPosition);

		// The target is only a remembered position: the track there can have been mined since.
		if (!(trackState.getBlock() instanceof ITrackBlock))
			return;

		ms.pushPose();
		TransformStack.of(ms)
			.translate(targetPosition.subtract(be.getBlockPos()));
		// The same overlay the preview shows while the item is held, so aiming and the placed
		// result agree — Create only has markers for its own three point types.
		TrackTargetingBehaviour.render(level, targetPosition, target.getTargetDirection(), target.getTargetBezier(),
			ms, buffer, light, overlay, RenderedTrackOverlayType.STATION, 1);
		ms.popPose();
	}
}
