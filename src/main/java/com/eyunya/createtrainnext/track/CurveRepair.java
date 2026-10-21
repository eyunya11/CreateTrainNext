package com.eyunya.createtrainnext.track;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNRegistry;
import com.simibubi.create.content.trains.track.BezierConnection;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Mends links that were built as one curve and are a different shape read the other way round.
 *
 * <p>The wand no longer builds those — see {@link CurvePlanner#MAX_ORIENTATION_DEVIATION} — but a
 * world made before it stopped has them, and each is a place where the graph's edge for one
 * direction of travel is not where the rails are drawn. What it takes is what the planner does for a
 * new curve between two fixed tangents, done to one that is already there: an anchor goes between
 * the two, and the link becomes two circular arcs that meet at it. See {@link Biarc}.
 *
 * <p>No anchor moves or turns. Both keep their place and their heading, so the track either side of
 * the link is exactly as it was and the new arcs leave and arrive along the same tangents; what
 * changes is the path between the two, which is the part that was wrong, and it stays close to where
 * it was drawn — a little under half a block, for east into north across twenty blocks. Signals and
 * stations stand on anchors, so they stay where they are, and the anchors keep the way they report
 * their axis through the same {@link AngledTrackAnchorBlockEntity#linkTo} that everything else uses.
 *
 * <p>The arcs are held to what the planner holds a new curve to: each has to be one a train will
 * take, measured against the track already on the far side of the anchor it starts or ends at, and
 * so does the point where they meet. A link for which they would not be is left as it was, rather
 * than traded for one a train cannot cross.
 */
public final class CurveRepair {

	private CurveRepair() {}

	/**
	 * A link between two anchors, as the anchor that owns it sees it.
	 *
	 * @param deviation how far the curve strays from itself read the other way round, in blocks.
	 */
	public record Link(AngledTrackAnchorBlockEntity from, BlockPos to, double length, double deviation) {

		/** Whether this is a link the planner would not build as one curve. */
		public boolean overLimit() {
			return deviation > CurvePlanner.MAX_ORIENTATION_DEVIATION;
		}
	}

	/**
	 * What came of mending one link.
	 *
	 * @param error    why it was left as it is, as a key like the planner's, or null if it was mended.
	 * @param junction where the anchor between the two arcs went, if it was mended.
	 * @param moved    how far the rails are from where they were, at the furthest point, in blocks. The
	 *                 two ends stay where they are and so do their headings; it is the path between
	 *                 them that changes, and this is how much.
	 */
	public record Outcome(Link link, @Nullable String error, @Nullable BlockPos junction, double moved) {

		static Outcome left(Link link, String error) {
			return new Outcome(link, error, null, 0);
		}

		public boolean repaired() {
			return error == null;
		}
	}

	/**
	 * A run of repairs.
	 *
	 * @param remaining how many links over the limit were not tried, because the run had mended as many
	 *                  as it was allowed to.
	 */
	public record Batch(List<Outcome> outcomes, int remaining) {}

	/**
	 * The most links one run mends. Each is a handful of changes to the graph, and Create works out
	 * where a new edge crosses every other edge in the world, so the time one takes grows with the
	 * size of the network; a run that has no limit can hold the server up for as long as it has links.
	 */
	public static final int MAX_PER_RUN = 16;

	/**
	 * Every link loaded within {@code radius} blocks of {@code centre}, the most different from itself
	 * first. A link is placed where the anchor that owns it is.
	 *
	 * <p>Links whose far anchor is not loaded cannot be measured and are left out.
	 */
	public static List<Link> links(Level level, Vec3 centre, double radius) {
		List<Link> links = new ArrayList<>();
		for (AngledTrackAnchorBlockEntity anchor : AnchorIndex.in(level)) {
			if (Vec3.atCenterOf(anchor.getBlockPos())
				.distanceTo(centre) > radius)
				continue;

			Link link = linkFrom(anchor);
			if (link != null)
				links.add(link);
		}
		links.sort(Comparator.comparingDouble(Link::deviation)
			.reversed());
		return links;
	}

	/** The link this anchor owns, or null if it has none that can be measured. */
	@Nullable
	public static Link linkFrom(AngledTrackAnchorBlockEntity anchor) {
		BezierConnection curve = anchor.buildCurveToNext();
		if (curve == null)
			return null;
		return new Link(anchor, anchor.getNext(), curve.getLength(), CurvePlanner.orientationDeviation(curve));
	}

	/**
	 * Mends the links within {@code radius} blocks of {@code centre} that are over the limit, the
	 * worst first, up to {@code limit} of them, and says what became of each. Server side only.
	 *
	 * <p>A link that cannot be mended does not count against the limit, since it costs next to nothing
	 * and would otherwise keep the ones behind it from ever being reached.
	 *
	 * <p>Trains on or beside a link that is mended are derailed, as they are when track is broken
	 * under them: the nodes at its ends and at the anchors next to them are taken out of the graph
	 * and put back.
	 */
	public static Batch repairAll(Level level, Vec3 centre, double radius, int limit) {
		List<Outcome> outcomes = new ArrayList<>();
		int mended = 0;
		int remaining = 0;
		for (Link link : links(level, centre, radius)) {
			if (!link.overLimit())
				continue;
			if (mended >= limit) {
				remaining++;
				continue;
			}

			Outcome outcome = repair(level, link);
			outcomes.add(outcome);
			if (outcome.repaired())
				mended++;
		}
		return new Batch(outcomes, remaining);
	}

	/**
	 * Mends one link, or says why it was left alone. Nothing is changed when a reason comes back.
	 *
	 * <p>The reasons are the planner's where they are the same question — {@code uneven} for no block
	 * to put the anchor in, {@code too_sharp} and {@code too_steep} for an arc a train would not take —
	 * and {@code not_loaded}, {@code links_disagree} and {@code fine} for a link that could not be
	 * looked at or does not need it. {@code has_points} is a link with a signal or a station on it.
	 */
	public static Outcome repair(Level level, Link link) {
		if (level.isClientSide)
			return Outcome.left(link, "client");

		AngledTrackAnchorBlockEntity from = link.from();
		BlockPos toPos = from.getNext();
		if (toPos == null || !level.isLoaded(toPos)
			|| !(level.getBlockEntity(toPos) instanceof AngledTrackAnchorBlockEntity to))
			return Outcome.left(link, "not_loaded");
		if (!from.getBlockPos()
			.equals(to.getPrev()))
			return Outcome.left(link, "links_disagree");

		BezierConnection single = from.buildCurveToNext();
		if (single == null)
			return Outcome.left(link, "not_loaded");
		if (CurvePlanner.orientationDeviation(single) <= CurvePlanner.MAX_ORIENTATION_DEVIATION)
			return Outcome.left(link, "fine");
		// Mending replaces the curve with two arcs, and anything standing on it by its ends would
		// be left pointing at a curve that is no longer there.
		if (CurvePlanner.carriesPoints(level, single))
			return Outcome.left(link, "has_points");

		// The ends of the curve as the anchors report them now. Neither moves: linking through an
		// anchor in the middle changes the other side of each anchor, not the side this curve is on.
		Vec3 startPoint = from.exitNode();
		Vec3 endPoint = to.entryNode();
		Vec3 startHeading = from.heading();
		Vec3 endHeading = to.heading();

		Biarc.Junction junction = Biarc.find(startPoint, startHeading, endPoint, endHeading,
			pos -> CurvePlanner.junctionUsable(level, pos));
		if (junction == null)
			return Outcome.left(link, "uneven");

		// The two ends keep whatever climb they have. The anchor between the arcs climbs at the slope of
		// the whole link, as the planner gives one when it splits a curve, so a link between two
		// heights is two arcs on the same grade rather than a flat spot where they meet.
		double run = CurvePlanner.flat(junction.point()
			.subtract(startPoint))
			.length()
			+ CurvePlanner.flat(endPoint.subtract(junction.point()))
				.length();
		double junctionSlope = (float) ((endPoint.y - startPoint.y) / run);

		BezierConnection first = CurvePlanner.curveBetween(from.getBlockPos(), junction.pos(), startPoint,
			junction.point(), startHeading, junction.heading(), from.getSlope(), junctionSlope);
		BezierConnection second = CurvePlanner.curveBetween(junction.pos(), toPos, junction.point(), endPoint,
			junction.heading(), endHeading, junctionSlope, to.getSlope());

		// Each arc against the track already on the far side of the anchor it starts or ends at. An
		// anchor with a curve on both sides is asked about the one that is staying: the link being
		// replaced is the other side, and is not what the new arc has to line up with.
		Vec3 arriving = from.getPrev() == null ? null
			: CurvePlanner.adjoiningDirection(from, false, CurvePlanner.Role.START);
		Vec3 leaving = to.getNext() == null ? null : CurvePlanner.adjoiningDirection(to, true, CurvePlanner.Role.END);

		String error = CurvePlanner.validateCurve(first, startPoint, junction.point(), startHeading,
			junction.heading(), arriving, null);
		if (error == null)
			error = CurvePlanner.validateCurve(second, junction.point(), endPoint, junction.heading(), endHeading,
				null, leaving);
		if (error == null)
			error = CurvePlanner.validateJoint(first, second);
		if (error != null)
			return Outcome.left(link, error);

		double moved = distanceBetween(single, first, second);

		// The same two links the planner makes for a curve built as two arcs. The first takes the far
		// anchor off this one, so it is held in `to` already, and the second puts it back.
		CurvePlanner.placeAnchor(level, junction.pos(), junction.heading());
		if (!(level.getBlockEntity(junction.pos()) instanceof AngledTrackAnchorBlockEntity middle)) {
			// Nothing is linked yet, so taking out what was placed leaves the link as it was.
			if (level.getBlockState(junction.pos())
				.is(CTNRegistry.ANGLED_TRACK_ANCHOR.get()))
				level.removeBlock(junction.pos(), false);
			return Outcome.left(link, "no_room");
		}

		// Whether the far anchor is docked onto a slope goes with it too: between the two links it has
		// no curve, and is not.
		boolean toDocked = to.isSlopeDocked();
		from.linkTo(middle, null, AngledTrackAnchorBlockEntity.tangentOf(junction.heading(), junctionSlope));
		middle.linkTo(to, null, null, false, toDocked);
		return new Outcome(link, null, junction.pos(), moved);
	}

	/**
	 * How far the two arcs are from the curve they replace: the furthest a point on either is from
	 * the other, in blocks.
	 */
	private static double distanceBetween(BezierConnection single, BezierConnection first, BezierConnection second) {
		int samples = 120;
		Vec3[] before = new Vec3[samples + 1];
		Vec3[] after = new Vec3[samples + 1];
		for (int i = 0; i <= samples; i++) {
			before[i] = single.getPosition(i / (double) samples);
			// The arcs run end to end: the first half of the samples is the first arc and the rest the
			// second, joined at the point where they meet.
			after[i] = i <= samples / 2 ? first.getPosition(i / (double) (samples / 2))
				: second.getPosition((i - samples / 2) / (double) (samples / 2));
		}
		return CurvePlanner.deviation(before, after);
	}
}
