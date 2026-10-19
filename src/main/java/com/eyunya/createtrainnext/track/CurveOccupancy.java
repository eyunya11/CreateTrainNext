package com.eyunya.createtrainnext.track;

import java.util.HashMap;
import java.util.Map;

import com.eyunya.createtrainnext.CTNConfig;
import com.simibubi.create.content.trains.track.BezierConnection;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Which blocks a curve runs through, and how high up each one it runs.
 *
 * <p>Feeds {@link CurveFillerBlock}, and deliberately does not use Create's own
 * {@code BezierConnection.rasterise}. That one thins the result down to a minimal line, dropping
 * every block it can while keeping the shape recognisable, because what it feeds is a marker for
 * mob pathing and the train map — things a one block wide diagonal staircase serves perfectly well.
 * Something to stand on is a different question: thinned like that, a diagonal run leaves the two
 * blocks at each corner empty, and a player walking the line falls through the gap between them.
 *
 * <p>So this walks the curve without thinning, and takes a swathe rather than a line: each sample
 * is spread sideways to cover the width of the track, which is the same block-wide strip Create's
 * straight track occupies. What comes out is a floor the shape of the rails.
 */
public final class CurveOccupancy {

	/** Samples per block of curve. Eight is finer than a block corner can hide in. */
	private static final int SAMPLES_PER_BLOCK = 8;

	/**
	 * How far either side of the centre line to fill, in blocks.
	 *
	 * <p>Just under half a block, so the strip is about one block wide — matching Create's straight
	 * track, whose collision is its whole block. Wider would put a floor beside the rails; narrower
	 * would leave one rail hanging over the edge of it on a diagonal.
	 */
	private static final double HALF_WIDTH = 0.45;

	/** A ceiling on how much one curve may fill, in case a degenerate bezier ever gets this far. */
	private static final int MAX_BLOCKS = 4096;

	private CurveOccupancy() {}

	/**
	 * The blocks this curve passes through, each mapped to the height within it, in pixels, that
	 * the rails run at.
	 *
	 * <p>Where a curve crosses a block at more than one height — which happens on a climb, and at
	 * the very edge of a block on the flat — the lowest wins. Standing slightly below the rails
	 * reads as a rough sleeper; standing above them reads as walking on air.
	 */
	public static Map<BlockPos, Integer> rasterise(BezierConnection curve) {
		Map<BlockPos, Integer> heights = new HashMap<>();

		double length = curve.getLength();
		int samples = Math.max(8, (int) (length * SAMPLES_PER_BLOCK));

		Vec3 previous = null;
		for (int i = 0; i <= samples; i++) {
			double t = i / (double) samples;
			Vec3 point = curve.getPosition(t);

			// The horizontal normal, taken from the step just walked rather than from the bezier's
			// derivative, so the first sample has something to work with either way.
			Vec3 along = previous == null ? curve.getPosition(Math.min(1, t + 1.0 / samples))
				.subtract(point) : point.subtract(previous);
			previous = point;

			Vec3 sideways = new Vec3(-along.z, 0, along.x);
			if (sideways.lengthSqr() < 1.0E-8) {
				put(heights, point);
				continue;
			}
			sideways = sideways.normalize()
				.scale(HALF_WIDTH);

			put(heights, point.subtract(sideways));
			put(heights, point);
			put(heights, point.add(sideways));

			if (heights.size() > MAX_BLOCKS)
				break;
		}

		return heights;
	}

	private static void put(Map<BlockPos, Integer> heights, Vec3 point) {
		int blockY = Mth.floor(point.y);
		BlockPos pos = BlockPos.containing(point.x, blockY, point.z);
		int height = Mth.clamp((int) Math.round((point.y - blockY) * 16), 0, 15);
		heights.merge(pos, height, Math::min);
	}

	/**
	 * Puts the curve's floor down, or renews it where it is already standing, in the chunks that are
	 * loaded.
	 *
	 * <p>Asking a level about a block in a chunk that is not loaded loads it, there and then, and a
	 * long curve crosses a good many chunks. What is not loaded is left: it is put down at the next
	 * refresh once it is.
	 */
	public static void place(Level level, BezierConnection curve) {
		if (level.isClientSide || !CTNConfig.SERVER.curveCollision.get())
			return;
		rasterise(curve).forEach((pos, height) -> {
			if (level.isLoaded(pos))
				CurveFillerBlock.placeOrRenew(level, pos, height);
		});
	}

	/**
	 * Takes the curve's floor away, in the chunks that are loaded. What is left in the others goes by
	 * itself: see {@link CurveFillerBlock}'s upkeep.
	 */
	public static void clear(Level level, BezierConnection curve) {
		if (level.isClientSide)
			return;
		// Not gated on the config: a curve that had a floor when the setting was on still has to be
		// able to lose it, otherwise turning the setting off would strand every floor already laid.
		rasterise(curve).keySet()
			.forEach(pos -> {
				if (level.isLoaded(pos))
					CurveFillerBlock.remove(level, pos);
			});
	}
}
