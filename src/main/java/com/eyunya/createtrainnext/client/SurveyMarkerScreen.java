package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.track.SetSurveyMarkerPacket;
import com.eyunya.createtrainnext.track.SurveyMarkerBlockEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Typing the two numbers into a survey marker, for when they are already known.
 *
 * <p>The scroll box on the block is the better control while a line is being aimed — a degree at a
 * time, in the world, looking at where the track should go. It is the worse one when the bearing
 * came off a plan or off the wand's own readout, because reaching 137 from 0 is a hundred and
 * thirty-seven notches. This is the other half of that.
 *
 * <p>Plainly styled on purpose. Create's own screens are drawn against its texture sheet, and
 * borrowing that would tie a five-widget dialog to the exact layout of an atlas in someone else's
 * mod. Two boxes and a button do not need it.
 */
public class SurveyMarkerScreen extends Screen {

	private static final int WIDTH = 176;

	private static final int HEIGHT = 96;

	private final BlockPos pos;

	private final int initialBearing;

	private final int initialLength;

	private EditBox bearingBox;

	private EditBox lengthBox;

	public SurveyMarkerScreen(BlockPos pos, int bearing, int length) {
		super(Component.translatable("createtrainnext.survey_marker.title"));
		this.pos = pos;
		this.initialBearing = bearing;
		this.initialLength = length;
	}

	@Override
	protected void init() {
		int left = (width - WIDTH) / 2;
		int top = (height - HEIGHT) / 2;

		bearingBox = numberBox(left + 80, top + 24, initialBearing,
			Component.translatable("createtrainnext.survey_marker.bearing"));
		lengthBox = numberBox(left + 80, top + 46, initialLength,
			Component.translatable("createtrainnext.survey_marker.length"));

		addRenderableWidget(Button
			.builder(Component.translatable("createtrainnext.survey_marker.apply"), b -> applyAndClose())
			.bounds(left + 38, top + 68, 100, 20)
			.build());

		setInitialFocus(bearingBox);
	}

	private EditBox numberBox(int x, int y, int value, Component label) {
		EditBox box = new EditBox(font, x, y, 58, 16, label);
		box.setMaxLength(4);
		// Digits only, and an empty box while it is being retyped. Anything else would have to be
		// rejected on the way out, which is a worse moment to find out.
		box.setFilter(text -> text.isEmpty() || text.chars()
			.allMatch(Character::isDigit));
		box.setValue(Integer.toString(value));
		return addRenderableWidget(box);
	}

	private void applyAndClose() {
		PacketDistributor.sendToServer(new SetSurveyMarkerPacket(pos,
			read(bearingBox, initialBearing, SurveyMarkerBlockEntity.MIN_BEARING, SurveyMarkerBlockEntity.MAX_BEARING),
			read(lengthBox, initialLength, SurveyMarkerBlockEntity.MIN_LENGTH, SurveyMarkerBlockEntity.MAX_LENGTH)));
		onClose();
	}

	/** The box's value, or what it started as if it was left empty. Out of range clamps in. */
	private static int read(EditBox box, int fallback, int min, int max) {
		String text = box.getValue()
			.trim();
		if (text.isEmpty())
			return fallback;
		try {
			return Mth.clamp(Integer.parseInt(text), min, max);
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);

		int left = (width - WIDTH) / 2;
		int top = (height - HEIGHT) / 2;

		graphics.drawCenteredString(font, title, width / 2, top + 6, 0xFFFFFF);
		graphics.drawString(font, Component.translatable("createtrainnext.survey_marker.bearing"), left + 16,
			top + 28, 0xC8C8C8);
		graphics.drawString(font, Component.translatable("createtrainnext.survey_marker.length"), left + 16,
			top + 50, 0xC8C8C8);
		graphics.drawString(font, Component.translatable("createtrainnext.survey_marker.hint")
			.withStyle(ChatFormatting.DARK_GRAY), left + 16, top + HEIGHT - 4, 0x808080);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
