package com.eyunya.createtrainnext.track;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Splits a curve between two fixed tangents into two circular arcs, so that each half is the same
 * curve whichever way round it is read.
 *
 * <p>The reason it is needed is in {@link CurvePlanner#MAX_ORIENTATION_DEVIATION}: Create's bezier
 * is only the same curve from both ends when the tangents at the two ends make equal angles with
 * the chord, and a curve between two anchors that are already pointing somewhere has no say in
 * either tangent. Two circular arcs sharing a tangent where they meet — a biarc — can join any pair
 * of tangents, and each arc is symmetric by construction. All that is needed is a place for them to
 * meet.
 *
 * <h2>Where they meet</h2>
 *
 * <p>The tangent at one end of a circular arc is the mirror image of the one at the other end, the
 * mirror being the chord. So for the junction {@code M}, the first arc is circular when the tangent
 * there is {@code reflect(startHeading, chord S-M)}, and the second when it is
 * {@code reflect(endHeading, chord M-E)}. Both can hold at once only if the two chords differ in
 * direction by half the total turn, which puts {@code M} on a circle through the two ends. Any point
 * of it will do, and there is nothing to choose between them but taste.
 *
 * <p>But an anchor can only go in the centre of a block, so {@code M} cannot be put exactly on that
 * circle, and the two tangents it asks for differ by a small angle. The junction takes the bisector,
 * which leaves each arc a little short of symmetric — by half that angle, which for the nearest block
 * centre is a degree or so, and comes to hundredths of a block. Every candidate is scored by that
 * angle, and the best few are then measured on the real thing rather than trusted.
 */
public final class Biarc {

	/**
	 * Where the two arcs meet: the block to put an anchor in, the point that is the centre of it, and
	 * the way the route runs there.
	 *
	 * @param mismatchDegrees how far apart the tangents asked for by the two arcs were, which is what
	 *                        the heading is a compromise between.
	 */
	public record Junction(BlockPos pos, Vec3 point, Vec3 heading, double mismatchDegrees) {}

	/** Neither arc may be shorter than this, in blocks, or the two anchors would crowd one another. */
	private static final double MIN_ARM = 2.5;

	/** Each arc has to leave in the direction it is going, and not by much less than that. */
	private static final double MIN_FORWARD = 0.2;

	/** How many of the best-scoring junctions are measured on the real curves. */
	private static final int FINALISTS = 8;

	private static final Vec3 UP = new Vec3(0, 1, 0);

	private Biarc() {}

	/**
	 * The junction for a curve from {@code startPoint} to {@code endPoint}, which leaves the first
	 * along {@code startHeading} and arrives at the second along {@code endHeading}, or null if there
	 * is nowhere to put one.
	 *
	 * @param usable whether an anchor may go in a block: it has to be somewhere nothing else is.
	 */
	@Nullable
	public static Junction find(Vec3 startPoint, Vec3 startHeading, Vec3 endPoint, Vec3 endHeading,
		Predicate<BlockPos> usable) {
		Vec3 startTangent = CurvePlanner.flatUnit(startHeading);
		Vec3 endTangent = CurvePlanner.flatUnit(endHeading);

		// The circle the junction belongs on can bulge out past the two ends when the curve turns
		// sharply, so look further afield than the box they make.
		double reach = CurvePlanner.flat(endPoint.subtract(startPoint))
			.length();
		int margin = Math.max(4, (int) Math.ceil(reach / 2));
		int minX = Mth.floor(Math.min(startPoint.x, endPoint.x)) - margin;
		int maxX = Mth.floor(Math.max(startPoint.x, endPoint.x)) + margin;
		int minZ = Mth.floor(Math.min(startPoint.z, endPoint.z)) - margin;
		int maxZ = Mth.floor(Math.max(startPoint.z, endPoint.z)) + margin;

		List<Candidate> candidates = new ArrayList<>();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				Candidate candidate = candidateAt(x, z, startPoint, startTangent, endPoint, endTangent, usable);
				if (candidate != null)
					candidates.add(candidate);
			}
		}

		// A stable sort over a fixed order of blocks, so the client's ghost and the server's build
		// pick the same one.
		candidates.sort(Comparator.comparingDouble(Candidate::score));

		for (int i = 0; i < Math.min(FINALISTS, candidates.size()); i++) {
			Candidate candidate = candidates.get(i);
			Vec3 point = Vec3.atBottomCenterOf(candidate.pos());
			if (deviation(startPoint, startTangent, point, candidate.heading()) > CurvePlanner.MAX_ORIENTATION_DEVIATION)
				continue;
			if (deviation(point, candidate.heading(), endPoint, endTangent) > CurvePlanner.MAX_ORIENTATION_DEVIATION)
				continue;
			return new Junction(candidate.pos(), point, candidate.heading(), Math.toDegrees(candidate.mismatch()));
		}
		return null;
	}

	private record Candidate(BlockPos pos, Vec3 heading, double mismatch, double score) {}

	@Nullable
	private static Candidate candidateAt(int x, int z, Vec3 startPoint, Vec3 startTangent, Vec3 endPoint,
		Vec3 endTangent, Predicate<BlockPos> usable) {
		Vec3 centre = new Vec3(x + 0.5, 0, z + 0.5);
		Vec3 first = CurvePlanner.flat(centre.subtract(startPoint));
		Vec3 second = CurvePlanner.flat(endPoint.subtract(centre));
		double firstLength = first.length();
		double secondLength = second.length();
		if (firstLength < MIN_ARM || secondLength < MIN_ARM)
			return null;

		Vec3 firstChord = first.scale(1 / firstLength);
		Vec3 secondChord = second.scale(1 / secondLength);
		if (firstChord.dot(startTangent) < MIN_FORWARD || secondChord.dot(endTangent) < MIN_FORWARD)
			return null;

		// Level with a proportional share of whatever climb there is, to the nearest block.
		int y = Mth.floor(startPoint.y + (endPoint.y - startPoint.y) * firstLength / (firstLength + secondLength) + 0.5);
		if (CurvePlanner.tooSteep(firstLength, Math.abs(y - startPoint.y))
			|| CurvePlanner.tooSteep(secondLength, Math.abs(endPoint.y - y)))
			return null;

		BlockPos pos = new BlockPos(x, y, z);
		if (!usable.test(pos))
			return null;

		Vec3 fromStart = CurvePlanner.reflect(startTangent, firstChord);
		Vec3 fromEnd = CurvePlanner.reflect(endTangent, secondChord);
		Vec3 sum = fromStart.add(fromEnd);
		if (sum.lengthSqr() < 1.0E-6)
			return null;
		Vec3 heading = sum.normalize();

		double firstTurn = angle(startTangent, heading);
		double secondTurn = angle(heading, endTangent);
		if (firstTurn > Math.toRadians(CurvePlanner.MAX_TURN_DEGREES)
			|| secondTurn > Math.toRadians(CurvePlanner.MAX_TURN_DEGREES))
			return null;
		if (radius(firstLength, firstTurn) < CurvePlanner.MIN_RADIUS
			|| radius(secondLength, secondTurn) < CurvePlanner.MIN_RADIUS)
			return null;

		double mismatch = angle(fromStart, fromEnd);
		// The angle is what matters. Of the junctions that are as good as one another, the one with
		// the two arms most nearly equal makes the gentler curve.
		double score = mismatch + 0.02 * Math.abs(firstLength - secondLength) / (firstLength + secondLength);
		return new Candidate(pos, heading, mismatch, score);
	}

	/** The radius of a circular arc that turns {@code turn} radians across a chord this long. */
	private static double radius(double chord, double turn) {
		return turn < 1.0E-6 ? Double.MAX_VALUE : chord / (2 * Math.sin(turn / 2));
	}

	private static double angle(Vec3 a, Vec3 b) {
		return Math.acos(Mth.clamp(a.dot(b), -1, 1));
	}

	/** {@link CurvePlanner#orientationDeviation} of the curve between two points, as Create would build it. */
	private static double deviation(Vec3 from, Vec3 fromHeading, Vec3 to, Vec3 toHeading) {
		return CurvePlanner.orientationDeviation(new BezierConnection(Couple.create(BlockPos.ZERO, BlockPos.ZERO),
			Couple.create(from, to), Couple.create(fromHeading, toHeading.scale(-1)), Couple.create(UP, UP), true,
			false, TrackMaterial.ANDESITE));
	}
}
