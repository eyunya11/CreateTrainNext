package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.signal.PlateKind;
import com.eyunya.createtrainnext.signal.SignalPlateBlock;
import com.eyunya.createtrainnext.signal.SignalPlateBlockEntity;
import com.eyunya.createtrainnext.signal.SignalRotation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;

import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws a signal's plate, turned the way its block is, and what is written on it.
 *
 * <p>The pole through the block is its blockstate model. The plate is drawn here because it turns in
 * 22.5 degree steps, as the signal heads do.
 *
 * <p>The writing is centred on the plate's front, the face {@link PlateKind#plateBounds()} puts at z1, and
 * is as big as the plate's height allows, smaller when it is too wide for the plate. Unlike a speed limit,
 * where a number that changed size would look broken, a plate is written once and the size it comes out at
 * is the size it stays.
 */
public class SignalPlateRenderer extends SmartBlockEntityRenderer<SignalPlateBlockEntity> {

	/** How tall a capital or a figure is in the font's own units: 7 of the 9 of a line. */
	private static final float GLYPH_HEIGHT = 7F;

	/** How tall a line of text is in the font's own units, a character and the gap under it. */
	private static final float LINE_HEIGHT = 9F;

	/** The share of the plate's height the writing takes, leaving a margin above and below. */
	private static final float TEXT_SHARE = 0.7F;

	/** The share of the plate's width the writing may take before it is made smaller. */
	private static final float WIDTH_SHARE = 0.85F;

	public SignalPlateRenderer(Context context) {
		super(context);
	}

	/** As far as a signal's lamps, so that a plate is not gone from a signal that is still lit. */
	@Override
	public int getViewDistance() {
		return CreateTrainNextClient.signalViewDistance();
	}

	@Override
	protected void renderSafe(SignalPlateBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
		int light, int overlay) {
		super.renderSafe(be, partialTicks, ms, buffer, light, overlay);

		BlockState state = be.getBlockState();
		if (!(state.getBlock() instanceof SignalPlateBlock block))
			return;
		PlateKind kind = block.kind();
		float yaw = SignalRotation.modelYaw(state);

		CachedBuffers.partial(CreateTrainNextClient.signalPlate(kind), state)
			.rotateCenteredDegrees(-yaw, Direction.Axis.Y)
			.light(light)
			.renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));

		String text = be.getText();
		if (text.isEmpty())
			return;
		if (kind.vertical())
			renderVertical(text, kind, yaw, ms, buffer, light);
		else
			renderText(text, kind, yaw, ms, buffer, light);
	}

	/**
	 * Writes the text down the plate, a character under the one before, as a kilometre post has it. Each
	 * character is as big as the plate's width allows, and smaller when there are too many for its height.
	 */
	private void renderVertical(String text, PlateKind kind, float yaw, PoseStack ms, MultiBufferSource buffer,
		int light) {
		Font font = Minecraft.getInstance().font;
		double[] b = kind.plateBounds();
		float plateWidth = (float) (b[3] - b[0]) / 16F;
		float plateHeight = (float) (b[4] - b[1]) / 16F;
		int[] characters = text.codePoints()
			.toArray();
		int widest = 1;
		for (int character : characters)
			widest = Math.max(widest, font.width(Character.toString(character)) - 1);

		// A line is 9 of the font's units, the 7 of a character and the gap under it.
		float scale = Math.min(plateWidth * WIDTH_SHARE / widest, plateHeight * TEXT_SHARE / (characters.length * LINE_HEIGHT));
		float top = (characters.length * LINE_HEIGHT - (LINE_HEIGHT - GLYPH_HEIGHT)) / 2F;

		ms.pushPose();
		ms.translate(0.5F, 0, 0.5F);
		ms.mulPose(Axis.YP.rotationDegrees(-yaw));
		ms.translate((b[0] + b[3]) / 32 - 0.5, (b[1] + b[4]) / 32, b[5] / 16 - 0.5);
		ms.scale(scale, -scale, scale);
		for (int i = 0; i < characters.length; i++) {
			String character = Character.toString(characters[i]);
			font.drawInBatch(character, -(font.width(character) - 1) / 2F, -top + i * LINE_HEIGHT, kind.textColour(), false,
				ms.last()
					.pose(), buffer, Font.DisplayMode.POLYGON_OFFSET, 0, light);
		}
		ms.popPose();
	}

	private void renderText(String text, PlateKind kind, float yaw, PoseStack ms, MultiBufferSource buffer,
		int light) {
		Font font = Minecraft.getInstance().font;
		double[] b = kind.plateBounds();
		float plateWidth = (float) (b[3] - b[0]) / 16F;
		float plateHeight = (float) (b[4] - b[1]) / 16F;
		int textWidth = Math.max(1, font.width(text) - 1);

		float scale = Math.min(plateHeight * TEXT_SHARE / GLYPH_HEIGHT, plateWidth * WIDTH_SHARE / textWidth);

		ms.pushPose();
		ms.translate(0.5F, 0, 0.5F);
		// The same turn as the plate model: rotateCenteredDegrees(-yaw) about the block's middle.
		ms.mulPose(Axis.YP.rotationDegrees(-yaw));
		// The middle of the plate's front, measured from the block's middle. Text is drawn on +z, which is
		// where the front is, so no half turn as the speed limit sign needs.
		ms.translate((b[0] + b[3]) / 32 - 0.5, (b[1] + b[4]) / 32, b[5] / 16 - 0.5);
		// Negative y because the font measures downwards.
		ms.scale(scale, -scale, scale);
		font.drawInBatch(text, -textWidth / 2F, -GLYPH_HEIGHT / 2F, kind.textColour(), false, ms.last()
			.pose(), buffer, Font.DisplayMode.POLYGON_OFFSET, 0, light);
		ms.popPose();
	}
}
