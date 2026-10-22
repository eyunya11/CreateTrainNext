package com.eyunya.createtrainnext.signal;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.IntSupplier;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.signal.SignalAspect.Ladder;
import com.eyunya.createtrainnext.signal.SignalAspect.LampColour;

/**
 * The kinds of signal head there are, told apart by how many lamps they have and what colour each is.
 *
 * <p>What a head is asked is the same whatever it is: how far ahead the line is clear, which a
 * {@link Ladder} turns into an aspect. What differs is how the aspect is shown. Each type has its own
 * column of lamps, read top to bottom, and its own idea of which of them an aspect lights. They follow
 * the vertical heads of JR East, where a smaller head is the bigger one with its upper lamps taken away:
 * the two-aspect is red over green, the three-aspect yellow, red, green, and the four-aspect yellow, red,
 * yellow, green, which is the five-aspect without its top lamp, and so without 警戒. The six-aspect adds
 * a second green below the five-aspect's yellow and gives the pair of greens a meaning of its own, 高速進行.
 *
 * <p>The column does not change with traffic, so nothing here is saved by a head: the block it stands
 * in is its type.
 */
public enum SignalType {

	/** 2灯式. Red over green: 停止 or 進行, and nothing between. */
	TWO("multi_aspect_signal_2", "multi_aspect_signal_2", Ladder.TWO,
		colours(LampColour.RED, LampColour.GREEN),
		lit(SignalAspect.STOP, 1), lit(SignalAspect.PROCEED, 2)),

	/** 3灯式. Yellow, red, green. */
	THREE("multi_aspect_signal_3", "multi_aspect_signal_3", Ladder.THREE,
		colours(LampColour.YELLOW, LampColour.RED, LampColour.GREEN),
		lit(SignalAspect.STOP, 2), lit(SignalAspect.APPROACH, 1), lit(SignalAspect.PROCEED, 3)),

	/** 4灯式. Yellow, red, yellow, green: the five-aspect's lamps 2 to 5. */
	FOUR("multi_aspect_signal_4", "multi_aspect_signal_4", Ladder.FOUR,
		colours(LampColour.YELLOW, LampColour.RED, LampColour.YELLOW, LampColour.GREEN),
		lit(SignalAspect.STOP, 2), lit(SignalAspect.APPROACH, 3), lit(SignalAspect.REDUCED_SPEED, 1, 4),
		lit(SignalAspect.PROCEED, 4)),

	/**
	 * 5灯式. Yellow, yellow, red, yellow, green. The first head this mod had, so its block keeps the
	 * name it was given before there were others.
	 *
	 * <p>The lamp column is arranged so the two-lamp aspects come out vertically separated and cannot be
	 * mistaken for each other at a distance. 警戒 lights lamps 1 and 4, two yellows with the red between
	 * them; 減速 lights lamps 2 and 5, a yellow high and the green low. Neither pair can be confused with
	 * the single-lamp aspects that share their colours.
	 */
	FIVE("multi_aspect_signal", "multi_aspect_signal", Ladder.FIVE,
		colours(LampColour.YELLOW, LampColour.YELLOW, LampColour.RED, LampColour.YELLOW, LampColour.GREEN),
		lit(SignalAspect.STOP, 3), lit(SignalAspect.RESTRICTED, 1, 4), lit(SignalAspect.APPROACH, 4),
		lit(SignalAspect.REDUCED_SPEED, 2, 5), lit(SignalAspect.PROCEED, 5)),

	/**
	 * 6灯式. Yellow, green, red, green, yellow, green.
	 *
	 * <p>The single green at lamp 4 is 進行, and unlike the other types' it holds a train to a speed
	 * ({@code signals.sixAspectProceedSpeed}): it is the aspect of a line that is clear but not clear
	 * enough to run at full speed. The two greens at 2 and 6 are 高速進行, which holds a train to
	 * nothing. There is no 警戒.
	 */
	SIX("multi_aspect_signal_6", "multi_aspect_signal_6", Ladder.SIX,
		() -> CTNConfig.SERVER.sixAspectProceedSpeed.get(),
		colours(LampColour.YELLOW, LampColour.GREEN, LampColour.RED, LampColour.GREEN, LampColour.YELLOW,
			LampColour.GREEN),
		lit(SignalAspect.STOP, 3), lit(SignalAspect.APPROACH, 5), lit(SignalAspect.REDUCED_SPEED, 1, 4),
		lit(SignalAspect.PROCEED, 4), lit(SignalAspect.HIGH_SPEED_PROCEED, 2, 6));

	/** The height of the middle of the five-aspect's middle lamp, in pixels. Every column is centred on it. */
	private static final float CENTRE_Y = 7.75F;

	/** Pixels between neighbouring lamps in the five-aspect's model, which every column keeps. */
	private static final float SPACING = 2.25F;

	private final String blockName;
	private final String modelDir;
	private final Ladder ladder;
	private final IntSupplier proceedSpeed;
	private final LampColour[] colours;
	private final Map<SignalAspect, Integer> masks = new EnumMap<>(SignalAspect.class);
	private final float[] socketY;

	SignalType(String blockName, String modelDir, Ladder ladder, LampColour[] colours, Lit... patterns) {
		this(blockName, modelDir, ladder, () -> SignalAspect.NO_LIMIT, colours, patterns);
	}

	SignalType(String blockName, String modelDir, Ladder ladder, IntSupplier proceedSpeed, LampColour[] colours,
		Lit... patterns) {
		this.blockName = blockName;
		this.modelDir = modelDir;
		this.ladder = ladder;
		this.proceedSpeed = proceedSpeed;
		this.colours = colours;
		this.socketY = centredSockets(colours.length);

		for (Lit pattern : patterns) {
			int mask = 0;
			for (int position : pattern.positions) {
				if (position < 1 || position > colours.length)
					throw new IllegalArgumentException(blockName + " has no lamp at position " + position);
				mask |= 1 << position;
			}
			if (masks.put(pattern.aspect, mask) != null)
				throw new IllegalArgumentException(blockName + " gives " + pattern.aspect + " two patterns");
		}
		// A head that is asked for an aspect it cannot show would go dark, which reads as a failed
		// signal and not as the aspect it was meant to say.
		for (SignalAspect aspect : ladder.aspects())
			if (!masks.containsKey(aspect))
				throw new IllegalArgumentException(blockName + " has no lamps for " + aspect);
	}

	/** The name of the block, and of its item, in the registry. */
	public String blockName() {
		return blockName;
	}

	/** The folder under {@code models/block/} that holds this type's housing models. */
	public String modelDir() {
		return modelDir;
	}

	/** The rungs this type steps through as the line ahead clears. */
	public Ladder ladder() {
		return ladder;
	}

	/** How many lamps the column has. */
	public int lampCount() {
		return colours.length;
	}

	/** The fixed colour of the lamp at {@code position}, counted from 1 at the top. */
	public LampColour colourAt(int position) {
		if (position < 1 || position > colours.length)
			throw new IllegalArgumentException("No lamp at position " + position);
		return colours[position - 1];
	}

	/** Whether the lamp at {@code position}, counted from 1 at the top, is lit when this type shows {@code aspect}. */
	public boolean isLit(SignalAspect aspect, int position) {
		Integer mask = masks.get(aspect);
		return mask != null && (mask & (1 << position)) != 0;
	}

	/** Whether this type has a way to show {@code aspect}. */
	public boolean shows(SignalAspect aspect) {
		return masks.containsKey(aspect);
	}

	/**
	 * Where the lamp at {@code position} sits on the housing, as the height of its middle in pixels.
	 *
	 * <p>The renderer moves every lamp from the one socket the lamp models are drawn at to here, so this
	 * is the only number that has to change when a type's housing is redrawn with its lamps elsewhere.
	 * Until it is, the lamps are spread about the middle of the five-aspect's housing at its spacing,
	 * which for the five-aspect itself is exactly where its sockets are.
	 */
	public float socketY(int position) {
		if (position < 1 || position > colours.length)
			throw new IllegalArgumentException("No lamp at position " + position);
		return socketY[position - 1];
	}

	/**
	 * The speed in km/h that this type's 進行 holds a train to, or {@link SignalAspect#NO_LIMIT}.
	 *
	 * <p>Only the six-aspect has one. Everywhere else a green signal is a line with no restriction, and
	 * it is 高速進行 that stands for that on the six-aspect.
	 */
	public int proceedSpeed() {
		return proceedSpeed.getAsInt();
	}

	private static float[] centredSockets(int lampCount) {
		float[] heights = new float[lampCount];
		for (int position = 1; position <= lampCount; position++)
			heights[position - 1] = CENTRE_Y + ((lampCount + 1) / 2F - position) * SPACING;
		return heights;
	}

	private static LampColour[] colours(LampColour... colours) {
		return colours;
	}

	private static Lit lit(SignalAspect aspect, int... positions) {
		return new Lit(aspect, positions);
	}

	/** One aspect and the lamps it lights, as written in the table above. */
	private record Lit(SignalAspect aspect, int[] positions) {}
}
