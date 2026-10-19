package com.eyunya.createtrainnext.signal;

import com.eyunya.createtrainnext.CTNConfig;

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
 * from the signal outwards, stepping up a {@link Ladder}. Whether {@link #RESTRICTED} is one of
 * that ladder's rungs is the signal's own setting — see its note.
 *
 * <p>Each aspect also has a speed it holds a train to, from the config: see {@link SignalSpeeds}.
 */
public enum SignalAspect {

	/** 停止. Red alone. The block ahead is occupied. */
	STOP(LampPattern.of(3)),

	/**
	 * 警戒. Both outer yellows, lamps 1 and 4.
	 *
	 * <p>An extra rung below {@link #APPROACH} rather than a replacement for it, and one a signal
	 * only has if it is told to. Real railways insert it where the block ahead is too short for a
	 * train running at 注意 speed to stop within, which is a fact about the layout rather than
	 * about traffic, so no count of clear blocks could decide it.
	 */
	RESTRICTED(LampPattern.of(1, 4)),

	/** 注意. The lower yellow alone, lamp 4. One block clear; the next signal is at stop. */
	APPROACH(LampPattern.of(4)),

	/** 減速. Upper yellow and green, lamps 2 and 5. Two blocks clear. */
	REDUCED_SPEED(LampPattern.of(2, 5)),

	/** 進行. Green alone, lamp 5. Three or more blocks clear; no restriction of its own. */
	PROCEED(LampPattern.of(5));

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

	private final LampPattern pattern;

	SignalAspect(LampPattern pattern) {
		this.pattern = pattern;
	}

	/**
	 * The speed in km/h this aspect permits, or {@link #NO_LIMIT}. Stop has none of its own.
	 *
	 * <p>From the config, whose defaults are the speeds of much of JR. Real operators differ —
	 * 注意 is 45 on much of JR but 55 or 25 elsewhere — so they are settings and not facts about
	 * railways in general.
	 */
	public int getSpeedLimit() {
		return switch (this) {
			case RESTRICTED -> CTNConfig.SERVER.restrictedSpeed.get();
			case APPROACH -> CTNConfig.SERVER.approachSpeed.get();
			case REDUCED_SPEED -> CTNConfig.SERVER.reducedSpeed.get();
			default -> NO_LIMIT;
		};
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

		RED(0xFF2D23), YELLOW(0xE8D51A), GREEN(0x13D161);

		private final int tint;

		LampColour(int tint) {
			this.tint = tint;
		}

		/** Path of the lit model for this colour, under {@code block/multi_aspect_signal/}. */
		public String litModelName() {
			return "light_lit_" + name().toLowerCase(java.util.Locale.ROOT);
		}

		/**
		 * The colour the halo around a lit lamp is drawn in, packed as RGB.
		 *
		 * <p>The same values the lit lamp textures are filled with, so the halo cannot drift away
		 * from the lamp it surrounds. A halo of a slightly different hue would be worse than none:
		 * the whole point of it is to carry the lamp's colour out to where the lamp itself has
		 * become too small to read.
		 */
		public int glowTint() {
			return tint;
		}
	}

	/**
	 * The order the aspects step through as the line ahead clears, most permissive first.
	 *
	 * <p>警戒 is not a substitute for 注意 but an extra rung below it, so turning it on lengthens
	 * the ladder rather than swapping a step out: a signal three blocks behind a train shows 減速
	 * either way, but two blocks behind it shows 注意 on the short ladder and 警戒 on the long one.
	 * That in turn is why the ladder decides how far ahead there is any point looking — one block
	 * further than it has rungs below 進行 could not change the answer.
	 */
	public enum Ladder {

		/** 進行 → 減速 → 注意 → 停止. */
		WITHOUT_RESTRICTED(PROCEED, REDUCED_SPEED, APPROACH, STOP),

		/** 進行 → 減速 → 注意 → 警戒 → 停止. */
		WITH_RESTRICTED(PROCEED, REDUCED_SPEED, APPROACH, RESTRICTED, STOP);

		private final SignalAspect[] steps;

		Ladder(SignalAspect... steps) {
			this.steps = steps;
		}

		public static Ladder of(boolean withRestricted) {
			return withRestricted ? WITH_RESTRICTED : WITHOUT_RESTRICTED;
		}

		/** The aspect to show with this many blocks clear ahead. */
		public SignalAspect aspectFor(int clearBlocks) {
			// Counted from the restrictive end, which is where the rungs are pinned: zero clear is
			// always 停止, and it is 進行 that moves as the ladder grows.
			return steps[Math.max(0, steps.length - 1 - clearBlocks)];
		}

		/** How far ahead is worth counting. One more than this cannot change the aspect. */
		public int lookahead() {
			return steps.length - 1;
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
