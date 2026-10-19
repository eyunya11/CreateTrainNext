package com.eyunya.createtrainnext.gametest;

import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.BEFORE;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.EAST;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.GRAPH_TICKS;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.NORTH;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.START;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.anchor;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.assertChain;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.assertConsistent;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.click;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.relative;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.route;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.eyunya.createtrainnext.track.CurveRepair;
import com.eyunya.createtrainnext.track.CurveWandItem;
import com.eyunya.createtrainnext.track.GradeText;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks that a route can climb, on a real server level: what the planner works out, what the
 * anchors keep of it, and the graph Create makes from the result.
 *
 * <p>A climb has to be a grade, not a staircase. Joining two heights used to give a curve that was
 * level at both ends and steepest in the middle, and the anchors did not remember it, so carrying a
 * route on from the end of one began from level again. The curves here are built from anchors that
 * hold a slope, so the things asked below are: that the grade along a curve is the one asked for, that
 * the next curve begins where the last one ended, that turning a route round turns the climb round
 * with it and nothing else, and that nothing Create reads off the anchors — the nodes, the axis a
 * signal is placed against, the edges either way — has been left behind.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class SlopeTests {

	private static final String EMPTY = "empty";

	/** Long enough for anchors that have been placed to have been entered in the index the split reads. */
	private static final int INDEX_TICKS = 8;

	/** How many steps along a curve its grade is looked at. */
	private static final int SAMPLES = 48;

	/** One block of rise in six of run: about 167 permille, plain to see and well inside the limit. */
	private static final double SIXTH = 1.0 / 6.0;

	/** Three anchors in a line east, each three higher than the last and eighteen further on: a sixth all the way. */
	private static final BlockPos LOW = new BlockPos(4, 1, 16);
	private static final BlockPos MIDDLE = new BlockPos(22, 4, 16);
	private static final BlockPos HIGH = new BlockPos(40, 7, 16);

	/** A route pointing north three blocks above {@link CurveConsistencyTests#START}'s, for joins across a height. */
	private static final BlockPos HIGH_END = new BlockPos(30, 5, 16);
	private static final BlockPos HIGH_AFTER = new BlockPos(30, 5, 8);

	// ------------------------------------------------------------ the planner

	/**
	 * Two ends with nothing to answer to are joined at one grade from end to end, and the anchors that
	 * are built from the plan hold it.
	 *
	 * <p>This is the property the whole feature is for. Before it, the same two clicks gave a curve
	 * that was level at both ends and half as steep again in the middle.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void twoFreeEndsAtDifferentHeightsAreJoinedAtOneGrade(GameTestHelper helper) {
		BlockPos from = new BlockPos(4, 1, 16);
		BlockPos to = new BlockPos(28, 5, 16);
		AngledTrackAnchorBlockEntity start = anchor(helper, from);
		Level level = helper.getLevel();
		double grade = 4.0 / 24.0;

		CurvePlanner.Plan plan = plan(helper, from, to);
		helper.assertTrue(plan.valid(), "the climb should be possible, but was refused: " + plan.error());
		helper.assertTrue(plan.junction() == null, "a straight climb should not need a point added");
		helper.assertTrue(near(plan.startSlope(), grade, 1.0E-6) && near(plan.endSlope(), grade, 1.0E-6),
			"both ends should climb at " + grade + ", but at " + plan.startSlope() + " and " + plan.endSlope());
		assertSteady(helper, plan.curve(), grade, "the planned curve");

		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the climb should have been built");
		AngledTrackAnchorBlockEntity end = anchorAt(helper, to);
		helper.assertTrue(near(start.getSlope(), grade, 1.0E-6) && near(end.getSlope(), grade, 1.0E-6),
			"the anchors should hold the grade, but hold " + start.getSlope() + " and " + end.getSlope());

		BezierConnection built = start.buildCurveToNext();
		assertSteady(helper, built, grade, "the curve that was built");
		// Preview and result are one call, and they have to stay one picture.
		helper.assertTrue(CurvePlanner.deviation(plan.curve(), built) < 1.0E-3,
			"the curve that was built should be the one that was planned");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, start);
			helper.succeed();
		});
	}

	/** Going down is the same with the other sign. */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aDescentIsAClimbWithTheOtherSign(GameTestHelper helper) {
		BlockPos from = new BlockPos(4, 5, 16);
		BlockPos to = new BlockPos(28, 1, 16);
		AngledTrackAnchorBlockEntity start = anchor(helper, from);
		double grade = -4.0 / 24.0;

		CurvePlanner.Plan plan = plan(helper, from, to);
		helper.assertTrue(plan.valid(), "the descent should be possible, but was refused: " + plan.error());
		helper.assertTrue(near(plan.startSlope(), grade, 1.0E-6) && near(plan.endSlope(), grade, 1.0E-6),
			"both ends should fall at " + grade + ", but at " + plan.startSlope() + " and " + plan.endSlope());
		assertSteady(helper, plan.curve(), grade, "the planned curve");

		helper.assertTrue(CurvePlanner.apply(helper.getLevel(), plan) == null, "the descent should have been built");
		helper.assertTrue(near(start.getSlope(), grade, 1.0E-6), "the anchor should hold the grade, but holds " + start.getSlope());

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, start);
			helper.succeed();
		});
	}

	/**
	 * A curve that leaves a level route and has to climb eases into it: level where it joins what is
	 * there, and steepening towards the end.
	 *
	 * <p>The end is free and takes the slope that gives the gentlest bend, which is half as steep again
	 * as the average where it finishes: it has to make up the ground it did not climb at the start. A
	 * curve that was level at both ends — the old shape — is flat at the finish too.
	 */
	@GameTest(template = EMPTY)
	public static void aCurveLeavingALevelRouteEasesIntoItsClimb(GameTestHelper helper) {
		route(helper, EAST, BEFORE, START);

		CurvePlanner.Plan plan = plan(helper, START, new BlockPos(30, 5, 16));
		helper.assertTrue(plan.valid(), "the curve should be possible, but was refused: " + plan.error());
		helper.assertTrue(plan.junction() == null, "one arc should do, with the free end aimed at it");
		helper.assertTrue(plan.startSlope() == 0.0, "it should leave the level route level, but climbs at " + plan.startSlope());

		BezierConnection curve = plan.curve();
		double average = 3.0 / horizontalLength(curve);
		double leaving = gradeBetween(curve, 0, 1.0 / SAMPLES);
		double arriving = gradeBetween(curve, 1 - 1.0 / SAMPLES, 1);
		helper.assertTrue(leaving < 0.2 * average, "it should leave level, but climbs at " + leaving + " at first");
		helper.assertTrue(arriving > 1.3 * average && arriving < 1.6 * average,
			"it should be climbing half as fast again as the average of " + average + " where it arrives, but is at "
				+ arriving);
		helper.succeed();
	}

	/**
	 * Carrying a climb on keeps its grade: the next curve starts where the last one left off, not from
	 * level.
	 *
	 * <p>Laid with the wand, one click for each curve, which is how it is used. If the anchor at the end
	 * of the first curve did not keep the slope it was left with, the second would climb out of level
	 * ground and the route would be a staircase.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void carryingAClimbOnKeepsItsGrade(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = climbingRoute(helper);

		assertChain(helper, route[0], LOW, MIDDLE, HIGH);
		for (int i = 0; i < route.length; i++)
			helper.assertTrue(near(route[i].getSlope(), SIXTH, 1.0E-6),
				"anchor " + i + " should climb at a sixth, but at " + route[i].getSlope());
		assertSteady(helper, route[0].buildCurveToNext(), SIXTH, "the first curve");
		assertSteady(helper, route[1].buildCurveToNext(), SIXTH, "the curve carried on from it");

		// The joint is a straight line through the middle anchor: what arrives and what leaves, measured
		// the way Create measures them, a quarter of the way in.
		Vec3 arriving = direction(route[0].buildCurveToNext(), 0.75, 1);
		Vec3 leaving = direction(route[1].buildCurveToNext(), 0, 0.25);
		helper.assertTrue(arriving.dot(leaving) > 0.9999, "the curves should meet without a bend, but meet at "
			+ Math.toDegrees(Math.acos(Math.min(1, arriving.dot(leaving)))) + " degrees");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, route[0]);
			helper.succeed();
		});
	}

	/**
	 * A route can be continued backwards out of its first anchor, and then the climb it had is the
	 * climb it goes on with, seen from the other end.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void carryingAClimbOnBackwardsKeepsItsGrade(GameTestHelper helper) {
		anchor(helper, MIDDLE);
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		helper.assertTrue(click(helper, player, wand, MIDDLE).consumesAction(), "setup: the first click should pick the start");
		helper.assertTrue(click(helper, player, wand, HIGH).consumesAction(), "setup: the second click should build the climb");
		AngledTrackAnchorBlockEntity top = anchorAt(helper, HIGH);

		// A fresh wand, because the first one is now waiting at the top; and the first anchor of the route
		// is the one with a free side behind it.
		ItemStack fresh = new ItemStack(CTNRegistry.CURVE_WAND.get());
		helper.assertTrue(click(helper, player, fresh, MIDDLE).consumesAction(), "the first anchor should be a place to start from");
		helper.assertTrue(click(helper, player, fresh, LOW).consumesAction(), "the second click should have carried the route on backwards");

		AngledTrackAnchorBlockEntity bottom = anchorAt(helper, LOW);
		assertChain(helper, bottom, LOW, MIDDLE, HIGH);
		helper.assertTrue(near(bottom.getSlope(), SIXTH, 1.0E-6) && near(top.getSlope(), SIXTH, 1.0E-6),
			"the route should climb a sixth all the way, but the ends climb " + bottom.getSlope() + " and " + top.getSlope());
		helper.assertTrue(bottom.heading()
			.dot(AngledTrackAnchorBlockEntity.directionFromYaw(EAST)) > 0.999, "the new anchor should point east, the way the route runs");
		assertSteady(helper, bottom.buildCurveToNext(), SIXTH, "the curve added behind");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, bottom);
			helper.succeed();
		});
	}

	/**
	 * A gap left between two climbs on one grade is joined by a curve that goes straight on into both,
	 * and a curve that arrives at an anchor takes its grade from the climb that leaves it.
	 *
	 * <p>The far end of a curve is fixed by what is there, as the near end is, but the vector that says
	 * which way the route runs through an anchor points the other way round for a curve that arrives:
	 * a climb read the wrong way is a descent, and the join would dive into the climb it was meant to
	 * meet.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aGapBetweenTwoClimbsIsJoinedAtTheSameGrade(GameTestHelper helper) {
		BlockPos lowStart = new BlockPos(4, 1, 16);
		BlockPos lowEnd = new BlockPos(16, 3, 16);
		BlockPos highStart = new BlockPos(28, 5, 16);
		BlockPos highEnd = new BlockPos(40, 7, 16);
		Player player = helper.makeMockPlayer(GameType.CREATIVE);

		anchor(helper, lowStart);
		ItemStack lower = new ItemStack(CTNRegistry.CURVE_WAND.get());
		helper.assertTrue(click(helper, player, lower, lowStart).consumesAction() && click(helper, player, lower, lowEnd).consumesAction(),
			"setup: the lower climb should have been built");
		anchor(helper, highStart);
		ItemStack upper = new ItemStack(CTNRegistry.CURVE_WAND.get());
		helper.assertTrue(click(helper, player, upper, highStart).consumesAction() && click(helper, player, upper, highEnd).consumesAction(),
			"setup: the upper climb should have been built");

		CurvePlanner.Plan plan = plan(helper, lowEnd, highStart);
		helper.assertTrue(plan.valid(), "the join should be possible, but was refused: " + plan.error());
		helper.assertTrue(plan.junction() == null, "a straight join should not need a point added");
		helper.assertTrue(near(plan.startSlope(), SIXTH, 1.0E-6) && near(plan.endSlope(), SIXTH, 1.0E-6),
			"the join should leave and arrive at a sixth, but does at " + plan.startSlope() + " and " + plan.endSlope());

		ItemStack joiner = new ItemStack(CTNRegistry.CURVE_WAND.get());
		helper.assertTrue(click(helper, player, joiner, lowEnd).consumesAction() && click(helper, player, joiner, highStart).consumesAction(),
			"the join should have been built");

		AngledTrackAnchorBlockEntity first = anchorAt(helper, lowStart);
		assertChain(helper, first, lowStart, lowEnd, highStart, highEnd);
		for (BlockPos each : new BlockPos[] { lowStart, lowEnd, highStart, highEnd })
			helper.assertTrue(near(anchorAt(helper, each).getSlope(), SIXTH, 1.0E-6),
				"the anchor at " + each.toShortString() + " should still climb a sixth, but at " + anchorAt(helper, each).getSlope());
		assertSteady(helper, anchorAt(helper, lowEnd).buildCurveToNext(), SIXTH, "the join");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, first);
			helper.succeed();
		});
	}

	// ------------------------------------------------------------------ limit

	/**
	 * One block of rise in three of run is as steep as a curve may be, and no steeper: the limit the
	 * mod has always had, now measured as a grade.
	 */
	@GameTest(template = EMPTY)
	public static void oneBlockInThreeIsTheSteepestAllowed(GameTestHelper helper) {
		BlockPos from = new BlockPos(4, 1, 16);
		anchor(helper, from);

		// Five up over fifteen is 333 permille, which is the limit and not over it.
		CurvePlanner.Plan exactly = plan(helper, from, new BlockPos(19, 6, 16));
		helper.assertTrue(exactly.valid(), "five blocks of rise in fifteen should be allowed, but: " + exactly.error());

		// Five over fourteen is 357.
		CurvePlanner.Plan steeper = plan(helper, from, new BlockPos(18, 6, 16));
		helper.assertTrue("too_steep".equals(steeper.error()),
			"five blocks of rise in fourteen should be too steep, but: " + steeper.error());
		helper.succeed();
	}

	/**
	 * The limit is {@code curves.maxGrade}, so moving it moves the boundary.
	 *
	 * <p>The setting is put back whether or not the checks pass: the tests share one server.
	 */
	@GameTest(template = EMPTY)
	public static void theLimitFollowsTheConfig(GameTestHelper helper) {
		BlockPos from = new BlockPos(4, 1, 16);
		anchor(helper, from);
		int original = CTNConfig.SERVER.maxGrade.get();

		try {
			CTNConfig.SERVER.maxGrade.set(250);
			helper.assertTrue("too_steep".equals(plan(helper, from, new BlockPos(19, 6, 16)).error()),
				"333 permille should be refused when the limit is 250");
			CurvePlanner.Plan level = plan(helper, from, new BlockPos(24, 6, 16));
			helper.assertTrue(level.valid(), "250 permille should be allowed when the limit is 250, but: " + level.error());

			CTNConfig.SERVER.maxGrade.set(400);
			CurvePlanner.Plan steeper = plan(helper, from, new BlockPos(18, 6, 16));
			helper.assertTrue(steeper.valid(), "357 permille should be allowed when the limit is 400, but: " + steeper.error());
		} finally {
			CTNConfig.SERVER.maxGrade.set(original);
		}
		helper.succeed();
	}

	// -------------------------------------------------------- what an anchor keeps

	/**
	 * Turning a route round turns the climb round with it and changes nothing that anything outside
	 * the anchors can see: the curves are where they were, and so is the axis a signal is placed
	 * against.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void turningAClimbRoundTurnsItsGradeRound(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = climbingRoute(helper);
		Level level = helper.getLevel();

		BezierConnection lowToMiddle = route[0].buildCurveToNext();
		BezierConnection middleToHigh = route[1].buildCurveToNext();
		Vec3[] axes = new Vec3[route.length];
		Vec3[] entries = new Vec3[route.length];
		Vec3[] exits = new Vec3[route.length];
		for (int i = 0; i < route.length; i++) {
			axes[i] = reportedAxis(level, route[i]);
			entries[i] = route[i].entryNode();
			exits[i] = route[i].exitNode();
		}

		helper.assertTrue(route[1].reverseRoute() == route.length, "the whole route should have been turned round");

		for (int i = 0; i < route.length; i++) {
			helper.assertTrue(near(route[i].getSlope(), -SIXTH, 1.0E-6),
				"anchor " + i + " should fall a sixth running the other way, but climbs " + route[i].getSlope());
			helper.assertTrue(reportedAxis(level, route[i]).distanceTo(axes[i]) < 1.0E-4,
				"anchor " + i + " reported " + axes[i] + " and now reports " + reportedAxis(level, route[i]));
			helper.assertTrue(route[i].entryNode()
				.distanceTo(exits[i]) < 1.0E-9
				&& route[i].exitNode()
					.distanceTo(entries[i]) < 1.0E-9,
				"anchor " + i + " should keep its nodes where they were, entry and exit swapped");
		}

		// The route now runs high, middle, low. Each curve is the old one run backwards.
		helper.assertTrue(CurvePlanner.deviation(route[2].buildCurveToNext(), middleToHigh.secondary()) < 1.0E-3,
			"the curve from the top should be the one that came down to it");
		helper.assertTrue(CurvePlanner.deviation(route[1].buildCurveToNext(), lowToMiddle.secondary()) < 1.0E-3,
			"the curve from the middle should be the one that came up to it");

		helper.assertTrue(route[1].reverseRoute() == route.length, "and a second time");
		for (int i = 0; i < route.length; i++)
			helper.assertTrue(near(route[i].getSlope(), SIXTH, 1.0E-6),
				"anchor " + i + " should climb a sixth again, but climbs " + route[i].getSlope());

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, route[0]);
			helper.succeed();
		});
	}

	/**
	 * The grade is saved, and a save made before there was one reads as level.
	 *
	 * <p>Level anchors write nothing for it, so worlds from before this and anchors that never climbed
	 * are the same on disk as they were.
	 */
	@GameTest(template = EMPTY)
	public static void theGradeIsSavedAndAnOldSaveReadsAsLevel(GameTestHelper helper) {
		Level level = helper.getLevel();
		AngledTrackAnchorBlockEntity low = anchor(helper, new BlockPos(4, 2, 4));
		AngledTrackAnchorBlockEntity high = anchor(helper, new BlockPos(12, 3, 4));
		Vec3 aim = AngledTrackAnchorBlockEntity.tangentOf(AngledTrackAnchorBlockEntity.directionFromYaw(EAST), 0.125);
		low.linkTo(high, aim, aim);
		helper.assertTrue(low.getSlope() == 0.125F && high.getSlope() == 0.125F,
			"setup: both anchors should be climbing an eighth, but are at " + low.getSlope() + " and " + high.getSlope());

		CompoundTag saved = low.saveWithFullMetadata(level.registryAccess());
		helper.assertTrue(saved.contains("Slope"), "the grade should be written");
		helper.assertTrue(reload(helper, low, saved).getSlope() == 0.125F, "the grade should come back from a save");

		CompoundTag old = saved.copy();
		old.remove("Slope");
		helper.assertTrue(reload(helper, low, old).getSlope() == 0.0F, "a save with no grade should read as level");

		AngledTrackAnchorBlockEntity lone = anchor(helper, new BlockPos(30, 2, 4));
		helper.assertTrue(!lone.saveWithFullMetadata(level.registryAccess())
			.contains("Slope"), "an anchor that is level should write nothing for it");
		helper.succeed();
	}

	/**
	 * An anchor that climbs has its nodes at the centre of its block, whichever way it points. A level
	 * end that points along an axis keeps its half block of span either side, which is what lets it
	 * meet Create's own track.
	 */
	@GameTest(template = EMPTY)
	public static void aClimbingAnchorHasItsNodesAtTheCentreOfItsBlock(GameTestHelper helper) {
		Vec3 east = AngledTrackAnchorBlockEntity.directionFromYaw(EAST);
		Vec3 climb = AngledTrackAnchorBlockEntity.tangentOf(east, 0.125);

		AngledTrackAnchorBlockEntity low = anchor(helper, new BlockPos(4, 2, 4));
		AngledTrackAnchorBlockEntity high = anchor(helper, new BlockPos(12, 3, 4));
		low.linkTo(high, climb, climb);
		for (AngledTrackAnchorBlockEntity each : new AngledTrackAnchorBlockEntity[] { low, high }) {
			Vec3 centre = Vec3.atBottomCenterOf(each.getBlockPos());
			helper.assertTrue(each.entryNode()
				.distanceTo(centre) < 1.0E-9
				&& each.exitNode()
					.distanceTo(centre) < 1.0E-9,
				"the anchor at " + each.getBlockPos()
					.toShortString() + " climbs and should have both nodes at the centre of its block");
		}

		AngledTrackAnchorBlockEntity flatStart = anchor(helper, new BlockPos(4, 2, 12));
		AngledTrackAnchorBlockEntity flatEnd = anchor(helper, new BlockPos(12, 2, 12));
		flatStart.linkTo(flatEnd, east, east);
		helper.assertTrue(flatStart.exitNode()
			.distanceTo(Vec3.atBottomCenterOf(flatStart.getBlockPos())) > 0.4,
			"a level end on an axis should keep its node on the edge of its block");
		helper.succeed();
	}

	// ----------------------------------------------------------- two arcs and more

	/**
	 * Two routes that point different ways at different heights are joined by two arcs, and the anchor
	 * between them climbs at the grade of the whole join.
	 *
	 * <p>The arcs are the same two curves in the plan, and so as consistent either way round as they
	 * were on level ground: what is checked at the end is the graph, edge against drawn curve.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void aJoinAcrossAHeightIsTwoArcsWithAClimbingAnchorBetweenThem(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] low = route(helper, EAST, BEFORE, START);
		route(helper, NORTH, HIGH_END, HIGH_AFTER);
		Level level = helper.getLevel();

		CurvePlanner.Plan plan = plan(helper, START, HIGH_END);
		helper.assertTrue(plan.valid(), "the join should be possible, but was refused: " + plan.error());
		helper.assertTrue(plan.junction() != null && plan.curves()
			.size() == 2, "it should be built as two arcs with a point between them");

		Vec3 from = plan.curve().starts.getFirst();
		Vec3 between = plan.curve().starts.getSecond();
		Vec3 to = plan.secondCurve().starts.getSecond();
		double expected = (to.y - from.y) / (horizontal(from, between) + horizontal(between, to));
		helper.assertTrue(near(plan.junctionSlope(), expected, 1.0E-4),
			"the point between should climb at the grade of the whole join, " + expected + ", but at " + plan.junctionSlope());

		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the join should have been built");
		BlockPos middle = relative(helper, plan.junction()
			.pos());
		assertChain(helper, low[0], BEFORE, START, middle, HIGH_END, HIGH_AFTER);

		AngledTrackAnchorBlockEntity joint = anchorAt(helper, middle);
		helper.assertTrue(near(joint.getSlope(), expected, 1.0E-4),
			"the anchor between the arcs should climb at " + expected + ", but at " + joint.getSlope());
		helper.assertTrue(low[1].getSlope() == 0.0F && anchorAt(helper, HIGH_END).getSlope() == 0.0F,
			"the routes that were there should be as level as they were");

		helper.assertTrue(CurvePlanner.deviation(plan.curve(), low[1].buildCurveToNext()) < 1.0E-3
			&& CurvePlanner.deviation(plan.secondCurve(), joint.buildCurveToNext()) < 1.0E-3,
			"the arcs that were built should be the ones that were planned");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, low[0]);
			helper.succeed();
		});
	}

	/**
	 * A link between two heights that was built before there were slopes — one curve, level at both
	 * ends — is mended into two arcs on one grade, and the anchors that were there are not touched.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 100)
	public static void anOldLinkAcrossAHeightIsMendedOnOneGrade(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] low = route(helper, EAST, BEFORE, START);
		AngledTrackAnchorBlockEntity[] high = route(helper, NORTH, HIGH_END, HIGH_AFTER);
		low[1].linkTo(high[0], null, null);
		Level level = helper.getLevel();

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			CurveRepair.Link link = CurveRepair.linkFrom(low[1]);
			helper.assertTrue(link != null && link.overLimit(), "setup: the link should be one that differs read the other way round");

			CurveRepair.Outcome outcome = CurveRepair.repair(level, link);
			helper.assertTrue(outcome.repaired(), "the link should have been mended, but: " + outcome.error());

			BlockPos middle = relative(helper, outcome.junction());
			assertChain(helper, low[0], BEFORE, START, middle, HIGH_END, HIGH_AFTER);
			AngledTrackAnchorBlockEntity joint = anchorAt(helper, middle);

			Vec3 from = low[1].exitNode();
			Vec3 between = joint.entryNode();
			Vec3 to = high[0].entryNode();
			double expected = (to.y - from.y) / (horizontal(from, between) + horizontal(between, to));
			helper.assertTrue(near(joint.getSlope(), expected, 1.0E-4),
				"the point between the arcs should climb at the grade of the whole link, " + expected + ", but at "
					+ joint.getSlope());
			helper.assertTrue(low[1].getSlope() == 0.0F && high[0].getSlope() == 0.0F,
				"the anchors that were there should be as level as they were");

			helper.runAfterDelay(GRAPH_TICKS, () -> {
				assertConsistent(helper, low[0]);
				helper.succeed();
			});
		});
	}

	/**
	 * A point put part way along a climb takes the grade the curve has there, so the two halves go on
	 * climbing instead of levelling off where they meet.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void aPointPutOnAClimbTakesItsGrade(GameTestHelper helper) {
		anchor(helper, LOW);
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		helper.assertTrue(click(helper, player, wand, LOW).consumesAction(), "setup: the first click should pick the start");
		helper.assertTrue(click(helper, player, wand, MIDDLE).consumesAction(), "setup: the second click should build the climb");
		AngledTrackAnchorBlockEntity bottom = anchorAt(helper, LOW);

		helper.runAfterDelay(INDEX_TICKS, () -> {
			Level level = helper.getLevel();
			Vec3 halfway = helper.absoluteVec(new Vec3(13.5, 2.5, 16.5));

			CurvePlanner.SplitTarget target = CurvePlanner.findCurveNear(level, halfway, 2.0);
			helper.assertTrue(target != null && target.valid(),
				"there should be somewhere to put a point on the climb: " + (target == null ? "no curve found" : target.error()));
			helper.assertTrue(near(target.slope(), SIXTH, 1.0E-4), "the curve climbs a sixth there, but the target says " + target.slope());

			helper.assertTrue(CurvePlanner.split(level, target), "the point should have been put on the curve");
			BlockPos split = relative(helper, target.anchorPos());
			assertChain(helper, bottom, LOW, split, MIDDLE);
			helper.assertTrue(near(anchorAt(helper, split).getSlope(), SIXTH, 1.0E-4),
				"the new anchor should climb a sixth, but at " + anchorAt(helper, split).getSlope());

			helper.runAfterDelay(GRAPH_TICKS, () -> {
				assertConsistent(helper, bottom);
				helper.succeed();
			});
		});
	}

	// -------------------------------------------------------------- signals

	/**
	 * A signal can be put on a climb, facing either way along it.
	 *
	 * <p>Create places one by comparing the axis the track block reports with the direction of the
	 * edges in the graph, and both are three dimensional: an anchor on a climb that reported a level
	 * axis would be refused at every grade.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aSignalCanBePlacedFacingEitherWayOnAClimb(GameTestHelper helper) {
		climbingRoute(helper);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			Level level = helper.getLevel();
			BlockPos low = helper.absolutePos(LOW);
			BlockPos middle = helper.absolutePos(MIDDLE);
			BlockPos high = helper.absolutePos(HIGH);

			// An end has one edge, on whichever side its axis points.
			helper.assertTrue(locate(level, low, true) != null || locate(level, low, false) != null,
				"a signal should go on the first anchor of the climb");
			helper.assertTrue(locate(level, high, true) != null || locate(level, high, false) != null,
				"a signal should go on the last anchor of the climb");

			TrackGraphLocation front = locate(level, middle, true);
			TrackGraphLocation back = locate(level, middle, false);
			helper.assertTrue(front != null && back != null,
				"a signal should go on either side of the anchor in the middle of the climb");
			helper.assertTrue(!sameEdge(front, back), "the two sides should be two different edges, or this proves nothing");
			helper.succeed();
		});
	}

	// ------------------------------------------------------- what is said

	/**
	 * A plan knows how far it rises and how far it runs, which are the numbers its message is made of,
	 * and how steep it is at its steepest, which is what it is refused on.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aPlanSaysHowFarItRisesAndRuns(GameTestHelper helper) {
		BlockPos from = new BlockPos(4, 1, 16);
		anchor(helper, from);

		CurvePlanner.Plan straight = plan(helper, from, new BlockPos(28, 5, 16));
		helper.assertTrue(straight.valid(), "the climb should be possible, but was refused: " + straight.error());
		helper.assertTrue(straight.rise() == 4.0, "it should rise four blocks, but rises " + straight.rise());
		helper.assertTrue(near(straight.horizontalLength(), 24.0, 0.01),
			"it should run 24 blocks along the ground, but runs " + straight.horizontalLength());
		helper.assertTrue(near(straight.steepestGrade(), 4.0 / 24.0, 1.0E-4),
			"a straight climb is as steep everywhere as it is on average, but is " + straight.steepestGrade());
		helper.assertTrue(straight.length() > straight.horizontalLength(),
			"the curve is longer than its run, since it climbs as well");

		// Going down is a negative rise over the same run.
		anchor(helper, new BlockPos(4, 5, 8));
		CurvePlanner.Plan descent = plan(helper, new BlockPos(4, 5, 8), new BlockPos(28, 1, 8));
		helper.assertTrue(descent.rise() == -4.0, "it should fall four blocks, but rises " + descent.rise());
		helper.assertTrue(near(descent.steepestGrade(), 4.0 / 24.0, 1.0E-4),
			"the steepest point of a descent is a positive grade too, but is " + descent.steepestGrade());

		// Two arcs are one link: the rise is that of the whole, the run is both arcs, and the steepest point
		// is the steeper of them.
		route(helper, EAST, BEFORE, START);
		route(helper, NORTH, HIGH_END, HIGH_AFTER);
		CurvePlanner.Plan arcs = plan(helper, START, HIGH_END);
		helper.assertTrue(arcs.valid() && arcs.curves()
			.size() == 2, "setup: the join should be two arcs, but: " + arcs.error());
		helper.assertTrue(arcs.rise() == 3.0, "the whole join should rise three blocks, but rises " + arcs.rise());
		double both = CurvePlanner.horizontalLength(arcs.curve()) + CurvePlanner.horizontalLength(arcs.secondCurve());
		helper.assertTrue(near(arcs.horizontalLength(), both, 1.0E-9),
			"the run of the join should be that of both arcs, " + both + ", but is " + arcs.horizontalLength());
		helper.assertTrue(arcs.steepestGrade() >= arcs.rise() / arcs.horizontalLength(),
			"the steepest point cannot be gentler than the average");
		helper.succeed();
	}

	/** The line about a climb is made of the plan's four numbers, signed the way the climb goes. */
	@GameTest(template = EMPTY)
	public static void theLineAboutAClimbIsMadeOfItsFourNumbers(GameTestHelper helper) {
		TranslatableContents up = translatable(helper, GradeText.climb(3, 100));
		helper.assertTrue("message.createtrainnext.grade.climb".equals(up.getKey()), "a climb should use the climb text");
		helper.assertTrue(Arrays.equals(up.getArgs(),
			new Object[] { String.format("%+d", 30), String.format("%.1f", 100.0 / 3), String.format("%.1f", 100.0),
				String.format("%+d", 3) }),
			"the numbers should be 30 permille, one in 33.3, a run of 100 and a rise of 3, but are "
				+ Arrays.toString(up.getArgs()));

		TranslatableContents down = translatable(helper, GradeText.climb(-3, 100));
		helper.assertTrue(Arrays.equals(down.getArgs(),
			new Object[] { String.format("%+d", -30), String.format("%.1f", 100.0 / 3), String.format("%.1f", 100.0),
				String.format("%+d", -3) }),
			"a descent should carry the minus signs, but the numbers are " + Arrays.toString(down.getArgs()));

		// The grade is rounded to the permille it is compared with, not cut short: two in three is 667.
		helper.assertTrue(String.format("%+d", 667).equals(translatable(helper, GradeText.climb(2, 3)).getArgs()[0]),
			"two blocks in three should read as 667 permille");

		helper.assertTrue(GradeText.isLevel(0.0) && GradeText.isLevel(0.2) && !GradeText.isLevel(1.0)
			&& !GradeText.isLevel(-1.0), "only no rise at all is level, whichever way a rise goes");
		helper.assertTrue("message.createtrainnext.grade.level".equals(translatable(helper, GradeText.level()).getKey()),
			"a level curve should say it is level");
		helper.succeed();
	}

	/**
	 * A climb that is too steep is refused with how steep it is and what the limit is, and any other
	 * refusal is only its reason.
	 */
	@GameTest(template = EMPTY)
	public static void aClimbThatIsTooSteepIsToldHowSteepAndWhatTheLimitIs(GameTestHelper helper) {
		BlockPos from = new BlockPos(4, 1, 16);
		anchor(helper, from);

		// Five up over fourteen: 357 permille against the limit of 333.
		CurvePlanner.Plan steep = plan(helper, from, new BlockPos(18, 6, 16));
		helper.assertTrue("too_steep".equals(steep.error()), "setup: the climb should be too steep, but: " + steep.error());
		TranslatableContents said = translatable(helper, CurveWandItem.refusal(steep));
		helper.assertTrue("message.createtrainnext.wand.too_steep_by".equals(said.getKey()),
			"it should say how steep, but uses " + said.getKey());
		helper.assertTrue(Arrays.equals(said.getArgs(), new Object[] { 357, CurvePlanner.maxGradePermille() }),
			"it should say 357 against the limit, but says " + Arrays.toString(said.getArgs()));

		CurvePlanner.Plan close = plan(helper, from, new BlockPos(5, 1, 16));
		TranslatableContents other = translatable(helper, CurveWandItem.refusal(close));
		helper.assertTrue(("message.createtrainnext.wand." + close.error()).equals(other.getKey()) && other.getArgs().length == 0,
			"any other refusal should be its reason alone, but is " + other.getKey() + " with "
				+ Arrays.toString(other.getArgs()));
		helper.succeed();
	}

	// --------------------------------------------------------------- helpers

	private static TranslatableContents translatable(GameTestHelper helper, Component component) {
		helper.assertTrue(component.getContents() instanceof TranslatableContents, "the text should be a translation");
		return (TranslatableContents) component.getContents();
	}

	/**
	 * {@link #LOW}, {@link #MIDDLE} and {@link #HIGH}, laid with the wand from a lone anchor at the
	 * first: three anchors on one grade, the way a player makes them.
	 */
	private static AngledTrackAnchorBlockEntity[] climbingRoute(GameTestHelper helper) {
		anchor(helper, LOW);
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		helper.assertTrue(click(helper, player, wand, LOW).consumesAction(), "setup: the first click should pick the start");
		helper.assertTrue(click(helper, player, wand, MIDDLE).consumesAction(), "setup: the second click should build the first curve");
		helper.assertTrue(click(helper, player, wand, HIGH).consumesAction(), "setup: the third click should carry it on");
		return new AngledTrackAnchorBlockEntity[] { anchorAt(helper, LOW), anchorAt(helper, MIDDLE), anchorAt(helper, HIGH) };
	}

	private static CurvePlanner.Plan plan(GameTestHelper helper, BlockPos start, BlockPos end) {
		return CurvePlanner.plan(helper.getLevel(), helper.absolutePos(start), helper.absolutePos(end), Direction.UP, 0);
	}

	private static AngledTrackAnchorBlockEntity anchorAt(GameTestHelper helper, BlockPos relative) {
		BlockEntity entity = helper.getBlockEntity(relative);
		helper.assertTrue(entity instanceof AngledTrackAnchorBlockEntity, "there should be an anchor at " + relative);
		return (AngledTrackAnchorBlockEntity) entity;
	}

	/** A block entity read back from a tag, as the world does it. */
	private static AngledTrackAnchorBlockEntity reload(GameTestHelper helper, AngledTrackAnchorBlockEntity original,
		CompoundTag tag) {
		BlockEntity loaded = BlockEntity.loadStatic(original.getBlockPos(), original.getBlockState(), tag,
			helper.getLevel()
				.registryAccess());
		helper.assertTrue(loaded instanceof AngledTrackAnchorBlockEntity, "the saved anchor should load again");
		return (AngledTrackAnchorBlockEntity) loaded;
	}

	/** The axis Create gets when it asks the block, which is what placement is compared against. */
	private static Vec3 reportedAxis(Level level, AngledTrackAnchorBlockEntity anchor) {
		BlockPos pos = anchor.getBlockPos();
		BlockState state = level.getBlockState(pos);
		return ((ITrackBlock) state.getBlock()).getTrackAxes(level, pos, state)
			.get(0);
	}

	/** Where a signal placed on one side of the block at {@code pos} would go, or null if nowhere. */
	@Nullable
	private static TrackGraphLocation locate(Level level, BlockPos pos, boolean front) {
		AtomicReference<TrackGraphLocation> found = new AtomicReference<>();
		TrackTargetingBlockItem.withGraphLocation(level, pos, front, null, EdgePointType.SIGNAL,
			(overlap, location) -> found.set(location));
		return found.get();
	}

	private static boolean sameEdge(TrackGraphLocation a, TrackGraphLocation b) {
		return a.edge.getFirst()
			.equals(b.edge.getFirst())
			&& a.edge.getSecond()
				.equals(b.edge.getSecond());
	}

	/** Checks that a curve climbs at {@code grade} along the whole of it, give or take a hundredth of that. */
	private static void assertSteady(GameTestHelper helper, BezierConnection curve, double grade, String what) {
		double least = Double.MAX_VALUE;
		double greatest = -Double.MAX_VALUE;
		for (int i = 0; i < SAMPLES; i++) {
			double each = gradeBetween(curve, i / (double) SAMPLES, (i + 1) / (double) SAMPLES);
			least = Math.min(least, each);
			greatest = Math.max(greatest, each);
		}
		double tolerance = 0.01 * Math.abs(grade);
		helper.assertTrue(Math.abs(least - grade) <= tolerance && Math.abs(greatest - grade) <= tolerance,
			what + " should climb at " + grade + " all along, but runs from " + least + " to " + greatest);
	}

	/** Rise over run, the run measured from above, between two points of a curve. */
	private static double gradeBetween(BezierConnection curve, double from, double to) {
		Vec3 a = curve.getPosition(from);
		Vec3 b = curve.getPosition(to);
		return (b.y - a.y) / horizontal(a, b);
	}

	private static double horizontalLength(BezierConnection curve) {
		double length = 0;
		Vec3 previous = curve.getPosition(0);
		for (int i = 1; i <= SAMPLES; i++) {
			Vec3 point = curve.getPosition(i / (double) SAMPLES);
			length += horizontal(previous, point);
			previous = point;
		}
		return length;
	}

	private static double horizontal(Vec3 a, Vec3 b) {
		return Math.hypot(b.x - a.x, b.z - a.z);
	}

	/** Which way a curve runs between two points of it, as the unit vector Create would measure. */
	private static Vec3 direction(BezierConnection curve, double from, double to) {
		return curve.getPosition(to)
			.subtract(curve.getPosition(from))
			.normalize();
	}

	private static boolean near(double a, double b, double tolerance) {
		return Math.abs(a - b) <= tolerance;
	}
}
