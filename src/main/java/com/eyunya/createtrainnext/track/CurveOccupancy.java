package com.eyunya.createtrainnext.track;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.eyunya.createtrainnext.CTNConfig;
import com.simibubi.create.content.trains.track.BezierConnection;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

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
 *
 * <p>And not one height per block but one per column of a {@link #CELLS} by {@link #CELLS} grid in it. A
 * curve that climbs at 45 degrees rises a block in every block it crosses, so one height per block was a
 * step of most of a block from one to the next, under the rails by most of a block at the high side of
 * each. A column a quarter of a block across rises a quarter of a block, which is a step anyone walks up
 * and never far from the rails.
 */
public final class CurveOccupancy {

	/** How many columns the floor of a block is divided into each way. */
	public static final int CELLS = 4;

	/** The height of a column the curve does not pass over, which holds nothing up. */
	public static final byte EMPTY = -1;

	/** Samples per block of curve: two to every column it crosses on the flat, and more on a climb. */
	private static final int SAMPLES_PER_BLOCK = 8;

	/** Where across the track each sample is taken, as a fraction of {@link #HALF_WIDTH}: at least one per column. */
	private static final double[] ACROSS = { -1, -0.5, 0, 0.5, 1 };

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
	 * The blocks this curve passes through, each mapped to the heights within it, in pixels, that the rails
	 * run at over each of its columns: {@code CELLS * CELLS} of them, x along a row and rows along z,
	 * {@link #EMPTY} where the curve does not pass.
	 *
	 * <p>Where a curve crosses a column at more than one height — which happens on a climb, and at the very
	 * edge of a block on the flat — the lowest wins. Standing slightly below the rails reads as a rough
	 * sleeper; standing above them reads as walking on air.
	 */
	public static Map<BlockPos, byte[]> rasterise(BezierConnection curve) {
		Map<BlockPos, byte[]> heights = new HashMap<>();

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

			for (double across : ACROSS)
				put(heights, point.add(sideways.scale(across)));

			if (heights.size() > MAX_BLOCKS)
				break;
		}

		// A climb crosses the top of a block part way over a column, and leaves a piece of floor at the
		// bottom of the block above in the same column. The lowest wins there as well: taken from above, the
		// floor would step up by a slab more than the column rises, at every block the climb crosses.
		Map<BlockPos, byte[]> under = new HashMap<>();
		heights.forEach((pos, floor) -> under.put(pos, floor.clone()));
		heights.forEach((pos, floor) -> {
			byte[] below = under.get(pos.below());
			if (below != null)
				for (int i = 0; i < floor.length; i++)
					if (below[i] != EMPTY)
						floor[i] = EMPTY;
		});
		heights.values()
			.removeIf(CurveOccupancy::isEmpty);
		return heights;
	}

	private static boolean isEmpty(byte[] floor) {
		for (byte height : floor)
			if (height != EMPTY)
				return false;
		return true;
	}

	private static void put(Map<BlockPos, byte[]> heights, Vec3 point) {
		int blockY = Mth.floor(point.y);
		BlockPos pos = BlockPos.containing(point.x, blockY, point.z);
		byte height = (byte) Mth.clamp((int) Math.round((point.y - blockY) * 16), 0, 15);
		int column = column(point.x - pos.getX()) + column(point.z - pos.getZ()) * CELLS;
		byte[] floor = heights.computeIfAbsent(pos, p -> empty());
		if (floor[column] == EMPTY || height < floor[column])
			floor[column] = height;
	}

	private static int column(double within) {
		return Mth.clamp((int) Math.floor(within * CELLS), 0, CELLS - 1);
	}

	private static byte[] empty() {
		byte[] floor = new byte[CELLS * CELLS];
		Arrays.fill(floor, EMPTY);
		return floor;
	}

	/**
	 * What a floor holds up: a two pixel slab over each column it has, at the column's height, as Create's
	 * straight track has over its whole block.
	 */
	public static VoxelShape shapeOf(byte[] floor) {
		VoxelShape built = Shapes.empty();
		int cell = 16 / CELLS;
		for (int z = 0; z < CELLS; z++)
			for (int x = 0; x < CELLS; x++) {
				int height = floor[x + z * CELLS];
				if (height == EMPTY)
					continue;
				built = Shapes.or(built, Block.box(x * cell, height, z * cell, (x + 1) * cell, height + 2, (z + 1) * cell));
			}
		return built.optimize();
	}

	/** The lowest of a floor's columns, or 0 for one with none. */
	public static int lowest(byte[] floor) {
		int lowest = Integer.MAX_VALUE;
		for (byte height : floor)
			if (height != EMPTY)
				lowest = Math.min(lowest, height);
		return lowest == Integer.MAX_VALUE ? 0 : lowest;
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
		rasterise(curve).forEach((pos, floor) -> {
			if (level.isLoaded(pos))
				CurveFillerBlock.placeOrRenew(level, pos, floor);
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
