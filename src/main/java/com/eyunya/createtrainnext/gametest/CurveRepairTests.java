package com.eyunya.createtrainnext.gametest;

import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.AFTER;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.BEFORE;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.EAST;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.END;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.GRAPH_TICKS;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.NORTH;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.START;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.WEST;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.assertChain;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.assertConsistent;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.relative;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.route;

import java.util.List;

import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.eyunya.createtrainnext.track.CurveRepair;
import com.simibubi.create.content.trains.track.ITrackBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks the mending of links that were built as one curve and are a different shape read the other
 * way round, on a real server level.
 *
 * <p>The wand no longer builds those, so each test makes one the way the wand used to: two routes
 * that point different ways, and the last anchor of one linked to the first of the other with
 * nothing in between. What is asked afterwards is what matters about a repair: that the anchors that
 * were there have not moved or turned, that whatever stands on them still faces the way it did, that
 * the graph Create builds from the result has an edge on the drawn curve in both directions, and
 * that a link which cannot be mended is left exactly as it was.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class CurveRepairTests {

	private static final String EMPTY = "empty";

	/** Long enough for anchors that have been placed to have been entered in the index commands read. */
	private static final int INDEX_TICKS = 8;

	/**
	 * Two routes joined the way the wand used to join them, as one curve. The result is the anchors in
	 * the order the route runs: the two of the first route, then the two of the second.
	 */
	private static AngledTrackAnchorBlockEntity[] oldJoin(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, EAST, BEFORE, START);
		AngledTrackAnchorBlockEntity[] second = route(helper, NORTH, END, AFTER);
		first[1].linkTo(second[0], null, null);
		return new AngledTrackAnchorBlockEntity[] { first[0], first[1], second[0], second[1] };
	}

	/** Where the third route of {@link #twoOldLinks} starts and ends. */
	private static final BlockPos WEST_FIRST = new BlockPos(18, 2, 4);
	private static final BlockPos WEST_LAST = new BlockPos(8, 2, 4);

	/**
	 * Three routes joined by two old links, in different directions round: east into north, then north
	 * into west. The anchors come back in the order the route runs.
	 */
	private static AngledTrackAnchorBlockEntity[] twoOldLinks(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] one = route(helper, EAST, BEFORE, START);
		AngledTrackAnchorBlockEntity[] two = route(helper, NORTH, END, AFTER);
		AngledTrackAnchorBlockEntity[] three = route(helper, WEST, WEST_FIRST, WEST_LAST);
		one[1].linkTo(two[0], null, null);
		two[1].linkTo(three[0], null, null);
		return new AngledTrackAnchorBlockEntity[] { one[0], one[1], two[0], two[1], three[0], three[1] };
	}

	// ---------------------------------------------------------------- mending

	/** The link between them is over the limit, and becomes two arcs with an anchor between. */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void anOldLinkBetweenAnchorsPointingDifferentWaysIsMendedIntoTwoArcs(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = oldJoin(helper);
		Level level = helper.getLevel();

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			helper.assertTrue(CurveRepair.linkFrom(route[1])
				.overLimit(), "setup: the link should be one that differs read the other way round");
			Vec3[] headings = headingsOf(route);
			Vec3[] axes = axesOf(level, route);

			CurveRepair.Outcome outcome = CurveRepair.repair(level, CurveRepair.linkFrom(route[1]));
			helper.assertTrue(outcome.repaired(), "the link should have been mended, but: " + outcome.error());
			// Both ends and both headings stay, so the rails can only be a little way from where they
			// were drawn: much less than the curve was off from itself.
			helper.assertTrue(outcome.moved() > 0.0 && outcome.moved() < 1.0,
				"the rails should have moved a little, but moved " + outcome.moved() + " blocks");

			assertChain(helper, route[0], BEFORE, START, relative(helper, outcome.junction()), END, AFTER);
			assertTurnedNowhere(helper, route, headings);
			assertFacingTheSameWay(helper, level, route, axes);

			helper.runAfterDelay(GRAPH_TICKS, () -> {
				assertConsistent(helper, route[0]);
				for (AngledTrackAnchorBlockEntity member : route[0].route())
					helper.assertTrue(member.getNext() == null || !CurveRepair.linkFrom(member)
						.overLimit(), "no link should be over the limit any more, but the one from "
							+ member.getBlockPos()
								.toShortString()
							+ " is");
				helper.succeed();
			});
		});
	}

	/** Mended once, a link is not mended again, and nothing else is added to the world. */
	@GameTest(template = EMPTY, timeoutTicks = 100)
	public static void mendingTwiceChangesNothingTheSecondTime(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = oldJoin(helper);
		Level level = helper.getLevel();

		helper.runAfterDelay(INDEX_TICKS, () -> {
			Vec3 centre = Vec3.atCenterOf(helper.absolutePos(START));
			helper.assertTrue(CurveRepair.repairAll(level, centre, 12, 16)
				.outcomes()
				.size() == 1, "setup: one link here should have been mended");

			helper.runAfterDelay(INDEX_TICKS, () -> {
				helper.assertTrue(CurveRepair.repairAll(level, centre, 12, 16)
					.outcomes()
					.isEmpty(), "nothing should be left to mend");
				helper.assertTrue(route[0].route()
					.size() == 5, "the route should be the four it was and the one that was added");
				helper.succeed();
			});
		});
	}

	/**
	 * A route with an old link in each of two places, and in different directions round: east into
	 * north, then north into west. The signs the anchors report are the part that depends on which way
	 * the route runs, and this one runs against the block's own axis for much of its length.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 100)
	public static void everyOldLinkInARouteIsMended(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = twoOldLinks(helper);
		Level level = helper.getLevel();

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			Vec3[] headings = headingsOf(route);
			Vec3[] axes = axesOf(level, route);

			CurveRepair.Outcome first = CurveRepair.repair(level, CurveRepair.linkFrom(route[1]));
			CurveRepair.Outcome second = CurveRepair.repair(level, CurveRepair.linkFrom(route[3]));
			helper.assertTrue(first.repaired(), "the first link should have been mended, but: " + first.error());
			helper.assertTrue(second.repaired(), "the second link should have been mended, but: " + second.error());

			assertChain(helper, route[0], BEFORE, START, relative(helper, first.junction()), END, AFTER,
				relative(helper, second.junction()), WEST_FIRST, WEST_LAST);
			assertTurnedNowhere(helper, route, headings);
			assertFacingTheSameWay(helper, level, route, axes);

			helper.runAfterDelay(GRAPH_TICKS, () -> {
				assertConsistent(helper, route[0]);
				helper.succeed();
			});
		});
	}

	/**
	 * A run mends only so many links, so that a world full of them does not hold the server up for as
	 * long as it takes, and says how many it left. Running it again does the rest.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 120)
	public static void aRunMendsNoMoreLinksThanItIsAllowedTo(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = twoOldLinks(helper);
		Level level = helper.getLevel();

		helper.runAfterDelay(INDEX_TICKS, () -> {
			// Between the two links that have to be mended, and within reach of both.
			Vec3 centre = Vec3.atCenterOf(helper.absolutePos(new BlockPos(20, 2, 17)));

			CurveRepair.Batch first = CurveRepair.repairAll(level, centre, 16, 1);
			helper.assertTrue(first.outcomes()
				.size() == 1 && first.outcomes()
					.get(0)
					.repaired() && first.remaining() == 1,
				"the first run should mend one link and leave one: " + first);

			helper.runAfterDelay(INDEX_TICKS, () -> {
				CurveRepair.Batch second = CurveRepair.repairAll(level, centre, 16, 1);
				helper.assertTrue(second.outcomes()
					.size() == 1 && second.outcomes()
						.get(0)
						.repaired() && second.remaining() == 0,
					"the second run should mend the other and leave none: " + second);

				helper.runAfterDelay(INDEX_TICKS, () -> {
					helper.assertTrue(CurveRepair.repairAll(level, centre, 16, 1)
						.outcomes()
						.isEmpty(), "a third run should find nothing");
					helper.assertTrue(route[0].route()
						.size() == 8, "the six anchors should have two more between them");
					helper.succeed();
				});
			});
		});
	}

	// ------------------------------------------------------------ left alone

	/**
	 * Where there is nowhere to put the anchor the link is left as it was, and the reason is given.
	 * The whole layer it could go in is filled with stone.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void aLinkWithNowhereForTheAnchorIsLeftAsItWas(GameTestHelper helper) {
		for (int x = 0; x < 48; x++)
			for (int z = 0; z < 32; z++)
				helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
		AngledTrackAnchorBlockEntity[] route = oldJoin(helper);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			CurveRepair.Outcome outcome = CurveRepair.repair(helper.getLevel(), CurveRepair.linkFrom(route[1]));
			helper.assertTrue("uneven".equals(outcome.error()),
				"it should have been left alone for want of room, not: " + outcome.error());
			assertChain(helper, route[0], BEFORE, START, END, AFTER);
			helper.assertTrue(CurveRepair.linkFrom(route[1])
				.overLimit(), "the link should be the one it was");
			helper.succeed();
		});
	}

	/** A curve that is the same either way round is not touched, however many there are about. */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void linksThatAreTheSameEitherWayAreLeftAlone(GameTestHelper helper) {
		// East to east across a gap: the same angle to the chord at both ends.
		AngledTrackAnchorBlockEntity[] first = route(helper, EAST, BEFORE, START);
		AngledTrackAnchorBlockEntity[] second = route(helper, EAST, END, new BlockPos(38, 2, 16));
		first[1].linkTo(second[0], null, null);

		helper.runAfterDelay(INDEX_TICKS, () -> {
			helper.assertTrue(CurveRepair.repairAll(helper.getLevel(), Vec3.atCenterOf(helper.absolutePos(START)), 12, 16)
				.outcomes()
				.isEmpty(), "a curve that is the same either way should not be mended");
			assertChain(helper, first[0], BEFORE, START, END, new BlockPos(38, 2, 16));
			helper.succeed();
		});
	}

	// ----------------------------------------------------------- the command

	/** The listing flags the link, and the command mends it. */
	@GameTest(template = EMPTY, timeoutTicks = 100)
	public static void theRepairCommandMendsTheLinksAroundWhoeverRunsIt(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = oldJoin(helper);

		helper.runAfterDelay(INDEX_TICKS, () -> {
			// A small radius: the tests run side by side, and the next one's box has links of its own.
			Vec3 where = Vec3.atCenterOf(helper.absolutePos(START));

			List<String> listing = GraphCleanlinessTests.run(helper, "createtrainnext curves 12", where);
			helper.assertTrue(listing.stream()
				.anyMatch(line -> line.contains(" !! ")), "the listing should flag the link: " + listing);

			List<String> said = GraphCleanlinessTests.run(helper, "createtrainnext repair 12", where);
			helper.assertTrue(said.stream()
				.anyMatch(line -> line.contains("Mended")), "the command should say it mended the link: " + said);
			helper.assertTrue(route[0].route()
				.size() == 5, "an anchor should have been added between the two arcs");

			helper.runAfterDelay(GRAPH_TICKS, () -> {
				assertConsistent(helper, route[0]);
				helper.succeed();
			});
		});
	}

	// --------------------------------------------------------------- helpers

	private static Vec3[] headingsOf(AngledTrackAnchorBlockEntity[] route) {
		Vec3[] headings = new Vec3[route.length];
		for (int i = 0; i < route.length; i++)
			headings[i] = route[i].heading();
		return headings;
	}

	/** What each anchor reports now as its axis, which is the vector a signal's facing is a sign on. */
	private static Vec3[] axesOf(Level level, AngledTrackAnchorBlockEntity[] route) {
		Vec3[] axes = new Vec3[route.length];
		for (int i = 0; i < route.length; i++)
			axes[i] = reportedAxis(level, route[i]);
		return axes;
	}

	private static Vec3 reportedAxis(Level level, AngledTrackAnchorBlockEntity anchor) {
		BlockPos pos = anchor.getBlockPos();
		BlockState state = level.getBlockState(pos);
		return ((ITrackBlock) state.getBlock()).getTrackAxes(level, pos, state)
			.get(0);
	}

	/** The anchors that were there still point the way they did, so the track either side is as it was. */
	private static void assertTurnedNowhere(GameTestHelper helper, AngledTrackAnchorBlockEntity[] route,
		Vec3[] headings) {
		for (int i = 0; i < route.length; i++)
			helper.assertTrue(route[i].heading()
				.distanceToSqr(headings[i]) < 1.0E-9, "anchor " + i + " should point the way it did");
	}

	/**
	 * A signal is stored as a sign against the axis its anchor reports, so the axis may bend a little
	 * as the curves either side of it do and may not turn round.
	 */
	private static void assertFacingTheSameWay(GameTestHelper helper, Level level, AngledTrackAnchorBlockEntity[] route,
		Vec3[] before) {
		for (int i = 0; i < route.length; i++) {
			Vec3 now = reportedAxis(level, route[i]);
			helper.assertTrue(now.normalize()
				.dot(before[i].normalize()) > 0.9, "anchor " + i + " should report the same way round: it was " + before[i]
					+ " and is " + now);
		}
	}
}
