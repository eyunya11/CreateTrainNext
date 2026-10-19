package com.eyunya.createtrainnext.gametest;

import java.util.List;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AnchorAxis;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlock;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.eyunya.createtrainnext.track.CurveWandItem;
import com.eyunya.createtrainnext.track.CurveWandSelection;
import com.simibubi.create.content.trains.track.ITrackBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks on joining one route to another with the wand, run against a real server level.
 *
 * <p>Each test lays two short straight routes on the same line out of anchors, asks the planner
 * what clicking from the end of one to the end of the other would do, builds it, and then checks
 * that what is left is one route that hangs together: every anchor points the way the route goes
 * past it. Which two ends face one another is what varies, because that is what decides whether a
 * route has to be turned round on the way.
 *
 * <p>The other thing checked throughout is what the anchors around a change report. A signal is
 * stored as a sign against that axis, so an end that is joined or carried on has to go on reporting
 * the same way after it stops being an end, and an anchor that is left as an end because its
 * neighbour was unlinked or broken has to go on reporting the same way as when it was in the middle
 * of a route — whichever way the route happens to run relative to the block's own axis.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class RouteJoinTests {

	private static final String EMPTY = "empty";

	/** Yaw 270 is east, and yaw 90 is west. */
	private static final float EAST = 270.0F;
	private static final float WEST = 90.0F;

	private static final int Y = 2;
	private static final int Z = 16;

	/** A block on the line every route here is laid along, {@code x} blocks in. */
	private static BlockPos at(int x) {
		return new BlockPos(x, Y, Z);
	}

	/**
	 * Carrying a route on into the head of another, the way it has always been done: the first
	 * route's last anchor to the second route's first. Nothing has to be turned round for this.
	 *
	 * <p>It is also the plainest case of ending a curve at an anchor that already has a curve on
	 * its other side, which the planner has to be able to do whichever way it is asked.
	 */
	@GameTest(template = EMPTY)
	public static void endsAtAnExistingRouteHead(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, EAST, at(4), at(12), at(20));
		AngledTrackAnchorBlockEntity[] second = route(helper, EAST, at(30), at(38), at(46));
		Level level = helper.getLevel();
		Vec3[] firstHeadings = headings(first);
		Vec3[] secondHeadings = headings(second);
		Vec3 lastBefore = reportedAxis(level, first[2]);
		Vec3 headBefore = reportedAxis(level, second[0]);

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(at(20)), helper.absolutePos(at(30)),
			Direction.UP, 0);
		helper.assertTrue(plan.valid(), "joining a tail to a head should be allowed, but was refused: " + plan.error());
		helper.assertTrue(plan.forward(), "the curve should run from the first route into the second");
		helper.assertTrue(!plan.reverseEnd(), "nothing needs turning round to join a tail to a head");

		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the join should have been built");

		assertRoute(helper, first[0], at(4), at(12), at(20), at(30), at(38), at(46));
		assertTurned(helper, first, firstHeadings, false);
		assertTurned(helper, second, secondHeadings, false);
		assertSameWay(helper, "the last anchor of the first route", lastBefore, reportedAxis(level, first[2]));
		assertSameWay(helper, "the first anchor of the second route", headBefore, reportedAxis(level, second[0]));

		helper.succeed();
	}

	/**
	 * Two routes laid away from each other whose last anchors face one another across a gap. The
	 * last anchor of a route is the one with a free side, so both of these want the same slot, and
	 * the wand used to refuse. Now the far route is turned round and the two are joined.
	 *
	 * <p>The anchors in the middle of the route that was turned must still report the axis they did,
	 * since that is what the signals standing on them were placed against.
	 */
	@GameTest(template = EMPTY)
	public static void joinsTwoTailsFacingEachOther(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, EAST, at(4), at(12), at(20));
		AngledTrackAnchorBlockEntity[] second = route(helper, WEST, at(46), at(38), at(30));
		Level level = helper.getLevel();
		Vec3[] firstHeadings = headings(first);
		Vec3[] secondHeadings = headings(second);
		Vec3 axisBefore = reportedAxis(level, second[1]);
		Vec3 firstEndBefore = reportedAxis(level, first[2]);
		Vec3 farEndBefore = reportedAxis(level, second[2]);

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(at(20)), helper.absolutePos(at(30)),
			Direction.UP, 0);
		helper.assertTrue(plan.valid(), "joining two tails should be allowed, but was refused: " + plan.error());
		helper.assertTrue(plan.reverseEnd(), "the far route should have to be turned round");
		helper.assertTrue(plan.forward(), "the curve should run from the first route into the second");

		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the join should have been built");

		assertRoute(helper, first[0], at(4), at(12), at(20), at(30), at(38), at(46));
		assertTurned(helper, first, firstHeadings, false);
		assertTurned(helper, second, secondHeadings, true);

		assertSameWay(helper, "an anchor in the middle of the turned route", axisBefore, reportedAxis(level, second[1]));
		assertSameWay(helper, "the last anchor of the route clicked first", firstEndBefore,
			reportedAxis(level, first[2]));
		assertSameWay(helper, "the last anchor of the route that was turned", farEndBefore,
			reportedAxis(level, second[2]));

		helper.succeed();
	}

	/**
	 * The same, with the first anchors of two routes facing one another instead. The first anchor
	 * of a route is the one with a free prev, and both of these want that slot — which makes the
	 * curve run from the far end into the near one.
	 */
	@GameTest(template = EMPTY)
	public static void joinsTwoHeadsFacingEachOther(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, WEST, at(22), at(14), at(6));
		AngledTrackAnchorBlockEntity[] second = route(helper, EAST, at(30), at(38), at(46));
		Level level = helper.getLevel();
		Vec3[] firstHeadings = headings(first);
		Vec3[] secondHeadings = headings(second);
		Vec3 axisBefore = reportedAxis(level, second[1]);
		Vec3 firstEndBefore = reportedAxis(level, first[0]);
		Vec3 farEndBefore = reportedAxis(level, second[0]);

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(at(22)), helper.absolutePos(at(30)),
			Direction.UP, 0);
		helper.assertTrue(plan.valid(), "joining two heads should be allowed, but was refused: " + plan.error());
		helper.assertTrue(plan.reverseEnd(), "the far route should have to be turned round");
		helper.assertTrue(!plan.forward(), "the curve should run from the far route into the first");

		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the join should have been built");

		assertRoute(helper, second[2], at(46), at(38), at(30), at(22), at(14), at(6));
		assertTurned(helper, first, firstHeadings, false);
		assertTurned(helper, second, secondHeadings, true);

		assertSameWay(helper, "an anchor in the middle of the turned route", axisBefore, reportedAxis(level, second[1]));
		assertSameWay(helper, "the first anchor of the route clicked first", firstEndBefore,
			reportedAxis(level, first[0]));
		assertSameWay(helper, "the first anchor of the route that was turned", farEndBefore,
			reportedAxis(level, second[0]));

		helper.succeed();
	}

	/**
	 * A join that needs the far route turned round, when that route cannot be reached in full, is
	 * refused with nothing changed.
	 *
	 * <p>The ghost is drawn from what the client has loaded, so the plan itself looks fine. Only
	 * the server can find out, and it has to find out before it places or links anything.
	 */
	@GameTest(template = EMPTY)
	public static void refusesAJoinWhoseFarRouteCannotBeTurned(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, EAST, at(4), at(12), at(20));
		AngledTrackAnchorBlockEntity[] second = route(helper, WEST, at(46), at(38), at(30));
		Level level = helper.getLevel();
		Vec3[] secondHeadings = headings(second);
		boolean[] secondFlags = flags(second);

		// Takes the far end of the second route out from under its neighbour, which still links to
		// it. Asked for again it comes back new and blank, with no link to agree with.
		level.removeBlockEntity(helper.absolutePos(at(46)));

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(at(20)), helper.absolutePos(at(30)),
			Direction.UP, 0);
		helper.assertTrue(plan.valid() && plan.reverseEnd(),
			"the plan only looks at the anchors it joins, so it should still be valid: " + plan.error());

		String failure = CurvePlanner.apply(level, plan);
		helper.assertTrue("route_unavailable".equals(failure), "the join should be refused as route_unavailable, not "
			+ failure);

		helper.assertTrue(first[2].getNext() == null, "the first route should not have been joined onto");
		helper.assertTrue(second[2].getNext() == null, "the second route should not have been joined onto");
		assertTurned(helper, second, secondHeadings, false);
		assertSameFlags(helper, second, secondFlags);

		helper.succeed();
	}

	/**
	 * The other way a join can fail once it has started: the far route has been turned round, and an
	 * anchor then has nowhere to go. The route has to be turned back, or the player is told there was
	 * no room by a wand that has turned a route round and built nothing.
	 *
	 * <p>The start is made to need an anchor above the top of the world, which nothing can be placed
	 * in, standing for a block that turns out not to take one.
	 */
	@GameTest(template = EMPTY)
	public static void putsTheFarRouteBackWhenTheJoinRunsOutOfRoom(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, EAST, at(4), at(12), at(20));
		AngledTrackAnchorBlockEntity[] second = route(helper, WEST, at(46), at(38), at(30));
		Level level = helper.getLevel();
		Vec3[] secondHeadings = headings(second);
		boolean[] secondFlags = flags(second);

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(at(20)), helper.absolutePos(at(30)),
			Direction.UP, 0);
		helper.assertTrue(plan.valid() && plan.reverseEnd(),
			"setup: the join should turn the far route round: " + plan.error());

		CurvePlanner.Endpoint nowhere = new CurvePlanner.Endpoint(null,
			new BlockPos(0, level.getMaxBuildHeight() + 8, 0), true, null, false, false, CurvePlanner.Side.EITHER, 0.0);
		CurvePlanner.Plan doomed = new CurvePlanner.Plan(null, nowhere, plan.end(), plan.startHeading(),
			plan.endHeading(), plan.curve(), plan.adjustable(), plan.turnDegrees(), plan.forward(),
			plan.reverseEnd(), null, null, 0, 0, 0);

		String failure = CurvePlanner.apply(level, doomed);
		helper.assertTrue("no_room".equals(failure), "the join should be refused as no_room, not " + failure);

		helper.assertTrue(first[2].getNext() == null, "the first route should not have been joined onto");
		helper.assertTrue(second[2].getNext() == null, "the second route should not have been joined onto");
		assertTurned(helper, second, secondHeadings, false);
		assertSameFlags(helper, second, secondFlags);

		helper.succeed();
	}

	/**
	 * Carrying a route on out of its last anchor, when the route runs against that anchor's own
	 * axis: west here, where the block's axis points east.
	 *
	 * <p>A last anchor reports its block's discrete axis, and once a curve leaves it, an axis
	 * measured along the route. Where those point opposite ways, which is about half of all
	 * headings, the sign used to change with the extension, and every signal and station standing
	 * on that anchor would have been put on the other edge of the graph the next time it was rebuilt.
	 */
	@GameTest(template = EMPTY)
	public static void carryingARouteOnKeepsWhatItsLastAnchorReports(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = route(helper, WEST, at(30), at(22));
		Level level = helper.getLevel();
		Vec3 before = reportedAxis(level, route[1]);

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(at(22)), helper.absolutePos(at(10)),
			Direction.UP, 0);
		helper.assertTrue(plan.valid() && plan.forward(), "the route should be carried on forwards: " + plan.error());
		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the curve should have been built");

		assertRoute(helper, route[0], at(30), at(22), at(10));
		assertSameWay(helper, "the anchor that used to be last", before, reportedAxis(level, route[1]));

		helper.succeed();
	}

	/** The same out of the first anchor, which is carried on backwards: the curve runs into it. */
	@GameTest(template = EMPTY)
	public static void carryingARouteOnBackwardsKeepsWhatItsFirstAnchorReports(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = route(helper, WEST, at(22), at(14));
		Level level = helper.getLevel();
		Vec3 before = reportedAxis(level, route[0]);

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(at(22)), helper.absolutePos(at(34)),
			Direction.UP, 0);
		helper.assertTrue(plan.valid() && !plan.forward(),
			"the route should be carried on backwards: " + plan.error());
		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the curve should have been built");

		assertRoute(helper, route[1], at(34), at(22), at(14));
		assertSameWay(helper, "the anchor that used to be first", before, reportedAxis(level, route[0]));

		helper.succeed();
	}

	// ------------------------------------------------------------- taking apart

	/**
	 * Unlinking the last anchor of a route leaves the one before it as the new last anchor, which
	 * reports its block's own axis where it used to report one measured along the route.
	 *
	 * <p>The route runs west, against the block's axis, so those two point opposite ways and the
	 * anchor that has just become an end would, without care, report the reverse of what it did
	 * while a signal was being placed on it.
	 */
	@GameTest(template = EMPTY)
	public static void unlinkingTheEndOfARouteKeepsWhatItsNeighbourReports(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = route(helper, WEST, at(46), at(38), at(30), at(22));
		Level level = helper.getLevel();
		Vec3 before = reportedAxis(level, route[2]);

		route[3].unlink();

		helper.assertTrue(route[2].getNext() == null && route[2].getPrev() != null,
			"the anchor before the one unlinked should now be the end of the route");
		assertSameWay(helper, "the anchor that has become the end", before, reportedAxis(level, route[2]));

		helper.succeed();
	}

	/**
	 * Breaking an anchor is the same thing as unlinking it, since that is what removing the block
	 * does first — and it is how a route is most often taken apart.
	 */
	@GameTest(template = EMPTY)
	public static void breakingTheEndOfARouteKeepsWhatItsNeighbourReports(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = route(helper, WEST, at(46), at(38), at(30), at(22));
		Level level = helper.getLevel();
		Vec3 before = reportedAxis(level, route[2]);

		helper.setBlock(at(22), Blocks.AIR);

		helper.assertTrue(route[2].getNext() == null, "the anchor before the one broken should now be the end");
		assertSameWay(helper, "the anchor that has become the end", before, reportedAxis(level, route[2]));

		helper.succeed();
	}

	/** Unlinking in the middle leaves two ends, one on each side, and both have to hold. */
	@GameTest(template = EMPTY)
	public static void unlinkingInTheMiddleKeepsWhatBothNeighboursReport(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = route(helper, WEST, at(46), at(38), at(30), at(22), at(14));
		Level level = helper.getLevel();
		Vec3 behind = reportedAxis(level, route[1]);
		Vec3 ahead = reportedAxis(level, route[3]);

		route[2].unlink();

		helper.assertTrue(route[1].getNext() == null && route[3].getPrev() == null,
			"both neighbours should now be ends");
		assertSameWay(helper, "the anchor that has become the end of the first half", behind,
			reportedAxis(level, route[1]));
		assertSameWay(helper, "the anchor that has become the start of the second half", ahead,
			reportedAxis(level, route[3]));

		helper.succeed();
	}

	/**
	 * Putting an anchor part way along a curve is built out of links, and the anchors either side of
	 * it must come out reporting what they did. It never changed which axis anything reports, so
	 * this is here to catch the day that stops being true.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void splittingACurveKeepsWhatTheAnchorsAroundItReport(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = route(helper, WEST, at(46), at(38), at(30));
		Level level = helper.getLevel();
		Vec3[] before = new Vec3[route.length];
		for (int i = 0; i < route.length; i++)
			before[i] = reportedAxis(level, route[i]);

		// Curves are found through an index of the anchors that are loaded, and a block entity only
		// reaches it a few ticks after it is placed.
		helper.runAfterDelay(5, () -> {
			CurvePlanner.SplitTarget target =
				CurvePlanner.findCurveNear(level, Vec3.atBottomCenterOf(helper.absolutePos(at(42))), 2.0);
			helper.assertTrue(target != null && target.valid(), "there should be a curve to put a point on near x=42, but "
				+ (target == null ? "none was found" : "it was refused: " + target.error()));
			helper.assertTrue(CurvePlanner.split(level, target), "the point should have been put on the curve");

			for (int i = 0; i < route.length; i++)
				assertSameWay(helper, "anchor " + i, before[i], reportedAxis(level, route[i]));

			helper.succeed();
		});
	}

	// ------------------------------------------------------------- the wand

	/**
	 * Two clicks join two routes laid away from each other, and the wand lets go of the result.
	 *
	 * <p>Once joined, the anchor just reached has a curve on both sides and there is nowhere left
	 * to carry on from. A selection kept standing would be a ghost that is red wherever it is aimed.
	 */
	@GameTest(template = EMPTY)
	public static void wandJoinsTwoTailsInTwoClicks(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, EAST, at(4), at(12), at(20));
		route(helper, WEST, at(46), at(38), at(30));
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());

		helper.assertTrue(click(helper, player, wand, at(20)).consumesAction(),
			"the first click should pick the end of the first route");
		helper.assertTrue(CurveWandItem.selectionIn(wand, helper.getLevel()) != null,
			"the wand should be holding a selection");

		InteractionResult joined = click(helper, player, wand, at(30));
		helper.assertTrue(joined.consumesAction(), "the second click should have built the join, but gave " + joined);

		assertRoute(helper, first[0], at(4), at(12), at(20), at(30), at(38), at(46));
		helper.assertTrue(CurveWandItem.selectionIn(wand, helper.getLevel()) == null,
			"a joined route has nowhere to carry on from, so the selection should have been dropped");

		helper.succeed();
	}

	/**
	 * A join the server refuses leaves the wand where it was, so the player can try again once the
	 * route is loaded, and changes nothing in the world.
	 */
	@GameTest(template = EMPTY)
	public static void wandKeepsItsSelectionWhenAJoinIsRefused(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, EAST, at(4), at(12), at(20));
		AngledTrackAnchorBlockEntity[] second = route(helper, WEST, at(46), at(38), at(30));
		Vec3[] secondHeadings = headings(second);
		boolean[] secondFlags = flags(second);
		helper.getLevel()
			.removeBlockEntity(helper.absolutePos(at(46)));
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());

		helper.assertTrue(click(helper, player, wand, at(20)).consumesAction(),
			"the first click should pick the end of the first route");

		InteractionResult refused = click(helper, player, wand, at(30));
		helper.assertTrue(refused == InteractionResult.FAIL, "the second click should have been refused, but gave "
			+ refused);

		CurveWandSelection selection = CurveWandItem.selectionIn(wand, helper.getLevel());
		helper.assertTrue(selection != null && selection.pos()
			.equals(helper.absolutePos(at(20))), "the selection should still be on the first route's end");
		helper.assertTrue(first[2].getNext() == null && second[2].getNext() == null, "nothing should have been joined");
		assertTurned(helper, second, secondHeadings, false);
		assertSameFlags(helper, second, secondFlags);

		helper.succeed();
	}

	/**
	 * Starting a curve at the first anchor of a route and drawing backwards out of it, with the
	 * wand rather than the planner: the case that was refused with "This end already continues into
	 * a curve", and the selection then carries on from the new end the same way.
	 */
	@GameTest(template = EMPTY)
	public static void wandDrawsBackwardsOutOfARoutesFirstAnchor(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] route = route(helper, EAST, at(20), at(28));
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());

		helper.assertTrue(click(helper, player, wand, at(20)).consumesAction(),
			"the first anchor of a route should be somewhere a curve can be started");

		InteractionResult built = click(helper, player, wand, at(8));
		helper.assertTrue(built.consumesAction(), "the curve should have been built backwards, but gave " + built);
		helper.assertTrue(helper.absolutePos(at(8))
			.equals(route[0].getPrev()), "the first anchor should now arrive from the new one");

		CurveWandSelection selection = CurveWandItem.selectionIn(wand, helper.getLevel());
		helper.assertTrue(selection != null && selection.pos()
			.equals(helper.absolutePos(at(8))), "the wand should carry on from the end it just reached");

		helper.succeed();
	}

	/** Right clicks the block at {@code relative} with the wand, the way the game would. */
	private static InteractionResult click(GameTestHelper helper, Player player, ItemStack wand,
		BlockPos relative) {
		BlockPos pos = helper.absolutePos(relative);
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
		return CTNRegistry.CURVE_WAND.get()
			.useOn(new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, wand, hit));
	}

	// --------------------------------------------------------------- helpers

	/** Links the given positions in order into one route, every anchor pointed along {@code yaw}. */
	private static AngledTrackAnchorBlockEntity[] route(GameTestHelper helper, float yaw, BlockPos... positions) {
		AngledTrackAnchorBlockEntity[] anchors = new AngledTrackAnchorBlockEntity[positions.length];
		for (int i = 0; i < anchors.length; i++)
			anchors[i] = anchor(helper, positions[i]);

		Vec3 heading = AngledTrackAnchorBlockEntity.directionFromYaw(yaw);
		for (int i = 0; i + 1 < anchors.length; i++)
			anchors[i].linkTo(anchors[i + 1], i == 0 ? heading : null, heading);
		return anchors;
	}

	/** Puts an anchor on the Z axis at a position relative to the test, and returns its block entity. */
	private static AngledTrackAnchorBlockEntity anchor(GameTestHelper helper, BlockPos relative) {
		helper.setBlock(relative, CTNRegistry.ANGLED_TRACK_ANCHOR.get()
			.defaultBlockState()
			.setValue(AngledTrackAnchorBlock.AXIS, AnchorAxis.Z_ORTHO));

		BlockEntity entity = helper.getBlockEntity(relative);
		helper.assertTrue(entity instanceof AngledTrackAnchorBlockEntity,
			"no anchor block entity where one was placed at " + relative);
		return (AngledTrackAnchorBlockEntity) entity;
	}

	/**
	 * Checks that the route through {@code member} is exactly the anchors at {@code expected}, in
	 * that order, and that it hangs together: between each pair, both anchors point the way the
	 * route goes.
	 */
	private static void assertRoute(GameTestHelper helper, AngledTrackAnchorBlockEntity member, BlockPos... expected) {
		List<AngledTrackAnchorBlockEntity> route = member.route();
		helper.assertTrue(route != null, "the route should be one that can be walked in full");
		helper.assertTrue(route.size() == expected.length,
			"the route should have " + expected.length + " anchors, but has " + route.size());

		for (int i = 0; i < expected.length; i++)
			helper.assertTrue(route.get(i)
				.getBlockPos()
				.equals(helper.absolutePos(expected[i])), "anchor " + i + " of the route should be at " + expected[i]);

		for (int i = 0; i + 1 < route.size(); i++) {
			Vec3 along = Vec3.atBottomCenterOf(route.get(i + 1)
				.getBlockPos())
				.subtract(Vec3.atBottomCenterOf(route.get(i)
					.getBlockPos()));
			helper.assertTrue(route.get(i)
				.heading()
				.dot(along) > 0
				&& route.get(i + 1)
					.heading()
					.dot(along) > 0,
				"anchors " + i + " and " + (i + 1) + " should both point the way the route goes between them");
		}
	}

	private static boolean[] flags(AngledTrackAnchorBlockEntity[] anchors) {
		boolean[] flags = new boolean[anchors.length];
		for (int i = 0; i < anchors.length; i++)
			flags[i] = anchors[i].isFlipped();
		return flags;
	}

	/** Checks that nothing was held or released: a refusal changes no anchor, flags included. */
	private static void assertSameFlags(GameTestHelper helper, AngledTrackAnchorBlockEntity[] anchors, boolean[] before) {
		for (int i = 0; i < anchors.length; i++)
			helper.assertTrue(anchors[i].isFlipped() == before[i], "anchor " + i + " should have its flag as it was");
	}

	private static Vec3[] headings(AngledTrackAnchorBlockEntity[] anchors) {
		Vec3[] headings = new Vec3[anchors.length];
		for (int i = 0; i < anchors.length; i++)
			headings[i] = anchors[i].heading();
		return headings;
	}

	/**
	 * Checks whether a route was turned round, by which way its anchors point rather than by how it
	 * remembers having been: the flag that keeps the reported axis put is chosen afresh at a join
	 * and says nothing about whether the route was turned.
	 */
	private static void assertTurned(GameTestHelper helper, AngledTrackAnchorBlockEntity[] anchors, Vec3[] before,
		boolean turned) {
		for (int i = 0; i < anchors.length; i++) {
			double dot = anchors[i].heading()
				.dot(before[i]);
			helper.assertTrue(turned ? dot < -0.999 : dot > 0.999, "anchor " + i + (turned ? " should have" : " should not have")
				+ " been turned round");
		}
	}

	/** Checks that an axis points the way it did, allowing for the curves around it having moved a little. */
	private static void assertSameWay(GameTestHelper helper, String what, Vec3 before, Vec3 after) {
		helper.assertTrue(before.normalize()
			.dot(after.normalize()) > 0.9,
			what + " should report the same way as before: " + before + " became " + after);
	}

	/** The axis Create gets when it asks the block, which is what placement is compared against. */
	private static Vec3 reportedAxis(Level level, AngledTrackAnchorBlockEntity anchor) {
		BlockPos pos = anchor.getBlockPos();
		BlockState state = level.getBlockState(pos);
		return ((ITrackBlock) state.getBlock()).getTrackAxes(level, pos, state)
			.get(0);
	}
}
