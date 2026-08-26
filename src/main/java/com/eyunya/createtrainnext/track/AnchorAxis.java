package com.eyunya.createtrainnext.track;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;

/**
 * The directions an anchor's stub may run, mirroring the flat subset of Create's {@code TrackShape}.
 *
 * <p>Only these four are possible, and the reason is worth spelling out: graph nodes sit at
 * {@code blockCentre ± axis * 0.5} and {@code TrackNodeLocation} rounds every node onto a
 * half-block lattice, so the offset has to land on that lattice exactly. The vectors here all have
 * components of 0 or ±1, which is what makes {@code ±0.5} come out on it. A 30 degree axis would
 * not, and would be snapped away from the track it belongs to.
 *
 * <p>This is also, precisely, why Create allows 45 degrees and nothing between: the diagonals
 * happen to land on the lattice too.
 *
 * <p>Note the diagonals are deliberately not unit vectors — Create's own {@code TrackShape} axes
 * are the same, and the half-block offset depends on it.
 */
public enum AnchorAxis implements StringRepresentable {

	Z_ORTHO("z_ortho", new Vec3(0, 0, 1)),
	X_ORTHO("x_ortho", new Vec3(1, 0, 0)),
	DIAG("diag", new Vec3(1, 0, 1)),
	DIAG_2("diag_2", new Vec3(-1, 0, 1));

	private final String name;
	private final Vec3 axis;

	AnchorAxis(String name, Vec3 axis) {
		this.name = name;
		this.axis = axis;
	}

	@Override
	public String getSerializedName() {
		return name;
	}

	public Vec3 axis() {
		return axis;
	}

	/** The yaw an anchor placed on this axis starts at, so a fresh anchor is straight, not bent. */
	public float defaultYaw() {
		return (float) Math.toDegrees(Math.atan2(-axis.x, axis.z));
	}

	/**
	 * The one of these lying closest to the given horizontal direction.
	 *
	 * <p>With four to choose from, the stub is never more than 22.5 degrees away from the curve
	 * leaving it — half the gap that the two orthogonal axes alone could manage.
	 */
	public static AnchorAxis nearestTo(Vec3 direction) {
		AnchorAxis best = Z_ORTHO;
		double bestScore = -Double.MAX_VALUE;

		for (AnchorAxis candidate : values()) {
			// Unsigned: an axis serves the curve equally well pointing either way along itself.
			double score = Math.abs(candidate.axis.normalize()
				.dot(direction));
			if (score > bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}
}
