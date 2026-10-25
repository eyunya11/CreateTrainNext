package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.signal.SetSignalPlatePacket;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import net.neoforged.neoforge.network.PacketDistributor;

import org.lwjgl.glfw.GLFW;

/**
 * Writing on a signal's plate: one line, as long as the plate holds.
 *
 * <p>Plainly styled for the same reason {@link SurveyMarkerScreen} is.
 */
public class SignalPlateScreen extends Screen {

	private static final int WIDTH = 176;

	private static final int HEIGHT = 74;

	private final BlockPos pos;

	private final String initialText;

	private final int maxLength;

	private EditBox textBox;

	public SignalPlateScreen(BlockPos pos, String text, int maxLength) {
		super(Component.translatable("createtrainnext.signal_plate.title"));
		this.pos = pos;
		this.initialText = text;
		this.maxLength = maxLength;
	}

	@Override
	protected void init() {
		int left = (width - WIDTH) / 2;
		int top = (height - HEIGHT) / 2;

		textBox = new EditBox(font, left + 38, top + 22, 100, 16, title);
		textBox.setMaxLength(maxLength);
		textBox.setValue(initialText);
		addRenderableWidget(textBox);

		addRenderableWidget(Button
			.builder(Component.translatable("createtrainnext.signal_plate.apply"), b -> applyAndClose())
			.bounds(left + 38, top + 46, 100, 20)
			.build());

		setInitialFocus(textBox);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			applyAndClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private void applyAndClose() {
		PacketDistributor.sendToServer(new SetSignalPlatePacket(pos, textBox.getValue()));
		onClose();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(font, title, width / 2, (height - HEIGHT) / 2 + 6, 0xFFFFFF);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
