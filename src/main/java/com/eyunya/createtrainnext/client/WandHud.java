package com.eyunya.createtrainnext.client;

import java.util.ArrayList;
import java.util.List;

import com.eyunya.createtrainnext.CreateTrainNext;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * What the wand has to say while a curve is being aimed, drawn above the action bar and folded to fit.
 *
 * <p>This used to be the action bar itself, and a curve that climbs has more to say than a line of it
 * holds: the action bar is one line that does not wrap, so a message longer than the screen is
 * simply cut off at both edges, and at the larger interface scales that is not far past what the
 * curve's length and bearing already took. Here each message is folded to the width of the screen,
 * and a message can be several lines to begin with — the geometry of the curve, then how it climbs.
 *
 * <p>Said afresh every tick by whoever is aiming, and gone a few ticks after they stop, the way the
 * ghost it describes is. Anything the server says by the action bar is still drawn there, below.
 */
public final class WandHud {

	private static final ResourceLocation LAYER = CreateTrainNext.asResource("wand_status");

	/** The widest a line is let get before it is folded, in interface pixels. */
	private static final int MAX_WIDTH = 440;

	/** The space kept clear at each side of the screen. */
	private static final int SIDE_MARGIN = 12;

	/**
	 * How far above the bottom of the screen the top of the last line's text is. The action bar's own
	 * text is drawn from 72 pixels up, inside a box that reaches 74 up; the box round this one ends 78
	 * up, so a message from the server and one from here do not print over one another.
	 */
	private static final int BOTTOM = 88;

	private static final int LINE_HEIGHT = 12;

	/** How many ticks a message outlasts the last time it was said. */
	private static final int LIFETIME = 3;

	private static List<Component> lines = List.of();

	private static int ticksLeft;

	public static void init(IEventBus modEventBus) {
		modEventBus.addListener(WandHud::onRegisterLayers);
		NeoForge.EVENT_BUS.addListener(WandHud::onClientTick);
	}

	/** Says these lines, one under another, in place of whatever was said before. */
	public static void show(List<Component> message) {
		lines = List.copyOf(message);
		ticksLeft = LIFETIME;
	}

	public static void clear() {
		ticksLeft = 0;
	}

	/** Before the tick's own saying, so that a message that is said every tick never reaches nothing. */
	private static void onClientTick(ClientTickEvent.Pre event) {
		if (ticksLeft > 0)
			ticksLeft--;
	}

	private static void onRegisterLayers(RegisterGuiLayersEvent event) {
		event.registerAboveAll(LAYER, WandHud::render);
	}

	private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		// A layer above all the others is not hidden with the rest of the interface.
		if (ticksLeft <= 0 || lines.isEmpty() || mc.options.hideGui)
			return;

		Font font = mc.font;
		int maxWidth = Math.max(120, Math.min(graphics.guiWidth() - 2 * SIDE_MARGIN, MAX_WIDTH));

		List<FormattedCharSequence> folded = new ArrayList<>();
		for (Component line : lines)
			folded.addAll(font.split(line, maxWidth));

		int background = mc.options.getBackgroundColor(0.0F);
		int y = graphics.guiHeight() - BOTTOM - LINE_HEIGHT * (folded.size() - 1);
		for (FormattedCharSequence line : folded) {
			int width = font.width(line);
			int x = (graphics.guiWidth() - width) / 2;
			if (background != 0)
				graphics.fill(x - 2, y - 2, x + width + 2, y + font.lineHeight + 1, background);
			graphics.drawString(font, line, x, y, 0xFFFFFFFF);
			y += LINE_HEIGHT;
		}
	}

	private WandHud() {}
}
