package com.eyunya.createtrainnext.signal;

import java.util.List;

import com.eyunya.createtrainnext.CTNConfig;

/**
 * The aspects a Japanese multi-lamp signal can display.
 *
 * <p>An aspect is what the signal says; which lamps say it depends on the head, and is {@link SignalType}'s
 * to answer. The five-aspect head is where the names come from. Its lamp column is fixed, read top to
 * bottom: yellow, yellow, red, yellow, green. That arrangement is not arbitrary — it exists so the
 * two-lamp aspects come out vertically separated and cannot be mistaken for each other at a distance.
 * {@link #RESTRICTED} lights lamps 1 and 4, two yellows with the red between them; {@link #REDUCED_SPEED}
 * lights lamps 2 and 5, a yellow high and the green low.
 *
 * <p>Which aspect a signal shows follows from how far ahead the line is clear, counted in blocks
 * from the signal outwards, stepping up a {@link Ladder}. Every type of head has a ladder of its own.
 *
 * <p>Each aspect also has a speed it holds a train to, from the config: see {@link SignalSpeeds}.
 */
public enum SignalAspect {

	/** 停止. Red alone. The block ahead is occupied. */
	STOP,

	/**
	 * 警戒. Both outer yellows, lamps 1 and 4 of the five-aspect, the only head that has it.
	 *
	 * <p>A rung below {@link #APPROACH}. Real railways insert it where the block ahead is too short for a
	 * train running at 注意 speed to stop within.
	 */
	RESTRICTED,

	/** 注意. A yellow alone. One block clear; the next signal is at stop. */
	APPROACH,

	/** 減速. A yellow and a green together. Two blocks clear on the five-aspect. */
	REDUCED_SPEED,

	/**
	 * 進行. A green alone. Three or more blocks clear on the four-aspect, four on the five-aspect; no
	 * restriction of its own, except on the six-aspect, where a line this clear is still not clear enough
	 * to run at full speed.
	 */
	PROCEED,

	/**
	 * 高速進行. Two greens, lamps 2 and 6 of the six-aspect, the only head that has it. The line is clear
	 * as far as the head can see, and a train is held to nothing at all, which lifts the limit that 進行
	 * set. Last of the aspects so that it counts as the least restrictive.
	 */
	HIGH_SPEED_PROCEED;

	/** Stands in for "run at whatever the line allows" rather than a speed this signal imposes. */
	public static final int NO_LIMIT = -1;

	/**
	 * The speed in km/h this aspect permits, or {@link #NO_LIMIT}. Stop has none of its own, and neither
	 * has 進行, whose speed depends on the head: see {@link SignalType#proceedSpeed()}.
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
	 * <p>The rungs are pinned at the restrictive end: zero clear is always 停止, and it is the permissive
	 * end that moves as the ladder grows. A head with more lamps has more rungs, so it takes a longer
	 * clear run to reach its first. That is also why the ladder decides how far ahead there is any point
	 * looking — one block further than it has rungs below the first could not change the answer.
	 */
	public enum Ladder {

		/** 進行 → 停止. */
		TWO(PROCEED, STOP),

		/** 進行 → 注意 → 停止. */
		THREE(PROCEED, APPROACH, STOP),

		/** 進行 → 減速 → 注意 → 停止. */
		FOUR(PROCEED, REDUCED_SPEED, APPROACH, STOP),

		/** 進行 → 減速 → 注意 → 警戒 → 停止. */
		FIVE(PROCEED, REDUCED_SPEED, APPROACH, RESTRICTED, STOP),

		/** 高速進行 → 進行 → 減速 → 注意 → 停止. */
		SIX(HIGH_SPEED_PROCEED, PROCEED, REDUCED_SPEED, APPROACH, STOP);

		private final SignalAspect[] steps;

		Ladder(SignalAspect... steps) {
			this.steps = steps;
		}

		/** The aspect to show with this many blocks clear ahead. */
		public SignalAspect aspectFor(int clearBlocks) {
			return steps[Math.max(0, steps.length - 1 - clearBlocks)];
		}

		/** How far ahead is worth counting. One more than this cannot change the aspect. */
		public int lookahead() {
			return steps.length - 1;
		}

		/** The aspects on this ladder, most permissive first. */
		public List<SignalAspect> aspects() {
			return List.of(steps);
		}
	}
}
