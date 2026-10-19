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
import com.eyunya.createtrainnext.track.ApplyGradePacket;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.eyunya.createtrainnext.track.CurveRepair;
import com.eyunya.createtrainnext.track.CurveWandItem;
import com.eyunya.createtrainnext.track.CurveWandSelection;
import com.eyunya.createtrainnext.track.GradeSpec;
import com.eyunya.createtrainnext.track.GradeText;
import com.eyunya.createtrainnext.track.SetCurveGradePacket;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
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

	// ------------------------------------------------------------ grade mode

	/**
	 * In grade mode the height of the far end comes from the grade, and not from where the click was.
	 *
	 * <p>A curve that leaves level ground and is to arrive at 150 permille over a run of twenty-four
	 * blocks has handles eight long, so it rises {@code 1.5 · 8 · 0.15 = 1.8}, which is two blocks, and
	 * arrives at two over twelve: the climb it was asked for, less nothing, since two was the nearest whole
	 * number. The slope changes at a steady rate along it, so halfway it is the mean of the two ends'.
	 */
	@GameTest(template = EMPTY)
	public static void gradeModeTakesTheFarEndsHeightFromTheGrade(GameTestHelper helper) {
		route(helper, EAST, BEFORE, START);
		GradeSpec spec = new GradeSpec(150);

		CurvePlanner.Plan plan = plan(helper, START, new BlockPos(34, 2, 26), spec);
		helper.assertTrue(plan.valid(), "the climb should be possible, but was refused: " + plan.error());
		helper.assertTrue(plan.grade() != null && plan.grade()
			.permille() == 150, "the plan should say it was laid at 150 permille");
		helper.assertTrue(plan.junction() == null, "one arc should do");

		double handle = CurvePlanner.levelHandleLength(plan.curve());
		int rise = (int) Math.round(1.5 * handle * 0.15);
		helper.assertTrue(rise == 2, "setup: the grade should come to a rise of two blocks, but comes to " + rise);
		helper.assertTrue(relative(helper, plan.end()
			.anchorPos()).getY() == START.getY() + rise,
			"the far end should be two above the start, but is at " + relative(helper, plan.end()
				.anchorPos()));
		helper.assertTrue(plan.startSlope() == 0.0, "it should leave the level route level, but climbs " + plan.startSlope());
		helper.assertTrue(near(plan.endSlope(), rise / (1.5 * handle), 1.0E-6),
			"it should arrive at " + rise / (1.5 * handle) + ", but arrives at " + plan.endSlope());
		helper.assertTrue(near(plan.rise(), rise, 1.0E-9), "the plan should rise two blocks, but rises " + plan.rise());
		helper.assertTrue(near(gradeBetween(plan.curve(), 0.45, 0.55), plan.endSlope() / 2, 0.01),
			"halfway it should be climbing at the mean of its two ends, but is at "
				+ gradeBetween(plan.curve(), 0.45, 0.55));

		// The click is only where on the ground. Aimed a long way above, the curve is the same one.
		CurvePlanner.Plan high = plan(helper, START, new BlockPos(34, 6, 26), spec);
		helper.assertTrue(high.valid() && high.end()
			.anchorPos()
			.equals(plan.end()
				.anchorPos()) && near(high.endSlope(), plan.endSlope(), 1.0E-9),
			"the height that was clicked should have no say, but the curve changed");

		// And without the mode, the height clicked is the height got, as it always was.
		CurvePlanner.Plan plain = plan(helper, START, new BlockPos(34, 6, 26));
		helper.assertTrue(plain.grade() == null, "a plan made without the mode should not say it was graded");
		helper.assertTrue(relative(helper, plain.end()
			.anchorPos()).getY() == 6, "without the mode the far end should be where it was clicked");
		helper.succeed();
	}

	/** A negative grade is a descent: the far end is lower, and the curve arrives going down. */
	@GameTest(template = EMPTY)
	public static void gradeModeCanDescend(GameTestHelper helper) {
		BlockPos from = new BlockPos(4, 5, 16);
		anchor(helper, from);

		CurvePlanner.Plan plan = plan(helper, from, new BlockPos(28, 5, 16), new GradeSpec(-150));
		helper.assertTrue(plan.valid(), "the descent should be possible, but was refused: " + plan.error());

		double handle = CurvePlanner.levelHandleLength(plan.curve());
		int rise = (int) Math.round(1.5 * handle * -0.15);
		helper.assertTrue(rise == -2, "setup: the grade should come to a fall of two blocks, but comes to " + rise);
		helper.assertTrue(relative(helper, plan.end()
			.anchorPos()).getY() == from.getY() + rise, "the far end should be two below the start");
		helper.assertTrue(plan.endSlope() < 0 && near(plan.endSlope(), rise / (1.5 * handle), 1.0E-6),
			"it should arrive going down at " + rise / (1.5 * handle) + ", but arrives at " + plan.endSlope());
		helper.succeed();
	}

	/**
	 * A route laid at a grade climbs at one grade: each curve starts from the slope the last one arrived
	 * with, and from the second curve on the slope no longer changes along it.
	 *
	 * <p>Laid with the wand, one click for each curve, the grade set the way the wheel sets it. The curves
	 * are twelve blocks, so their handles are four, and 170 permille comes to a rise of 1.02 from level:
	 * one block, arriving at a sixth. From a sixth the rise for the next curve is {@code 6 · (1/6 + 0.17)},
	 * just over two, and two blocks over twelve is a sixth again — so every curve after the first is a
	 * straight climb at the slope it arrives with, which is the slope the next one starts from.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aRouteLaidAtAGradeClimbsAtOneGrade(GameTestHelper helper) {
		BlockPos[] clicks = { new BlockPos(4, 1, 8), new BlockPos(16, 1, 8), new BlockPos(28, 1, 8), new BlockPos(40, 1, 8) };
		int[] heights = { 1, 2, 4, 6 };
		AngledTrackAnchorBlockEntity first = anchor(helper, clicks[0]);
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		player.setItemInHand(InteractionHand.MAIN_HAND, wand);

		helper.assertTrue(click(helper, player, wand, clicks[0]).consumesAction(), "setup: the first click should pick the start");
		helper.assertTrue(SetCurveGradePacket.apply(player, 170, true), "setup: the grade should have been put on the selection");

		for (int i = 1; i < clicks.length; i++) {
			helper.assertTrue(click(helper, player, wand, clicks[i]).consumesAction(), "curve " + i + " should have been built");
			CurveWandSelection carried = CurveWandItem.selectionIn(wand, helper.getLevel());
			helper.assertTrue(carried != null && carried.gradeMode() && carried.grade() == 170,
				"the grade should be carried on from curve " + i + ", but the selection is " + carried);
		}

		BlockPos[] placed = new BlockPos[clicks.length];
		for (int i = 0; i < clicks.length; i++)
			placed[i] = new BlockPos(clicks[i].getX(), heights[i], clicks[i].getZ());
		assertChain(helper, first, placed);

		double sixth = 1.0 / 6.0;
		helper.assertTrue(near(first.getSlope(), 0.0, 1.0E-4), "the route should start level, but starts at " + first.getSlope());
		for (int i = 1; i < placed.length; i++)
			helper.assertTrue(near(anchorAt(helper, placed[i]).getSlope(), sixth, 1.0E-6),
				"anchor " + i + " should arrive at a sixth, but arrives at " + anchorAt(helper, placed[i]).getSlope());
		assertSteady(helper, anchorAt(helper, placed[1]).buildCurveToNext(), sixth, "the second curve");
		assertSteady(helper, anchorAt(helper, placed[2]).buildCurveToNext(), sixth, "the third curve");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, first);
			helper.succeed();
		});
	}

	/**
	 * A curve asked to arrive level does: the route levels off, ends flat, and the mode is dropped.
	 *
	 * <p>From a sixth, a curve fifteen blocks long has handles five long and rises
	 * {@code 1.5 · 5 · (1/6 + 0) = 1.25}, which is one block. Arriving at nothing from a sixth over a
	 * rise of one block would take handles four long, so with the rounding as it falls the curve would
	 * arrive a thirtieth of the way into a descent. The far slope is exactly nothing instead, so that
	 * what is carried on from it is a level end.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void askingForLevelLevelsTheRouteOffAndDropsTheMode(GameTestHelper helper) {
		BlockPos[] clicks = { new BlockPos(4, 1, 8), new BlockPos(16, 1, 8), new BlockPos(28, 1, 8), new BlockPos(43, 1, 8) };
		AngledTrackAnchorBlockEntity first = anchor(helper, clicks[0]);
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		player.setItemInHand(InteractionHand.MAIN_HAND, wand);

		helper.assertTrue(click(helper, player, wand, clicks[0]).consumesAction(), "setup: the first click should pick the start");
		helper.assertTrue(SetCurveGradePacket.apply(player, 170, true), "setup: the grade should have been put on the selection");
		helper.assertTrue(click(helper, player, wand, clicks[1]).consumesAction() && click(helper, player, wand, clicks[2]).consumesAction(),
			"setup: the climb should have been built");

		// Now the wheel back to level, which from a climb leaves the mode on.
		helper.assertTrue(SetCurveGradePacket.apply(player, 0, true), "setup: the grade should have been put on the selection");
		CurveWandSelection leveling = CurveWandItem.selectionIn(wand, helper.getLevel());
		helper.assertTrue(leveling != null && leveling.gradeMode() && leveling.grade() == 0,
			"from a climb, asking for nothing should leave the mode on to level off, but the selection is " + leveling);

		helper.assertTrue(click(helper, player, wand, clicks[3]).consumesAction(), "the level-off should have been built");
		BlockPos top = new BlockPos(43, 5, 8);
		assertChain(helper, first, clicks[0], new BlockPos(16, 2, 8), new BlockPos(28, 4, 8), top);

		AngledTrackAnchorBlockEntity end = anchorAt(helper, top);
		helper.assertTrue(end.getSlope() == 0.0, "the route should end level, but ends at " + end.getSlope());
		CurveWandSelection after = CurveWandItem.selectionIn(wand, helper.getLevel());
		helper.assertTrue(after != null && !after.gradeMode() && after.grade() == 0,
			"the mode should be dropped once the route has levelled off, but the selection is " + after);

		BezierConnection levelling = anchorAt(helper, new BlockPos(28, 4, 8)).buildCurveToNext();
		helper.assertTrue(steepest(levelling) <= 1.0 / 6.0 * 1.02,
			"levelling off should never be steeper than the climb it ends, but is " + steepest(levelling));

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, first);
			helper.succeed();
		});
	}

	/**
	 * From level ground, asking for level asks for nothing, so the mode goes off rather than being left
	 * on; the wheel and the server come to the same answer.
	 */
	@GameTest(template = EMPTY)
	public static void askingForLevelFromLevelTurnsTheModeOff(GameTestHelper helper) {
		BlockPos from = new BlockPos(4, 1, 16);
		anchor(helper, from);
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		player.setItemInHand(InteractionHand.MAIN_HAND, wand);

		helper.assertTrue(!SetCurveGradePacket.apply(player, 35, true), "with nothing selected there is nothing to put a grade on");
		helper.assertTrue(click(helper, player, wand, from).consumesAction(), "setup: the first click should pick the start");
		helper.assertTrue(CurvePlanner.startsLevel(helper.getLevel(), helper.absolutePos(from)), "setup: a lone anchor starts level");

		SetCurveGradePacket.apply(player, 35, true);
		CurveWandSelection on = CurveWandItem.selectionIn(wand, helper.getLevel());
		helper.assertTrue(on != null && on.gradeMode() && on.grade() == 35, "35 permille should have been set, but: " + on);

		SetCurveGradePacket.apply(player, 0, true);
		CurveWandSelection off = CurveWandItem.selectionIn(wand, helper.getLevel());
		helper.assertTrue(off != null && !off.gradeMode() && off.grade() == 0,
			"level from level should turn the mode off, but: " + off);

		// A mode that is off has no grade, whatever it was sent.
		SetCurveGradePacket.apply(player, 80, false);
		CurveWandSelection none = CurveWandItem.selectionIn(wand, helper.getLevel());
		helper.assertTrue(none != null && !none.gradeMode() && none.grade() == 0, "an off mode should keep no grade, but: " + none);
		helper.succeed();
	}

	/**
	 * The grade is held to the limit when it is set, and what comes of a limit that moves.
	 *
	 * <p>The server does not take the client's word for it, and the setting is put back whether or not the
	 * checks pass: the tests share one server.
	 */
	@GameTest(template = EMPTY)
	public static void theGradeIsHeldToTheLimit(GameTestHelper helper) {
		BlockPos from = new BlockPos(4, 1, 16);
		anchor(helper, from);
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		player.setItemInHand(InteractionHand.MAIN_HAND, wand);
		helper.assertTrue(click(helper, player, wand, from).consumesAction(), "setup: the first click should pick the start");
		int original = CTNConfig.SERVER.maxGrade.get();

		try {
			SetCurveGradePacket.apply(player, 5000, true);
			helper.assertTrue(CurveWandItem.selectionIn(wand, helper.getLevel())
				.grade() == original, "a grade over the limit should be held to it, but is "
					+ CurveWandItem.selectionIn(wand, helper.getLevel())
						.grade());

			CTNConfig.SERVER.maxGrade.set(100);
			SetCurveGradePacket.apply(player, -5000, true);
			helper.assertTrue(CurveWandItem.selectionIn(wand, helper.getLevel())
				.grade() == -100, "the limit should follow the setting, but a descent is held to "
					+ CurveWandItem.selectionIn(wand, helper.getLevel())
						.grade());
		} finally {
			CTNConfig.SERVER.maxGrade.set(original);
		}
		helper.succeed();
	}

	/**
	 * A far end that already has a height is not moved: the mode has nothing to act on, and the curve is
	 * the one it always was.
	 */
	@GameTest(template = EMPTY)
	public static void gradeModeDoesNotMoveAnExistingAnchor(GameTestHelper helper) {
		route(helper, EAST, BEFORE, START);
		BlockPos there = new BlockPos(34, 4, 26);
		anchor(helper, there);

		CurvePlanner.Plan plain = plan(helper, START, there);
		CurvePlanner.Plan graded = plan(helper, START, there, new GradeSpec(150));
		helper.assertTrue(graded.valid() && plain.valid(), "setup: the join should be possible: " + graded.error() + ", " + plain.error());
		helper.assertTrue(graded.grade() == null, "the mode should not claim to have acted on an anchor that is where it is");
		helper.assertTrue(graded.end()
			.anchorPos()
			.equals(helper.absolutePos(there)), "the anchor should not have been moved");
		helper.assertTrue(graded.startSlope() == plain.startSlope() && graded.endSlope() == plain.endSlope()
			&& CurvePlanner.deviation(graded.curve(), plain.curve()) < 1.0E-9, "the curve should be the one made without the mode");
		helper.succeed();
	}

	/**
	 * The height a grade gives the far end has to be somewhere an anchor can go, and the block that was
	 * clicked does not: it is the ground the curve arrives over, not the place.
	 */
	@GameTest(template = EMPTY)
	public static void gradeModeIsRefusedWhereTheHeightItGivesHasNoRoom(GameTestHelper helper) {
		route(helper, EAST, BEFORE, START);
		GradeSpec spec = new GradeSpec(150);
		BlockPos click = new BlockPos(34, 2, 26);
		BlockPos arrival = new BlockPos(34, 4, 26);

		// The block clicked, and the one above it where an anchor would go without the mode, being in the
		// way says nothing about where the anchor goes with it.
		helper.setBlock(click, Blocks.STONE);
		helper.setBlock(click.above(), Blocks.STONE);
		helper.assertTrue("no_room".equals(plan(helper, START, click).error()),
			"setup: without the mode a block in the way of the anchor should refuse it");
		CurvePlanner.Plan overGround = plan(helper, START, click, spec);
		helper.assertTrue(overGround.valid(), "a block at the height clicked should not matter, but: " + overGround.error());
		helper.assertTrue(relative(helper, overGround.end()
			.anchorPos()).getY() == 4, "the far end should still be two above the start, at " + relative(helper, overGround.end()
				.anchorPos()));

		helper.setBlock(arrival, Blocks.STONE);
		CurvePlanner.Plan blocked = plan(helper, START, click, spec);
		helper.assertTrue("no_room".equals(blocked.error()), "a block where the anchor would go should refuse it, but: " + blocked.error());
		helper.assertTrue(blocked.curve() != null, "the curve should still be there to be drawn in red");

		helper.setBlock(arrival, Blocks.AIR);
		helper.assertTrue(plan(helper, START, click, spec).valid(), "with the block gone it should be possible again");
		helper.succeed();
	}

	/**
	 * A route turned by hand in grade mode is two arcs, and the anchor between them is on a slope
	 * between the two ends'.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aGradedCurveTurnedByHandIsTwoArcsOnOneClimb(GameTestHelper helper) {
		route(helper, EAST, BEFORE, START);
		GradeSpec spec = new GradeSpec(150);

		CurvePlanner.Plan plan = CurvePlanner.plan(helper.getLevel(), helper.absolutePos(START), helper.absolutePos(new BlockPos(34, 2, 26)),
			Direction.UP, 25.0F, spec);
		helper.assertTrue(plan.valid() && plan.grade() != null, "the curve should be possible, but was refused: " + plan.error());
		helper.assertTrue(plan.junction() != null && plan.secondCurve() != null, "turned by hand it should be two arcs");
		// By how far along the link it is: where a slope that changed at a steady rate would be.
		Vec3 middle = plan.junction()
			.point();
		double firstRun = Math.hypot(middle.x - plan.curve().starts.getFirst().x, middle.z - plan.curve().starts.getFirst().z);
		double secondRun = Math.hypot(plan.secondCurve().starts.getSecond().x - middle.x,
			plan.secondCurve().starts.getSecond().z - middle.z);
		double expected = plan.startSlope() + (plan.endSlope() - plan.startSlope()) * firstRun / (firstRun + secondRun);
		helper.assertTrue(near(plan.junctionSlope(), expected, 1.0E-6),
			"the point between the arcs should be on " + expected + ", between the ends' " + plan.startSlope() + " and "
				+ plan.endSlope() + ", but is on " + plan.junctionSlope());
		helper.assertTrue(near(plan.rise(), 2.0, 1.0E-9), "the whole should rise two blocks, but rises " + plan.rise());

		// And it can be built, with the graph agreeing with what is drawn on both arcs.
		helper.assertTrue(CurvePlanner.apply(helper.getLevel(), plan) == null, "the curve should have been built");
		AngledTrackAnchorBlockEntity start = anchorAt(helper, START);
		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, start);
			helper.succeed();
		});
	}

	// ------------------------------------------------------ what is saved

	/**
	 * A selection keeps its grade through saving, and a wand saved before there was one is read as being
	 * in the mode it was in: off.
	 */
	@GameTest(template = EMPTY)
	public static void aSelectionKeepsItsGradeThroughSavingAndOldOnesAreReadAsOff(GameTestHelper helper) {
		Level level = helper.getLevel();
		CurveWandSelection selection = CurveWandSelection.of(level, helper.absolutePos(START))
			.withAngle(12.5F)
			.withGrade(-35, true);

		CompoundTag saved = (CompoundTag) CurveWandSelection.CODEC.encodeStart(NbtOps.INSTANCE, selection)
			.getOrThrow();
		CurveWandSelection loaded = CurveWandSelection.CODEC.parse(NbtOps.INSTANCE, saved)
			.getOrThrow();
		helper.assertTrue(selection.equals(loaded), "a saved selection should load as it was: " + selection + " became " + loaded);

		saved.remove("grade");
		saved.remove("grade_mode");
		CurveWandSelection old = CurveWandSelection.CODEC.parse(NbtOps.INSTANCE, saved)
			.getOrThrow();
		helper.assertTrue(old.grade() == 0 && !old.gradeMode() && old.angle() == 12.5F && old.pos()
			.equals(selection.pos()), "a wand saved before grades should load in the old mode, but loads as " + old);

		ByteBuf buffer = Unpooled.buffer();
		CurveWandSelection.STREAM_CODEC.encode(buffer, selection);
		helper.assertTrue(selection.equals(CurveWandSelection.STREAM_CODEC.decode(buffer)),
			"a selection sent to the client should arrive as it was");

		// A selection that is not in the mode has no grade to speak of, so two that mean the same are equal.
		helper.assertTrue(selection.withGrade(50, false)
			.equals(selection.withGrade(0, false)), "a selection with the mode off should keep no grade");
		helper.succeed();
	}

	// --------------------------------------------------- laid for a distance

	/**
	 * A curve laid for a distance leaves the end of a route straight ahead and arrives that far along the
	 * ground, with its height from the grade just as one aimed at a block does.
	 *
	 * <p>Sixteen blocks has handles {@code 16/3} long, so 150 permille from level rises
	 * {@code 1.5 · 16/3 · 0.15 = 1.2}, a block, and arrives at {@code 1 / 8}.
	 */
	@GameTest(template = EMPTY)
	public static void aDistanceLaysACurveStraightAheadAtTheGrade(GameTestHelper helper) {
		route(helper, EAST, BEFORE, START);

		CurvePlanner.Plan plan = byDistance(helper, START, 150, 16, EAST);
		helper.assertTrue(plan.valid(), "the curve should be possible, but was refused: " + plan.error());
		helper.assertTrue(plan.grade() != null && plan.junction() == null, "it should be one curve laid at the grade");

		BlockPos end = relative(helper, plan.end()
			.anchorPos());
		helper.assertTrue(end.getZ() == START.getZ(), "it should go straight ahead, but arrives at " + end);
		helper.assertTrue(near(plan.horizontalLength(), 16.0, 1.5),
			"it should run sixteen blocks along the ground, but runs " + plan.horizontalLength());
		helper.assertTrue(near(end.getX() + 0.5 - (START.getX() + 0.5), 16.0, 1.5), "it should arrive about sixteen on, at " + end);

		double handle = CurvePlanner.levelHandleLength(plan.curve());
		int rise = (int) Math.round(1.5 * handle * 0.15);
		helper.assertTrue(rise == 1 && end.getY() == START.getY() + rise,
			"it should rise one block, but the far end is at " + end + " for a rise of " + rise);
		helper.assertTrue(near(plan.endSlope(), rise / (1.5 * handle), 1.0E-6),
			"it should arrive at " + rise / (1.5 * handle) + ", but arrives at " + plan.endSlope());

		// Too short and too long are refused, as any curve is.
		helper.assertTrue("too_close".equals(byDistance(helper, START, 150, 2, EAST).error()),
			"two blocks is too short for a curve");
		helper.assertTrue("too_far".equals(byDistance(helper, START, 150, 4000, EAST).error()),
			"four thousand blocks is further than track may go");
		helper.succeed();
	}

	/**
	 * Straight ahead is the way the route runs, whichever way the player faces: out of the free side of
	 * the end of a route, and backwards out of the first anchor of one.
	 */
	@GameTest(template = EMPTY)
	public static void aroundARouteTheDistanceFollowsTheRoute(GameTestHelper helper) {
		BlockPos tail = new BlockPos(30, 1, 22);
		BlockPos head = new BlockPos(30, 1, 14);
		route(helper, NORTH, tail, head);

		// The player looking the other way makes no difference to an anchor with a route through it.
		CurvePlanner.Plan onwards = byDistance(helper, head, 100, 8, 0.0F);
		helper.assertTrue(onwards.valid(), "the curve should be possible, but was refused: " + onwards.error());
		helper.assertTrue(relative(helper, onwards.end()
			.anchorPos()).getZ() < head.getZ() - 5, "it should carry on north, but arrives at " + relative(helper, onwards.end()
				.anchorPos()));
		helper.assertTrue(onwards.forward(), "carrying a route on runs forwards");

		CurvePlanner.Plan backwards = byDistance(helper, tail, 100, 8, NORTH);
		helper.assertTrue(backwards.valid(), "the curve should be possible, but was refused: " + backwards.error());
		helper.assertTrue(relative(helper, backwards.end()
			.anchorPos()).getZ() > tail.getZ() + 5,
			"out of the first anchor it should go back south, but arrives at " + relative(helper, backwards.end()
				.anchorPos()));
		helper.assertTrue(!backwards.forward(), "going back out of a route's first anchor runs the link the other way");
		helper.succeed();
	}

	/**
	 * An anchor on no route yet can be left either way, and the way the player faces picks the one of its
	 * two it is nearer to; Create's own track likewise picks the side that is faced.
	 */
	@GameTest(template = EMPTY)
	public static void whereTheStartDoesNotDecideThePlayerFacingDoes(GameTestHelper helper) {
		BlockPos lone = new BlockPos(24, 1, 16);
		anchor(helper, lone);

		CurvePlanner.Plan north = byDistance(helper, lone, 100, 10, NORTH);
		CurvePlanner.Plan south = byDistance(helper, lone, 100, 10, 0.0F);
		helper.assertTrue(north.valid() && south.valid(), "both ways should be possible: " + north.error() + ", " + south.error());
		helper.assertTrue(relative(helper, north.end()
			.anchorPos()).getZ() < lone.getZ() - 6 && relative(helper, south.end()
				.anchorPos()).getZ() > lone.getZ() + 6,
			"facing north it should go north and facing south south, but go to " + relative(helper, north.end()
				.anchorPos()) + " and " + relative(helper, south.end()
					.anchorPos()));

		BlockPos rail = new BlockPos(24, 1, 8);
		helper.setBlock(rail, AllBlocks.TRACK.get()
			.defaultBlockState()
			.setValue(TrackBlock.SHAPE, TrackShape.XO));
		CurvePlanner.Plan east = byDistance(helper, rail, 100, 12, EAST);
		CurvePlanner.Plan west = byDistance(helper, rail, 100, 12, 90.0F);
		helper.assertTrue(east.valid() && west.valid(), "both ways should be possible: " + east.error() + ", " + west.error());
		helper.assertTrue(relative(helper, east.start()
			.anchorPos()).getX() == rail.getX() + 1 && relative(helper, west.start()
				.anchorPos()).getX() == rail.getX() - 1,
			"the anchor should go on the side of the track that is faced");
		helper.assertTrue(relative(helper, east.end()
			.anchorPos()).getX() > rail.getX() + 8 && relative(helper, west.end()
				.anchorPos()).getX() < rail.getX() - 8, "and the curve should go that way");
		helper.succeed();
	}

	/**
	 * What the grade screen sends does what it says: the grade is set, and a distance lays a curve from
	 * the end of the route and carries the selection on from it, at the grade, until a level-off ends it.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void theGradeScreenSetsAGradeAndLaysACurveForADistance(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = route(helper, EAST, BEFORE, START);
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		player.setItemInHand(InteractionHand.MAIN_HAND, wand);
		Level level = helper.getLevel();

		helper.assertTrue(!ApplyGradePacket.apply(player, 150, 16), "with nothing selected there is nothing to act on");
		helper.assertTrue(click(helper, player, wand, START).consumesAction(), "setup: the first click should pick the start");

		// No distance: the grade is set and nothing is laid.
		helper.assertTrue(ApplyGradePacket.apply(player, 60, 0), "the grade should have been set");
		CurveWandSelection set = CurveWandItem.selectionIn(wand, level);
		helper.assertTrue(set != null && set.gradeMode() && set.grade() == 60, "60 permille should be set, but: " + set);
		assertChain(helper, route[0], BEFORE, START);

		helper.assertTrue(ApplyGradePacket.apply(player, -5000, 0), "the grade should have been set");
		helper.assertTrue(CurveWandItem.selectionIn(wand, level)
			.grade() == -CurvePlanner.maxGradePermille(), "a grade past the limit should be held to it");

		// A distance: laid ahead, where the plan said it would be, and carried on from.
		CurvePlanner.Plan first = byDistance(helper, START, 150, 16, EAST);
		helper.assertTrue(first.valid(), "setup: the curve should be possible, but: " + first.error());
		BlockPos firstEnd = relative(helper, first.end()
			.anchorPos());
		helper.assertTrue(ApplyGradePacket.apply(player, 150, 16), "the curve should have been laid");
		assertChain(helper, route[0], BEFORE, START, firstEnd);
		CurveWandSelection carried = CurveWandItem.selectionIn(wand, level);
		helper.assertTrue(carried != null && carried.pos()
			.equals(helper.absolutePos(firstEnd)) && carried.gradeMode() && carried.grade() == 150,
			"the selection should carry on from the end at 150 permille, but: " + carried);
		helper.assertTrue(near(anchorAt(helper, firstEnd).getSlope(), first.endSlope(), 1.0E-6),
			"the anchor should hold the slope the curve arrived with");

		// A level-off for a distance ends level and drops the mode.
		CurvePlanner.Plan second = byDistance(helper, firstEnd, 0, 12, EAST);
		helper.assertTrue(second.valid(), "setup: the level-off should be possible, but: " + second.error());
		BlockPos secondEnd = relative(helper, second.end()
			.anchorPos());
		helper.assertTrue(ApplyGradePacket.apply(player, 0, 12), "the level-off should have been laid");
		assertChain(helper, route[0], BEFORE, START, firstEnd, secondEnd);
		helper.assertTrue(anchorAt(helper, secondEnd).getSlope() == 0.0, "the route should end level");
		CurveWandSelection levelled = CurveWandItem.selectionIn(wand, level);
		helper.assertTrue(levelled != null && !levelled.gradeMode() && levelled.grade() == 0 && levelled.pos()
			.equals(helper.absolutePos(secondEnd)), "the mode should be dropped, and the selection on the new end, but: " + levelled);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, route[0]);
			helper.succeed();
		});
	}

	/**
	 * A distance that cannot be laid leaves the world as it was, with the grade set; and level from level
	 * is a level curve and no mode.
	 */
	@GameTest(template = EMPTY)
	public static void aDistanceThatCannotBeLaidBuildsNothing(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = route(helper, EAST, BEFORE, START);
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		player.setItemInHand(InteractionHand.MAIN_HAND, wand);
		helper.assertTrue(click(helper, player, wand, START).consumesAction(), "setup: the first click should pick the start");

		helper.assertTrue(ApplyGradePacket.apply(player, 150, 4000), "the grade should have been set");
		assertChain(helper, route[0], BEFORE, START);
		CurveWandSelection kept = CurveWandItem.selectionIn(wand, helper.getLevel());
		helper.assertTrue(kept != null && kept.gradeMode() && kept.grade() == 150 && kept.pos()
			.equals(helper.absolutePos(START)), "the selection should be where it was, at the grade: " + kept);

		// Level from level, laid for a distance: a level curve, and the mode stays off.
		helper.assertTrue(ApplyGradePacket.apply(player, 0, 12), "the curve should have been laid");
		BlockPos end = relative(helper, route[0].route()
			.get(2)
			.getBlockPos());
		helper.assertTrue(end.getY() == START.getY() && end.getZ() == START.getZ(), "it should be level and straight, but arrives at " + end);
		CurveWandSelection after = CurveWandItem.selectionIn(wand, helper.getLevel());
		helper.assertTrue(after != null && !after.gradeMode(), "level from level should not leave the mode on, but: " + after);
		helper.succeed();
	}

	/**
	 * Sneaking and right clicking the air drops a selection, except in grade mode, where it is the gesture
	 * that opens the grade screen and the selection has to be left alone for it to be about.
	 */
	@GameTest(template = EMPTY)
	public static void sneakingInTheAirKeepsASelectionInGradeModeOnly(GameTestHelper helper) {
		BlockPos from = new BlockPos(4, 1, 16);
		anchor(helper, from);
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		player.setItemInHand(InteractionHand.MAIN_HAND, wand);
		helper.assertTrue(click(helper, player, wand, from).consumesAction(), "setup: the first click should pick the start");
		player.setShiftKeyDown(true);

		SetCurveGradePacket.apply(player, 35, true);
		wand.getItem()
			.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		CurveWandSelection kept = CurveWandItem.selectionIn(wand, helper.getLevel());
		helper.assertTrue(kept != null && kept.gradeMode() && kept.grade() == 35,
			"in grade mode the selection should be left standing, but: " + kept);

		// Back out of the mode, which from level ground is asking for level.
		SetCurveGradePacket.apply(player, 0, true);
		wand.getItem()
			.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		helper.assertTrue(CurveWandItem.selectionIn(wand, helper.getLevel()) == null,
			"outside grade mode the same gesture should drop the selection, as it always did");
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

	private static CurvePlanner.Plan plan(GameTestHelper helper, BlockPos start, BlockPos end, GradeSpec spec) {
		return CurvePlanner.plan(helper.getLevel(), helper.absolutePos(start), helper.absolutePos(end), Direction.UP, 0,
			spec);
	}

	private static CurvePlanner.Plan byDistance(GameTestHelper helper, BlockPos start, int permille, double length,
		float facingYaw) {
		return CurvePlanner.planByDistance(helper.getLevel(), helper.absolutePos(start), new GradeSpec(permille), length,
			AngledTrackAnchorBlockEntity.directionFromYaw(facingYaw));
	}

	/** The steepest a curve is anywhere along it, as the largest grade between neighbouring samples. */
	private static double steepest(BezierConnection curve) {
		double steepest = 0;
		for (int i = 0; i < SAMPLES; i++)
			steepest = Math.max(steepest, Math.abs(gradeBetween(curve, i / (double) SAMPLES, (i + 1) / (double) SAMPLES)));
		return steepest;
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
