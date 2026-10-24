package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlock;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlockEntity;
import com.eyunya.createtrainnext.signal.SignalAspect;
import com.eyunya.createtrainnext.signal.SignalType;
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
 * Draws the lamps, however many the signal has, and the marker on the track the signal governs.
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
 *
 * <p>Two further things are done to a lit lamp, both aimed at the same problem and both off by
 * default in the sense that either can be turned down to nothing in the config: it can be drawn
 * larger than an unlit one, and it can be given a halo. The problem is that a lamp is 1.7 pixels
 * wide and its bezel crops that to a circle about 1.6 across, so past a certain distance the lit
 * area is smaller than a screen pixel and its colour is gone. Create's own signal does not suffer
 * this because its indicator is a 16-pixel cross rather than a lens. Making the lamp bigger raises
 * the distance at which that happens; the halo, being several times wider than the lamp, pushes it
 * much further, and is the same trick Create uses on nixie tubes and display links.
 */
public class MultiAspectSignalRenderer extends SmartBlockEntityRenderer<MultiAspectSignalBlockEntity> {

	/** Centre of the socket the lamp models are authored at, the point everything here scales about. */
	private static final float LAMP_CENTRE_X = 8F / 16F;

	private static final float LAMP_CENTRE_Y = 7.75F / 16F;

	/**
	 * Where the lamp models are drawn, as the height of the middle of the socket they sit in, in pixels.
	 * Every lamp model is the five-aspect's middle one, so a lamp elsewhere is moved by the difference
	 * between its type's {@link SignalType#socketY} and this.
	 */
	private static final float LAMP_MODEL_Y = 7.75F;

	private static final float LAMP_CENTRE_Z = 10.0125F / 16F;

	/**
	 * How far forward an enlarged lamp is moved, in blocks.
	 *
	 * <p>The bezel ringing each socket occupies z 10 to 10.05 and the lamp only 10 to 10.025, so the
	 * lamp is already inside the ring and growing it there would push the new area behind the ring
	 * rather than into view — nothing would look any different. A tenth of a pixel clears the ring
	 * for any scale the config allows, and is far too little to see as the lamp standing proud.
	 */
	private static final float BEZEL_CLEARANCE = 0.1F / 16F;

	/**
	 * Where the halo sits, in blocks.
	 *
	 * <p>In front of the bezel and of the furthest forward an enlarged lamp can reach, so it is
	 * never the halo that loses the depth test to the thing it is meant to be surrounding. Still
	 * well behind the hood over each socket, which should cut the halo off above the lamp exactly
	 * as a real hood cuts off the glare.
	 *
	 * <p>Being in front is only safe because {@link CTNRenderTypes#glow()} does not write depth. A
	 * halo that did would be a square hole in the depth buffer sitting over the lamp, and whether
	 * the lamp survived it would come down to which render pass a given frame happened to flush
	 * first.
	 */
	private static final float GLOW_Z = 10.25F / 16F;

	/**
	 * How far away the marker on the targeted track is still drawn, in blocks.
	 *
	 * <p>Deliberately not as far as the lamps. The marker is a setting-up aid — it says which piece
	 * of track this signal governs — and it is only ever looked at from close enough to be standing
	 * at the thing. Left uncapped it would scatter overlays across every line in view once the lamps
	 * started being drawn to the render distance, and pay for drawing them.
	 *
	 * <p>Vanilla's own block entity limit, which is what this used to be capped by for free.
	 */
	private static final int MARKER_VIEW_DISTANCE = 64;

	public MultiAspectSignalRenderer(Context context) {
		super(context);
	}

	/**
	 * How far the lamps are drawn.
	 *
	 * <p>Overridden because vanilla's answer is 64 blocks, and at 65 the housing carries on being
	 * drawn — it is terrain — while the lamps stop. A signal that goes dark at four chunks is worse
	 * than one that is merely small: an unlit head reads as an unlit head, not as one too far away
	 * to see.
	 */
	@Override
	public int getViewDistance() {
		return CreateTrainNextClient.signalViewDistance();
	}

	@Override
	protected void renderSafe(MultiAspectSignalBlockEntity be, float partialTicks, PoseStack ms,
		MultiBufferSource buffer, int light, int overlay) {
		super.renderSafe(be, partialTicks, ms, buffer, light, overlay);

		Level level = be.getLevel();
		if (level == null)
			return;

		renderHead(be.getBlockState(), ms, buffer, light);
		renderLamps(be, be.getBlockState(), ms, buffer, light);
		renderTrackMarker(be, level, ms, buffer, light, overlay);
	}

	/**
	 * Draws the turning half of the housing.
	 *
	 * <p>Here rather than in the blockstate because the head turns in 22.5 degree steps and a
	 * blockstate only turns in quarters — see {@code SignalRotation}. The mast is still a blockstate
	 * model, so it keeps the chunk mesh's lighting and costs nothing; the head pays for the angle by
	 * being transformed every frame and lit flat, the same trade the speed limit sign makes.
	 */
	private void renderHead(BlockState state, PoseStack ms, MultiBufferSource buffer, int light) {
		if (!(state.getBlock() instanceof MultiAspectSignalBlock))
			return;

		SignalType type = ((MultiAspectSignalBlock) state.getBlock()).signalType();
		CachedBuffers.partial(CreateTrainNextClient.signalHead(type, state.getValue(MultiAspectSignalBlock.OFFSET)), state)
			.rotateCenteredDegrees(-MultiAspectSignalBlock.modelYaw(state), Direction.Axis.Y)
			.light(light)
			.renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));
	}

	private void renderLamps(MultiAspectSignalBlockEntity be, BlockState state, PoseStack ms,
		MultiBufferSource buffer, int light) {
		if (!(state.getBlock() instanceof MultiAspectSignalBlock))
			return;

		SignalAspect aspect = be.getAspect();
		SignalType type = be.signalType();
		float yaw = MultiAspectSignalBlock.modelYaw(state);
		float localX = state.getValue(MultiAspectSignalBlock.OFFSET)
			.localX();

		float litScale = CTNConfig.CLIENT.signalLitLampScale.get()
			.floatValue();
		boolean glow = CTNConfig.CLIENT.signalGlow.get();

		for (int position = 1; position <= type.lampCount(); position++) {
			boolean lit = type.isLit(aspect, position);
			PartialModel model = lit ? CreateTrainNextClient.litLamp(type.colourAt(position))
				: CreateTrainNextClient.SIGNAL_LAMP_OFF;

			// Position 1 is the top, and the type says how high each socket is, so the step is whatever
			// takes the lamp from where the model is drawn to there.
			float verticalOffset = (type.socketY(position) - LAMP_MODEL_Y) / 16F;

			// Only an enlarged lamp comes forward. At a scale of exactly 1 the lamp is left inside
			// its bezel where the model puts it, so the config's lower bound is a true "unchanged"
			// rather than a value that still moves the lamp a tenth of a pixel.
			float scale = lit ? litScale : 1F;
			float forward = scale > 1F ? BEZEL_CLEARANCE : 0F;

			CachedBuffers.partial(model, state)
				// Negated because a blockstate variant's y turns the model the opposite way round
				// from this, and modelYaw is measured in the blockstate's sense so the housing and
				// the lamps agree.
				.rotateCenteredDegrees(-yaw, Direction.Axis.Y)
				// Issued after the rotation, so — per Flywheel's Affine, which composes each call
				// inside the ones before it — this runs first, in the same south-authored local
				// space housing_left.json/housing_right.json were shifted in, and the rotation above
				// carries it around exactly as it carries the housing's own baked shift. Reversing
				// the order would translate along the already-rotated axes instead and send the
				// lamps sideways off the housing on three of the four facings.
				.translate(localX, verticalOffset, forward)
				// Scaling happens about the model origin, which is the block's corner, so the lamp
				// has to be walked to the origin and back around it. Innermost of the three, so it
				// runs before any of the placement above.
				.translate(LAMP_CENTRE_X, LAMP_CENTRE_Y, LAMP_CENTRE_Z)
				.scale(scale)
				.translate(-LAMP_CENTRE_X, -LAMP_CENTRE_Y, -LAMP_CENTRE_Z)
				.light(lit ? LightTexture.FULL_BLOCK : light)
				.renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));

			if (lit && glow)
				renderGlow(state, type.colourAt(position), yaw, localX, verticalOffset, ms, buffer);
		}
	}

	/**
	 * Draws the halo over one lit lamp.
	 *
	 * <p>Additively, through {@link CTNRenderTypes#glow()}: the halo adds its colour
	 * to whatever is behind it instead of covering it, which is what makes it read as light coming
	 * off the lamp rather than as a disc stuck to the front of the signal. It is white in the
	 * texture and tinted here, and drawn at full brightness for the same reason the lit lamp is.
	 */
	private void renderGlow(BlockState state, SignalAspect.LampColour colour, float yaw, float localX,
		float verticalOffset, PoseStack ms, MultiBufferSource buffer) {
		int tint = colour.glowTint();
		float brightness = CTNConfig.CLIENT.signalGlowBrightness.get()
			.floatValue();
		float size = CTNConfig.CLIENT.signalGlowSize.get()
			.floatValue();

		// Brightness scales the colour rather than the alpha because the blend adds the source
		// colour on: dimming is a matter of adding less of it.
		int red = Math.round(((tint >> 16) & 0xFF) * brightness);
		int green = Math.round(((tint >> 8) & 0xFF) * brightness);
		int blue = Math.round((tint & 0xFF) * brightness);

		CachedBuffers.partial(CreateTrainNextClient.SIGNAL_LAMP_GLOW, state)
			.rotateCenteredDegrees(-yaw, Direction.Axis.Y)
			.translate(localX, verticalOffset, 0)
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

	private void renderTrackMarker(MultiAspectSignalBlockEntity be, Level level, PoseStack ms,
		MultiBufferSource buffer, int light, int overlay) {
		if (!CreateTrainNextClient.isWithin(be, MARKER_VIEW_DISTANCE))
			return;

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
