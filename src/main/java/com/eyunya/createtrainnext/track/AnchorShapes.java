package com.eyunya.createtrainnext.track;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The outline of an anchor, swept along whatever angle it actually points.
 *
 * <p>Everything else about an anchor already follows its heading exactly — the rails are drawn by
 * the block entity renderer from the same bezier the graph uses, and the block's own model is
 * empty. The outline was the one part still snapping to the four lattice-safe axes, because it was
 * picked from Create's own {@code TRACK_ORTHO} and {@code TRACK_DIAG}. So a curve running at 17
 * degrees was highlighted as though it ran at 0 or 45, and the box being pointed at disagreed with
 * the track plainly visible inside it.
 *
 * <p>The construction is Create's, generalised. {@code TrackVoxelShapes} builds its bands by
 * sliding a whole block box along the track's axis in two pixel steps and taking the union — the
 * orthogonal one along x or z, the diagonal one corner to corner. The only thing tying that to 45
 * degrees is the direction it slides in. Sliding along the heading gives the same band at any
 * angle, and at exactly 0 or 45 it reproduces Create's own shape.
 *
 * <p>Bands are cached one per degree. {@code getShape} is asked on the render thread for every
 * block in view, so building a union of boxes per call would be paid for continuously; a band is
 * also the same shape either way along itself, so half a turn of buckets covers a whole one.
 */
public final class AnchorShapes {

	/** One per degree. A band is symmetric under a half turn, so this covers every angle. */
	private static final int BUCKETS = 180;

	/**
	 * How far past its own block the band reaches, in pixels.
	 *
	 * <p>Create's own track does this and it is worth keeping: track is four pixels tall, so
	 * without the reach it could only be clicked by aiming at the ground it lies on. Fourteen is
	 * the figure {@code TrackVoxelShapes} uses.
	 */
	private static final double REACH = 14;

	/** Matched to Create's own bands, which slide their box two pixels at a time. */
	private static final double STEP = 2;

	/** Four pixels, as Create's track outlines are. Collision is a separate, shorter slab. */
	private static final double HEIGHT = 4;

	private static final VoxelShape[] CACHE = new VoxelShape[BUCKETS];

	private AnchorShapes() {}

	/** The outline for an anchor pointing along {@code yaw}, in Minecraft's degrees. */
	public static VoxelShape outline(float yaw) {
		int bucket = Math.floorMod(Math.round(yaw), BUCKETS);
		VoxelShape cached = CACHE[bucket];
		if (cached == null)
			CACHE[bucket] = cached = build(bucket);
		return cached;
	}

	private static VoxelShape build(int bucket) {
		// The same mapping AngledTrackAnchorBlockEntity.directionFromYaw uses, so the band lies
		// along the heading rather than across it.
		double radians = Math.toRadians(bucket);
		double dx = -Math.sin(radians) * STEP;
		double dz = Math.cos(radians) * STEP;

		VoxelShape shape = Block.box(0, 0, 0, 16, HEIGHT, 16);
		for (int step = 1; step * STEP <= REACH; step++) {
			double x = dx * step;
			double z = dz * step;
			shape = Shapes.or(shape, Block.box(x, 0, z, 16 + x, HEIGHT, 16 + z),
				Block.box(-x, 0, -z, 16 - x, HEIGHT, 16 - z));
		}
		return shape.optimize();
	}
}
