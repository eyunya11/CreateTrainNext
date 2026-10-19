package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.track.ApplyGradePacket;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Typing in the grade the wand lays a route at, and optionally how far to lay it, for when both are
 * already known.
 *
 * <p>The wheel is the better control while a route is being aimed, five permille at a time with the
 * ghost in front of you. It is the worse one for a number that came off a plan, and it cannot say how
 * far at all: a curve laid for a distance has no block to have aimed at. Leaving the distance empty
 * sets the grade and lays nothing, so the same screen does for changing the grade.
 *
 * <p>Plainly styled for the same reason {@link SurveyMarkerScreen} is.
 */
public class CurveGradeScreen extends Screen {

	private static final int WIDTH = 190;

	private static final int HEIGHT = 112;

	private final int initialGrade;

	private final int limit;

	private EditBox gradeBox;

	private EditBox distanceBox;

	/**
	 * @param grade the grade the selection has now, in permille.
	 * @param limit the most it may be either way, in permille.
	 */
	public CurveGradeScreen(int grade, int limit) {
		super(Component.translatable("createtrainnext.grade_screen.title"));
		this.initialGrade = grade;
		this.limit = limit;
	}

	@Override
	protected void init() {
		int left = (width - WIDTH) / 2;
		int top = (height - HEIGHT) / 2;

		gradeBox = new EditBox(font, left + 100, top + 24, 64, 16,
			Component.translatable("createtrainnext.grade_screen.grade"));
		gradeBox.setMaxLength(5);
		// A minus sign and digits, in the shapes a number can pass through while it is typed. Anything
		// else would have to be rejected on the way out, which is a worse moment to find out.
		gradeBox.setFilter(text -> text.matches("-?\\d{0,4}"));
		gradeBox.setValue(Integer.toString(initialGrade));
		addRenderableWidget(gradeBox);

		distanceBox = new EditBox(font, left + 100, top + 46, 64, 16,
			Component.translatable("createtrainnext.grade_screen.distance"));
		distanceBox.setMaxLength(4);
		distanceBox.setFilter(text -> text.chars()
			.allMatch(Character::isDigit));
		addRenderableWidget(distanceBox);

		addRenderableWidget(Button
			.builder(Component.translatable("createtrainnext.grade_screen.apply"), b -> applyAndClose())
			.bounds(left + 45, top + 70, 100, 20)
			.build());

		setInitialFocus(gradeBox);
	}

	private void applyAndClose() {
		PacketDistributor.sendToServer(new ApplyGradePacket(readGrade(), readDistance()));
		onClose();
	}

	/** The grade typed, held to the limit, or what it started as if the box holds no number. */
	private int readGrade() {
		try {
			return Mth.clamp(Integer.parseInt(gradeBox.getValue()
				.trim()), -limit, limit);
		} catch (NumberFormatException notANumber) {
			return initialGrade;
		}
	}

	/** The distance typed, or zero for none, which sets the grade and lays no curve. */
	private int readDistance() {
		try {
			return Math.max(0, Integer.parseInt(distanceBox.getValue()
				.trim()));
		} catch (NumberFormatException notANumber) {
			return 0;
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
			applyAndClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);

		int left = (width - WIDTH) / 2;
		int top = (height - HEIGHT) / 2;

		graphics.drawCenteredString(font, title, width / 2, top + 6, 0xFFFFFF);
		graphics.drawString(font, Component.translatable("createtrainnext.grade_screen.grade"), left + 12, top + 28,
			0xC8C8C8);
		graphics.drawString(font, Component.translatable("createtrainnext.grade_screen.distance"), left + 12,
			top + 50, 0xC8C8C8);
		graphics.drawCenteredString(font, Component.translatable("createtrainnext.grade_screen.hint", limit)
			.withStyle(ChatFormatting.DARK_GRAY), width / 2, top + HEIGHT - 12, 0x808080);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
