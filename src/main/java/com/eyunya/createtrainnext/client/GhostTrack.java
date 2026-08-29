package com.eyunya.createtrainnext.client;

import com.simibubi.create.content.trains.track.BezierConnection;

import net.createmod.catnip.data.Pair;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.world.phys.Vec3;

/**
 * A stretch of track drawn as an outline, for showing what a click is about to build.
 *
 * <p>Two lines rather than one down the middle, because the width is what makes a curve's direction
 * readable at a glance — a single line through the same points is much harder to tell apart from
 * the one beside it. The sampling follows the same bezier the finished track is cut from, so the
 * ghost and the track occupy the same space.
 *
 * <p>Drawn with catnip's outliner rather than as real track geometry. A preview that looked exactly
 * like finished track would be worse, not better: the useful thing about it is that it reads as
 * not-yet-real, and colouring it by whether the thing can be built turns validity into something
 * seen rather than something read.
 *
 * <p>One instance per thing being previewed. The outliner is a global keyed by object, so two
 * previews sharing a key would draw over each other, and each has to remember how many lines it
 * drew last time — a curve that got shorter leaves lines past its end, which the outliner goes on
 * drawing because nothing has asked it to stop.
 */
public class GhostTrack {

	private final String key;

	private int lastLineCount;

	private boolean drawing;

	public GhostTrack(String key) {
		this.key = key;
	}

	/**
	 * @param intensity how settled the preview is, from 0 to 1. Only thickens the lines; the colour
	 *                  is the caller's, since what counts as valid is not this class's business.
	 */
	public void draw(BezierConnection curve, int colour, float intensity) {
		Vec3 end1 = curve.starts.getFirst();
		Vec3 end2 = curve.starts.getSecond();
		double handle = curve.getHandleLength();
		Vec3 finish1 = end1.add(curve.axes.getFirst()
			.normalize()
			.scale(handle));
		Vec3 finish2 = end2.add(curve.axes.getSecond()
			.normalize()
			.scale(handle));

		Vec3 up = new Vec3(0, 4 / 16f, 0);
		float lineWidth = intensity / 16f + 1 / 16f;
		int segments = Math.max(1, curve.getSegmentCount());

		Vec3 previousLeft = null;
		Vec3 previousRight = null;
		Outliner outliner = Outliner.getInstance();

		for (int i = 0; i <= segments; i++) {
			float t = i / (float) segments;
			Vec3 point = VecHelper.bezier(end1, end2, finish1, finish2, t);
			Vec3 derivative = VecHelper.bezierDerivative(end1, end2, finish1, finish2, t)
				.normalize();
			Vec3 sideways = curve.getNormal(t)
				.cross(derivative)
				.scale(15 / 16f);

			Vec3 left = point.add(sideways)
				.add(up);
			Vec3 right = point.subtract(sideways)
				.add(up);

			if (previousLeft != null) {
				outliner.showLine(Pair.of(key, i * 2), previousLeft, left)
					.colored(colour)
					.disableLineNormals()
					.lineWidth(lineWidth);
				outliner.showLine(Pair.of(key, i * 2 + 1), previousRight, right)
					.colored(colour)
					.disableLineNormals()
					.lineWidth(lineWidth);
			}

			previousLeft = left;
			previousRight = right;
		}

		for (int i = segments + 1; i <= lastLineCount; i++)
			remove(outliner, i);

		lastLineCount = segments;
		drawing = true;
	}

	public void clear() {
		if (!drawing)
			return;
		Outliner outliner = Outliner.getInstance();
		for (int i = 0; i <= lastLineCount; i++)
			remove(outliner, i);
		lastLineCount = 0;
		drawing = false;
	}

	private void remove(Outliner outliner, int segment) {
		outliner.remove(Pair.of(key, segment * 2));
		outliner.remove(Pair.of(key, segment * 2 + 1));
	}
}
