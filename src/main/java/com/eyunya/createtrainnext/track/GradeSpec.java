package com.eyunya.createtrainnext.track;

/**
 * The grade a player has asked a curve to be laid at, in <em>grade mode</em>.
 *
 * <p>Without it the far end of a curve is wherever the player clicked, height and all, and the
 * climb is whatever that makes. With it the click gives only the place on the ground, and the height
 * of the far end follows from the grade: the curve leaves the start at the climb the route already
 * has there, and works towards {@code permille} by the time it arrives. See
 * {@link CurvePlanner#plan} for how the height and the climb at the far end are worked out.
 *
 * <p>Permille, as a whole number, because that is the unit the player turns the wheel in and reads
 * off the screen; a minus sign is a descent. Zero is a grade like any other: a curve asked to arrive
 * at zero is one that levels off, and ends level.
 *
 * @param permille the grade the curve works towards at its far end, in thousandths of rise over run.
 */
public record GradeSpec(int permille) {

	/** How far the wheel moves the grade in one notch, in permille. */
	public static final int STEP = 5;

	/** The grade as a fraction: rise over run, so 0.035 for 35 permille. */
	public double grade() {
		return permille / 1000.0;
	}
}
