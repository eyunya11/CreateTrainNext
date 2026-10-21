package com.eyunya.createtrainnext.gametest;

import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.GRAPH_TICKS;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.anchor;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.assertConsistent;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.relative;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.route;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.eyunya.createtrainnext.track.GradeSpec;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks that the free end of a curve is drawn to the angles on the grid, so that 45 and 90 degrees can
 * be laid exactly.
 *
 * <p>An aimed block gives the curve whatever angle it happens to: one block off a right angle is a turn of
 * 87 degrees, and nothing the player does with the wheel on top of that reaches 90, since the wheel adds
 * to the angle the block gave. So the angles that are multiples of {@code curves.snapAngle} win when the
 * one the block gives is within a quarter of a step of them: by moving the block, if a block a couple
 * of places over gives the angle exactly, and by turning the heading to it if not.
 *
 * <p>Each test that depends on the setting puts it back, since the tests share one server.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class AngleGridTests {

	/**
	 * An empty box of 64 by 128, not the 48 by 32 the other tests use. The grid is only seen working over
	 * spans of thirty blocks and more, so the routes here are laid far down the box and aimed up to forty
	 * blocks along it; in the small box they stood in the box of whichever test was laid out next, and a
	 * test there that counts the nodes in its own box counted theirs.
	 */
	private static final String EMPTY = "empty_wide";

	/** Two anchors running east, the second the end of the route. */
	private static final BlockPos BEFORE = new BlockPos(2, 2, 40);
	private static final BlockPos START = new BlockPos(10, 2, 40);

	/** A lone anchor, with no route through it to decide which way it points. */
	private static final BlockPos LONE = new BlockPos(10, 2, 80);

	/** Within this, two bearings are the same angle. */
	private static final double EXACT = 1.0E-6;

	/**
	 * The longest span the tests plan, set for the length of a plan. Create's own default of 32 is shorter than
	 * the spans the grid needs to be seen working over — a block off straight is only inside the window from about
	 * thirty blocks out — and a test must not depend on whatever the setting was left at.
	 */
	private static final int LONGEST_SPAN = 64;

	private static double bearing(Vec3 heading) {
		return CurvePlanner.bearingOf(heading);
	}

	private static boolean on(double bearing, double wanted) {
		return Math.abs(net.minecraft.util.Mth.wrapDegrees(bearing - wanted)) < EXACT;
	}

	private static CurvePlanner.Plan plan(GameTestHelper helper, BlockPos start, int east, int north, float hand) {
		return plan(helper, start, east, north, hand, null);
	}

	/** The plan for aiming at the block {@code east} on and {@code north} of the start; north is minus z. */
	private static CurvePlanner.Plan plan(GameTestHelper helper, BlockPos start, int east, int north, float hand,
		GradeSpec grade) {
		BlockPos from = helper.absolutePos(start);
		var longest = AllConfigs.server().trains.maxTrackPlacementLength;
		int original = longest.get();
		try {
			longest.set(LONGEST_SPAN);
			return CurvePlanner.plan(helper.getLevel(), from, from.offset(east, 0, -north), Direction.UP, hand, grade);
		} finally {
			longest.set(original);
		}
	}

	private static BlockPos moved(GameTestHelper helper, CurvePlanner.Plan plan, BlockPos start, int east, int north) {
		BlockPos aimed = helper.absolutePos(start)
			.offset(east, 0, -north);
		BlockPos end = plan.end()
			.anchorPos();
		return new BlockPos(end.getX() - aimed.getX(), 0, end.getZ() - aimed.getZ());
	}

	/** Runs the body with {@code curves.snapAngle} set, and puts the setting back whatever happens. */
	private static void withStep(int step, Runnable body) {
		int original = CTNConfig.SERVER.snapAngle.get();
		try {
			CTNConfig.SERVER.snapAngle.set(step);
			body.run();
		} finally {
			CTNConfig.SERVER.snapAngle.set(original);
		}
	}

	// ------------------------------------------------------- moving the aimed block

	/**
	 * A turn that is one block short of a right angle, or one over, is a right angle: the curve is moved a
	 * block, and is the one arc it would have been had the block been aimed at exactly.
	 */
	@GameTest(template = EMPTY)
	public static void aTurnNearARightAngleIsMadeOne(GameTestHelper helper) {
		route(helper, 270.0F, BEFORE, START);

		for (int[] aim : new int[][] { { 20, 19 }, { 20, 21 }, { 25, 24 } }) {
			CurvePlanner.Plan plan = plan(helper, START, aim[0], aim[1], 0);
			String at = "(+" + aim[0] + ", north " + aim[1] + ")";
			helper.assertTrue(plan.valid(), at + " should be possible, but was refused: " + plan.error());
			helper.assertTrue(on(bearing(plan.endHeading()), 90), at + " should arrive at 90 degrees, but arrives at " + bearing(plan.endHeading()));
			helper.assertTrue(plan.junction() == null, at + " should be one arc, with no anchor added to correct it");
			BlockPos by = moved(helper, plan, START, aim[0], aim[1]);
			helper.assertTrue(Math.abs(by.getX()) + Math.abs(by.getZ()) == 1, at + " should have moved one block, but moved " + by);
		}
		helper.succeed();
	}

	/** A line that is a block off straight is made straight. */
	@GameTest(template = EMPTY)
	public static void aLineNearStraightIsMadeStraight(GameTestHelper helper) {
		route(helper, 270.0F, BEFORE, START);

		for (int north : new int[] { 1, -1 }) {
			CurvePlanner.Plan plan = plan(helper, START, 40, north, 0);
			helper.assertTrue(plan.valid(), "it should be possible, but was refused: " + plan.error());
			helper.assertTrue(on(bearing(plan.endHeading()), 0), "it should run straight on, but arrives at " + bearing(plan.endHeading()));
			helper.assertTrue(plan.end()
				.anchorPos()
				.getZ() == helper.absolutePos(START)
					.getZ(), "and on the line of the route, but the end is at " + plan.end()
						.anchorPos());
			helper.assertTrue(plan.junction() == null && plan.turnDegrees() < EXACT, "and be one straight curve");
		}
		helper.succeed();
	}

	/** Out of the window nothing is touched: not the block, and not the angle. */
	@GameTest(template = EMPTY)
	public static void aTurnOutsideTheWindowIsLeftAlone(GameTestHelper helper) {
		route(helper, 270.0F, BEFORE, START);

		// 83.97 degrees is 6.03 from 90, and the window is 3.75.
		CurvePlanner.Plan plan = plan(helper, START, 20, 18, 0);
		helper.assertTrue(plan.valid(), "it should be possible, but was refused: " + plan.error());
		helper.assertTrue(moved(helper, plan, START, 20, 18).equals(BlockPos.ZERO), "the block should not have moved");
		helper.assertTrue(Math.abs(bearing(plan.endHeading()) - 83.9744) < 0.001,
			"the angle should be the one the block gives, but is " + bearing(plan.endHeading()));
		helper.succeed();
	}

	// ------------------------------------------------- turning the heading to the grid

	/**
	 * An angle no block can give exactly — 60 degrees is a chord of 30, which no block is on — is reached by
	 * turning the heading, with the block where it was aimed.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void anAngleNoBlockCanGiveIsTurnedToExactly(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, 270.0F, BEFORE, START);

		CurvePlanner.Plan plan = plan(helper, START, 30, 17, 0);
		helper.assertTrue(plan.valid(), "it should be possible, but was refused: " + plan.error());
		helper.assertTrue(on(bearing(plan.endHeading()), 60), "it should arrive at 60 degrees, but arrives at " + bearing(plan.endHeading()));
		helper.assertTrue(moved(helper, plan, START, 30, 17).equals(BlockPos.ZERO), "the block should be where it was aimed");
		helper.assertTrue(plan.junction() == null, "a few degrees of correction should not need an anchor of its own");

		// Built, the anchors hold the exact angle and the graph is what was drawn.
		helper.assertTrue(CurvePlanner.apply(helper.getLevel(), plan) == null, "the curve should have been built");
		BlockPos end = relative(helper, plan.end()
			.anchorPos());
		AngledTrackAnchorBlockEntity built = (AngledTrackAnchorBlockEntity) helper.getBlockEntity(end);
		helper.assertTrue(Math.abs(bearing(built.heading()) - 60) < 1.0E-4,
			"the anchor should point at 60 degrees, but points at " + bearing(built.heading()));

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, first[0]);
			helper.succeed();
		});
	}

	/** An anchor on no route is drawn to the grid, and so is the line from it. */
	@GameTest(template = EMPTY)
	public static void aLineFromALoneAnchorIsDrawnToTheGrid(GameTestHelper helper) {
		anchor(helper, LONE);

		Object[][] cases = { { 20, -11, -30.0 }, { 30, -8, -15.0 }, { 17, -10, -30.0 }, { 20, -19, -45.0 }, { 1, -20, -90.0 }, { 20, 0, 0.0 } };
		for (Object[] each : cases) {
			CurvePlanner.Plan plan = plan(helper, LONE, (int) each[0], (int) each[1], 0);
			String at = "(+" + each[0] + ", north " + each[1] + ")";
			helper.assertTrue(plan.valid(), at + " should be possible, but was refused: " + plan.error());
			helper.assertTrue(on(bearing(plan.startHeading()), (double) each[2]) && on(bearing(plan.endHeading()), (double) each[2]),
				at + " should run at " + each[2] + " degrees, but starts at " + bearing(plan.startHeading()) + " and ends at " + bearing(plan.endHeading()));
			helper.assertTrue(plan.junction() == null, at + " should be one curve");
		}
		helper.succeed();
	}

	// -------------------------------------------------------- the setting and the hand

	/** Zero is off: the angle is the one the block gives, and the block is the one that was aimed at. */
	@GameTest(template = EMPTY)
	public static void zeroTurnsTheGridOff(GameTestHelper helper) {
		route(helper, 270.0F, BEFORE, START);
		anchor(helper, LONE);

		withStep(0, () -> {
			CurvePlanner.Plan turn = plan(helper, START, 20, 19, 0);
			helper.assertTrue(turn.valid() && moved(helper, turn, START, 20, 19).equals(BlockPos.ZERO),
				"with the grid off the block should not move, but moved " + moved(helper, turn, START, 20, 19));
			helper.assertTrue(Math.abs(bearing(turn.endHeading()) - 87.0624) < 0.001,
				"the angle should be the one the block gives, but is " + bearing(turn.endHeading()));

			CurvePlanner.Plan free = plan(helper, LONE, 20, -11, 0);
			helper.assertTrue(Math.abs(bearing(free.endHeading()) + 28.8108) < 0.001,
				"a free line should run to the block, but runs at " + bearing(free.endHeading()));
		});
		helper.succeed();
	}

	/** The step is the setting's, and the window goes with it. */
	@GameTest(template = EMPTY)
	public static void theStepIsTheSettings(GameTestHelper helper) {
		route(helper, 270.0F, BEFORE, START);

		// 36.87 degrees: 6.87 from 30 and 8.13 from 45. Nothing on a grid of 15, since the window is 3.75.
		withStep(15, () -> {
			CurvePlanner.Plan plan = plan(helper, START, 30, 10, 0);
			helper.assertTrue(Math.abs(bearing(plan.endHeading()) - 36.8699) < 0.001,
				"a grid of 15 should leave 36.87 alone, but it is " + bearing(plan.endHeading()));
		});
		// A grid of 45 has the window of 11.25, which takes it to 45.
		withStep(45, () -> {
			CurvePlanner.Plan plan = plan(helper, START, 30, 10, 0);
			helper.assertTrue(plan.valid() && on(bearing(plan.endHeading()), 45),
				"a grid of 45 should take it to 45, but it is " + bearing(plan.endHeading()));
		});
		helper.succeed();
	}

	/**
	 * Turning the end by hand stops on the grid. The wheel adds to the angle the block gave, which is never
	 * a round one, so without this no notch of it reached 30 or 15 or 60.
	 */
	@GameTest(template = EMPTY)
	public static void turningByHandStopsOnTheGrid(GameTestHelper helper) {
		route(helper, 270.0F, BEFORE, START);

		// The block gives 43.60 degrees. Each hand turn takes that many degrees off it.
		Object[][] cases = { { 10.0F, 30.0 }, { 15.0F, 30.0 }, { 30.0F, 15.0 }, { -15.0F, 60.0 }, { -45.0F, 90.0 } };
		for (Object[] each : cases) {
			CurvePlanner.Plan plan = plan(helper, START, 30, 12, (float) each[0]);
			helper.assertTrue(plan.valid(), "a turn of " + each[0] + " should be possible, but was refused: " + plan.error());
			helper.assertTrue(on(bearing(plan.endHeading()), (double) each[1]),
				"a turn of " + each[0] + " should stop at " + each[1] + " degrees, but ends at " + bearing(plan.endHeading()));
		}

		// 2.5 degrees off 43.60 is 41.10, which is 3.9 from 45: just outside, so it is left to be.
		CurvePlanner.Plan between = plan(helper, START, 30, 12, 2.5F);
		helper.assertTrue(Math.abs(bearing(between.endHeading()) - 41.1028) < 0.001,
			"a turn that lands between two angles should be left there, but ends at " + bearing(between.endHeading()));
		helper.succeed();
	}

	/**
	 * While the end is being turned by hand the aimed block stays where it is: the player is steering the
	 * angle, and a block that jumped about under the wheel would make that impossible.
	 */
	@GameTest(template = EMPTY)
	public static void aHandTurnedEndIsNotMoved(GameTestHelper helper) {
		route(helper, 270.0F, BEFORE, START);

		// Without the hand this one is moved a block to make the right angle.
		helper.assertTrue(!moved(helper, plan(helper, START, 20, 19, 0), START, 20, 19).equals(BlockPos.ZERO),
			"setup: the aimed block should be moved when nothing is turned");

		for (float hand : new float[] { 5.0F, -5.0F, 15.0F }) {
			CurvePlanner.Plan plan = plan(helper, START, 20, 19, hand);
			helper.assertTrue(plan.valid(), "a turn of " + hand + " should be possible, but was refused: " + plan.error());
			helper.assertTrue(moved(helper, plan, START, 20, 19).equals(BlockPos.ZERO),
				"a turn of " + hand + " should leave the block where it was aimed, but moved it " + moved(helper, plan, START, 20, 19));
		}
		helper.succeed();
	}

	// ------------------------------------------------------------ the rest of the planner

	/** In grade mode the angle is drawn to the grid too, and the block that moves is a new one of the right height. */
	@GameTest(template = EMPTY)
	public static void gradeModeHasTheGridToo(GameTestHelper helper) {
		route(helper, 270.0F, BEFORE, START);

		CurvePlanner.Plan plan = plan(helper, START, 20, 19, 0, new GradeSpec(100));
		helper.assertTrue(plan.valid(), "it should be possible, but was refused: " + plan.error());
		helper.assertTrue(plan.grade() != null, "it should have been laid at the grade");
		helper.assertTrue(on(bearing(plan.endHeading()), 90), "it should arrive at 90 degrees, but arrives at " + bearing(plan.endHeading()));
		helper.assertTrue(plan.junction() == null, "it should be one arc");
		helper.succeed();
	}

	/**
	 * A grid that would make a curve impossible gives way: where the block it moves to has no room for the
	 * anchor, the curve is the one the aimed block gives.
	 */
	@GameTest(template = EMPTY)
	public static void aGridThatLeavesNoRoomGivesWay(GameTestHelper helper) {
		route(helper, 270.0F, BEFORE, START);

		GradeSpec grade = new GradeSpec(100);
		CurvePlanner.Plan snapped = plan(helper, START, 20, 19, 0, grade);
		helper.assertTrue(snapped.valid() && !moved(helper, snapped, START, 20, 19).equals(BlockPos.ZERO),
			"setup: the aimed block should have been moved");

		// Where the snapped curve would put its anchor, something is in the way; where the aimed one would, not.
		helper.getLevel()
			.setBlock(snapped.end()
				.anchorPos(), Blocks.STONE.defaultBlockState(), 3);

		CurvePlanner.Plan plan = plan(helper, START, 20, 19, 0, grade);
		helper.assertTrue(plan.valid(), "it should fall back to the aimed block, but was refused: " + plan.error());
		helper.assertTrue(moved(helper, plan, START, 20, 19).equals(BlockPos.ZERO),
			"it should be the block that was aimed at, but is " + moved(helper, plan, START, 20, 19) + " from it");
		helper.succeed();
	}

	/**
	 * A distance laid straight on is straight on from a route at an angle that is not on the grid, and is
	 * not bent towards it: it is meant to go the way the route runs.
	 */
	@GameTest(template = EMPTY)
	public static void aDistanceLaidAheadIsNotBent(GameTestHelper helper) {
		// A route running at 33 degrees, which is 3 off the grid and inside the window.
		Vec3 heading = CurvePlanner.headingAt(33.0);
		float yaw = AngledTrackAnchorBlockEntity.yawOf(heading);
		BlockPos tail = new BlockPos(10, 2, 120);
		BlockPos head = new BlockPos(26, 2, 110);
		route(helper, yaw, tail, head);
		BlockPos from = helper.absolutePos(head);

		double[] natural = new double[1];
		withStep(0, () -> {
			CurvePlanner.Plan plan = CurvePlanner.planByDistance(helper.getLevel(), from, new GradeSpec(0), 20, heading);
			helper.assertTrue(plan.valid(), "setup: the line should be possible, but was refused: " + plan.error());
			natural[0] = bearing(plan.endHeading());
		});
		helper.assertTrue(natural[0] > 26.25 && natural[0] < 33.75 && Math.abs(natural[0] - 30) > 0.001,
			"setup: the angle without the grid should be inside the window and off the grid, but is " + natural[0]);

		CurvePlanner.Plan plan = CurvePlanner.planByDistance(helper.getLevel(), from, new GradeSpec(0), 20, heading);
		helper.assertTrue(plan.valid(), "it should be possible, but was refused: " + plan.error());
		helper.assertTrue(Math.abs(bearing(plan.endHeading()) - natural[0]) < 1.0E-9,
			"it should go the way the route does, but ends at " + bearing(plan.endHeading()) + " rather than " + natural[0]);
		helper.succeed();
	}
}
