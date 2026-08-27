package com.eyunya.createtrainnext.signal;

/**
 * The five aspects a Japanese five-lamp signal can display, and which of its lamps each lights.
 *
 * <p>The lamp column is fixed, read top to bottom: yellow, yellow, red, yellow, green. That
 * arrangement is not arbitrary — it exists so the two-lamp aspects come out vertically separated
 * and cannot be mistaken for each other at a distance. {@link #RESTRICTED} lights lamps 1 and 4, two
 * yellows with the red between them; {@link #REDUCED_SPEED} lights lamps 2 and 5, a yellow high and
 * the green low. Neither pair can be confused with the single-lamp aspects that share their
 * colours.
 *
 * <p>Which aspect a signal shows follows from how far ahead the line is clear, counted in blocks
 * from the signal outwards. Working back from a train, the signal protecting it is at
 * {@link #STOP}; the one before that {@link #APPROACH}; the one before that {@link #REDUCED_SPEED};
 * and anything further back {@link #PROCEED}. {@link #RESTRICTED} does not fit that count — see
 * its own note.
 *
 * <p>This is data only. Nothing reads it yet: the block, its renderer and the block-counting that
 * would choose an aspect are all still to be written.
 */
public enum SignalAspect {

	/** 停止. Red alone. The block ahead is occupied. */
	STOP(0, LampPattern.of(3)),

	/**
	 * 警戒. Both outer yellows, lamps 1 and 4.
	 *
	 * <p>The odd one out: it is not simply "N blocks clear". Real railways use it where the block
	 * ahead is too short for a train running at {@link #APPROACH} speed to stop within it, so an
	 * extra, slower step is inserted ahead of the stop signal instead. It therefore has to be a
	 * property of the signal or of the block behind it, not something derived from a clear count.
	 */
	RESTRICTED(25, LampPattern.of(1, 4)),

	/** 注意. The lower yellow alone, lamp 4. One block clear; the next signal is at stop. */
	APPROACH(45, LampPattern.of(4)),

	/** 減速. Upper yellow and green, lamps 2 and 5. Two blocks clear. */
	REDUCED_SPEED(75, LampPattern.of(2, 5)),

	/** 進行. Green alone, lamp 5. Three or more blocks clear; no restriction of its own. */
	PROCEED(Constants.NO_LIMIT, LampPattern.of(5));

	/** Stands in for "run at whatever the line allows" rather than a speed this signal imposes. */
	public static final int NO_LIMIT = Constants.NO_LIMIT;

	/** How many lamps the column has, top to bottom. */
	public static final int LAMP_COUNT = Constants.LAMP_COUNT;

	/**
	 * Holder for the two values the constants above need at construction time.
	 *
	 * <p>Only here because Java forbids an enum constant from reading a static field of its own
	 * class declared after it, and the constants have to be declared after the constants they
	 * describe. A nested class is initialised first, so the enum body can read through it.
	 */
	private static final class Constants {
		static final int NO_LIMIT = -1;
		static final int LAMP_COUNT = 5;
	}

	private final int speedLimit;
	private final LampPattern pattern;

	SignalAspect(int speedLimit, LampPattern pattern) {
		this.speedLimit = speedLimit;
		this.pattern = pattern;
	}

	/**
	 * The speed this aspect permits, or {@link #NO_LIMIT}.
	 *
	 * <p>Defaults only. Real operators differ — 注意 is 45 on much of JR but 55 or 25 elsewhere —
	 * so these are a starting point to be made configurable, not facts about railways in general.
	 */
	public int getSpeedLimit() {
		return speedLimit;
	}

	/** Whether the lamp at {@code position}, counted from 1 at the top, is lit in this aspect. */
	public boolean isLit(int position) {
		return pattern.isLit(position);
	}

	/** The fixed colour of the lamp at {@code position}, counted from 1 at the top. */
	public static LampColour colourAt(int position) {
		return switch (position) {
			case 1, 2, 4 -> LampColour.YELLOW;
			case 3 -> LampColour.RED;
			case 5 -> LampColour.GREEN;
			default -> throw new IllegalArgumentException("No lamp at position " + position);
		};
	}

	/** The colours a lamp can be. Each maps to one model beside {@code light_blank}. */
	public enum LampColour {
		RED, YELLOW, GREEN;

		/** Path of the lit model for this colour, under {@code block/multi_aspect_signal/}. */
		public String litModelName() {
			return "light_lit_" + name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/**
	 * Which lamps an aspect lights, as a bitmask over positions 1 to {@link SignalAspect#LAMP_COUNT}.
	 *
	 * <p>A mask rather than a list because the question asked of it is always "is this one lit",
	 * once per lamp per frame, and because it makes an aspect's whole pattern one comparable value.
	 */
	private record LampPattern(int mask) {

		static LampPattern of(int... litPositions) {
			int mask = 0;
			for (int position : litPositions) {
				if (position < 1 || position > Constants.LAMP_COUNT)
					throw new IllegalArgumentException("No lamp at position " + position);
				mask |= 1 << position;
			}
			return new LampPattern(mask);
		}

		boolean isLit(int position) {
			return (mask & (1 << position)) != 0;
		}
	}
}
