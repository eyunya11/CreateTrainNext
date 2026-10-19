package com.eyunya.createtrainnext.track;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The words for how a curve climbs, in one place so that everything that has to say it says it the
 * same way.
 *
 * <p>Four numbers, which are the ways anyone describes a slope: the grade in permille, how far it
 * takes to gain a block, how far the curve goes along the ground and how far it goes up. A curve that
 * does not climb is only said to be level.
 *
 * <p>The grade shown is the average over the whole curve, rise over run, so that the four numbers
 * agree with one another and anyone can check them by dividing. The limit is held to the steepest
 * point of the curve instead, which on a curve that eases into a climb is more; a refusal says that
 * figure, see {@link CurveWandItem#refusal}.
 */
public final class GradeText {

	/** The most permille that is ever written, so that a wall does not print a nonsense figure. */
	private static final int MOST = 9999;

	private GradeText() {}

	/**
	 * Whether a rise is no rise at all. Nodes sit at the height of their blocks, so a curve rises by a
	 * whole number of blocks; anything under half of one is rounding.
	 */
	public static boolean isLevel(double rise) {
		return Math.abs(rise) < 0.5;
	}

	/** A grade, as a fraction, in whole permille: the figure the limit is compared against. */
	public static int permille(double grade) {
		return (int) Math.max(-MOST, Math.min(MOST, Math.round(grade * 1000)));
	}

	/** What is added to the end of a message about a curve that does not climb. */
	public static MutableComponent level() {
		return Component.translatable("message.createtrainnext.grade.level");
	}

	/**
	 * A line about a curve that climbs or falls: the grade, how far it takes to gain a block, the run
	 * and the rise. The two signs go with the rise, so a descent reads as one.
	 *
	 * @param rise the height gained from one end to the other, in blocks; minus for a descent.
	 * @param run  how far the curve goes along the ground, in blocks.
	 */
	public static MutableComponent climb(double rise, double run) {
		long blocks = Math.round(rise);
		return Component.translatable("message.createtrainnext.grade.climb",
			String.format("%+d", permille(rise / run)),
			String.format("%.1f", run / Math.abs(rise)),
			String.format("%.1f", run),
			String.format("%+d", blocks));
	}
}
