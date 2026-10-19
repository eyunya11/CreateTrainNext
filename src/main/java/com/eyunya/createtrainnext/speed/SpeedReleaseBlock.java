package com.eyunya.createtrainnext.speed;

/**
 * The sign that ends a speed limit: a limit sign with no number, whose limit is the highest there
 * is.
 *
 * <p>It is the same block in every other respect — placed against a piece of track, turned in
 * 22.5 degree steps, backed by the same block entity and the same kind of point in the track
 * graph — so it is a subclass only to be told apart. {@link SpeedLimitBlockEntity} asks which it
 * is, and the renderer draws the plate from its own model rather than writing a number on one.
 */
public class SpeedReleaseBlock extends SpeedLimitBlock {

	public SpeedReleaseBlock(Properties properties) {
		super(properties);
	}
}
