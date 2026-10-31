package com.eyunya.createtrainnext.gametest;

import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.EAST;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.GRAPH_TICKS;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.anchor;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.assertChain;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.assertConsistent;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.route;

import java.util.Map;
import java.util.Objects;

import com.eyunya.createtrainnext.CTNDataComponents;
import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.eyunya.createtrainnext.track.CurveWandSelection;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackPropagator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A turnout: an anchor in the middle of a route with a third curve, a branch, leaving it along the route's
 * heading beside the through line. What matters is what Create makes of it, so each test ends by asking the
 * graph: one node at the turnout with three ways out of it, the edges lying on the curves that are drawn,
 * and nothing left over once the turnout is changed or taken apart.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class BranchTests {

	private static final String EMPTY = "empty";

	/** And to settle after something is taken away or changed. */
	private static final int SETTLE_TICKS = 12;

	/** The through line, east along z = 16, and the turnout in it. */
	private static final BlockPos BEHIND = new BlockPos(6, 2, 16);
	private static final BlockPos TURNOUT = new BlockPos(14, 2, 16);
	private static final BlockPos AHEAD = new BlockPos(30, 2, 16);

	/** Where the branch goes, off to the north, and where a longer one carries on to. */
	private static final BlockPos BRANCH = new BlockPos(30, 2, 10);
	private static final BlockPos BRANCH_ON = new BlockPos(40, 2, 4);

	/** The through line and a branch ahead, its route running on from the turnout. */
	private record Turnout(AngledTrackAnchorBlockEntity behind, AngledTrackAnchorBlockEntity turnout,
		AngledTrackAnchorBlockEntity ahead, AngledTrackAnchorBlockEntity branch) {}

	private static Turnout turnout(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] line = route(helper, EAST, BEHIND, TURNOUT, AHEAD);
		AngledTrackAnchorBlockEntity branch = anchor(helper, BRANCH);
		line[1].branchTo(branch, true, true, arcEnd(helper, TURNOUT, line[1].heading(), BRANCH));
		return new Turnout(line[0], line[1], line[2], branch);
	}

	/**
	 * Which way a circular arc arrives at {@code to}, having left {@code from} along {@code heading}: the
	 * heading reflected about the chord. An arc is the same curve read either way, which is what the wand
	 * builds too.
	 */
	private static Vec3 arcEnd(GameTestHelper helper, BlockPos from, Vec3 heading, BlockPos to) {
		Vec3 chord = Vec3.atBottomCenterOf(to)
			.subtract(Vec3.atBottomCenterOf(from))
			.normalize();
		return chord.scale(2 * chord.dot(heading))
			.subtract(heading);
	}

	// -------------------------------------------------------------- the graph

	/**
	 * One node at the turnout's centre, three edges out of it. A train from behind may go either way, and one
	 * coming in along the branch may go on only behind: the turnout's own heading is shared by both lines that
	 * leave it, so the branch meets the through line at no angle, and the line ahead lies the other way.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aTurnoutIsOneNodeWithThreeWaysThrough(GameTestHelper helper) {
		Turnout t = turnout(helper);
		Level level = helper.getLevel();

		helper.assertTrue(t.turnout()
			.isBranch(t.branch()
				.getBlockPos()) && t.turnout()
					.getBranchAhead()
					.equals(t.branch()
						.getBlockPos()), "setup: the branch should be ahead of the turnout");
		helper.assertTrue(t.branch()
			.getPrev()
			.equals(t.turnout()
				.getBlockPos()), "the branch's route should run on from the turnout");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			// Two at each end of the line, one at the turnout, one at the end of the branch, which points
			// between the axes.
			GraphCleanlinessTests.assertClean(helper, "with a turnout", 6);

			Vec3 centre = Vec3.atBottomCenterOf(t.turnout()
				.getBlockPos());
			helper.assertTrue(t.turnout()
				.entryNode()
				.equals(centre) && t.turnout()
					.exitNode()
					.equals(centre), "a turnout's node should be the centre of its block");

			TrackNodeLocation at = new TrackNodeLocation(centre).in(level);
			TrackGraph graph = Create.RAILWAYS.sided(level)
				.getGraph(level, at);
			helper.assertTrue(graph != null && graph.locateNode(at) != null, "the turnout should be in the graph");
			TrackNode node = graph.locateNode(at);
			Map<TrackNode, TrackEdge> out = graph.getConnectionsFrom(node);
			helper.assertTrue(out.size() == 3, "three ways should leave the turnout, but " + out.size() + " do");

			TrackNode behind = farEnd(helper, graph, t.turnout()
				.buildCurveFromPrev(), centre);
			TrackNode ahead = farEnd(helper, graph, t.turnout()
				.buildCurveToNext(), centre);
			TrackNode branch = farEnd(helper, graph, t.turnout()
				.curveWith(t.branch()), centre);

			TrackEdge fromBehind = graph.getConnectionsFrom(behind)
				.get(node);
			TrackEdge fromBranch = graph.getConnectionsFrom(branch)
				.get(node);
			helper.assertTrue(fromBehind != null && fromBranch != null, "the turnout should be reached from behind and"
				+ " along the branch");
			helper.assertTrue(fromBehind.canTravelTo(out.get(ahead)), "a train from behind should be let on ahead");
			helper.assertTrue(fromBehind.canTravelTo(out.get(branch)), "a train from behind should be let onto the"
				+ " branch");
			helper.assertTrue(fromBranch.canTravelTo(out.get(behind)), "a train off the branch should be let on behind");
			helper.assertTrue(!fromBranch.canTravelTo(out.get(ahead)), "a train off the branch should not be let on"
				+ " ahead, which is back the way it came");

			for (BezierConnection curve : t.turnout()
				.curvesThrough())
				assertEdges(helper, curve);
			helper.succeed();
		});
	}

	/**
	 * The graph rebuilt from the turnout alone keeps the branch. Create rebuilds from a block by taking out what
	 * it can reach from there and walking out again, and a node at a block's centre is reached only from that
	 * block, so a turnout that did not report its branch would leave it out.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void rebuildingFromTheTurnoutKeepsTheBranch(GameTestHelper helper) {
		Turnout t = turnout(helper);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			BlockPos pos = t.turnout()
				.getBlockPos();
			TrackPropagator.onRailAdded(helper.getLevel(), pos, helper.getLevel()
				.getBlockState(pos));
			// Named one by one, not asked of the turnout: what is being tested is whether it reports them all.
			assertEdges(helper, t.turnout()
				.buildCurveFromPrev());
			assertEdges(helper, t.turnout()
				.buildCurveToNext());
			assertEdges(helper, t.turnout()
				.curveWith(t.branch()));
			GraphCleanlinessTests.assertClean(helper, "after rebuilding from a turnout", 6);
			helper.succeed();
		});
	}

	/** Turning the through line round leaves the branch where it was, on what is now the turnout's other side. */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void turningTheLineRoundLeavesTheBranchWhereItIs(GameTestHelper helper) {
		Turnout t = turnout(helper);
		BezierConnection before = t.turnout()
			.curveWith(t.branch());
		Vec3 turnoutAxis = t.turnout()
			.targetingAxis();
		Vec3 branchAxis = t.branch()
			.targetingAxis();

		helper.assertTrue(t.turnout()
			.reverseRoute() == 3, "the line through the turnout should be turned, and only that line");
		helper.assertTrue(t.turnout()
			.getBranchBehind()
			.equals(t.branch()
				.getBlockPos()) && t.turnout()
					.getBranchAhead() == null, "the branch should be behind the turnout now");
		helper.assertTrue(t.branch()
			.getPrev()
			.equals(t.turnout()
				.getBlockPos()), "the branch's own route should be left as it was");

		BezierConnection after = t.turnout()
			.curveWith(t.branch());
		helper.assertTrue(after != null && CurvePlanner.deviation(before, after) < 1.0E-6, "the branch should not have"
			+ " moved");
		helper.assertTrue(t.turnout()
			.targetingAxis()
			.dot(turnoutAxis) > 0
			&& t.branch()
				.targetingAxis()
				.dot(branchAxis) > 0, "the turnout and the branch should report their axes the way they did");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			GraphCleanlinessTests.assertClean(helper, "with the line through a turnout turned round", 6);
			for (BezierConnection curve : t.turnout()
				.curvesThrough())
				assertEdges(helper, curve);
			helper.succeed();
		});
	}

	/**
	 * A branch is a route of its own, beginning at the turnout. Turning it round makes it one that ends there,
	 * so the curve is built from its other end, in the same place.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void turningTheBranchRoundBuildsItsCurveFromTheOtherEnd(GameTestHelper helper) {
		Turnout t = turnout(helper);
		AngledTrackAnchorBlockEntity on = anchor(helper, BRANCH_ON);
		t.branch()
			.linkTo(on, null, arcEnd(helper, BRANCH, t.branch()
				.heading(), BRANCH_ON));
		assertChain(helper, t.branch(), BRANCH, BRANCH_ON);
		assertChain(helper, t.turnout(), BEHIND, TURNOUT, AHEAD);

		BezierConnection before = t.turnout()
			.curveWith(t.branch());
		helper.assertTrue(t.turnout()
			.owns(t.branch()), "setup: the turnout should hold the curve of a branch that runs on from it");

		helper.assertTrue(t.branch()
			.reverseRoute() == 2, "the branch should be turned, and only the branch");
		assertChain(helper, t.branch(), BRANCH_ON, BRANCH);
		helper.assertTrue(t.branch()
			.getNext()
			.equals(t.turnout()
				.getBlockPos()) && t.branch()
					.owns(t.turnout()), "the branch should run into the turnout now, and hold the curve");
		helper.assertTrue(t.turnout()
			.isBranch(t.branch()
				.getBlockPos()), "the turnout should still have the branch");

		BezierConnection after = t.turnout()
			.curveWith(t.branch());
		helper.assertTrue(after != null && after.bePositions.getFirst()
			.equals(t.branch()
				.getBlockPos()), "the curve should be built from the branch's end");
		helper.assertTrue(CurvePlanner.deviation(before, after) < 0.01, "the curve should be where it was, but is "
			+ CurvePlanner.deviation(before, after) + " off");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			for (BezierConnection curve : t.turnout()
				.curvesThrough())
				assertEdges(helper, curve);
			assertConsistent(helper, t.branch());
			helper.succeed();
		});
	}

	/**
	 * With the line beyond the turnout gone, what was the branch is the line: the route runs through, there is
	 * no turnout left, and nothing has moved.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void breakingTheLineBeyondATurnoutLeavesTheBranchAsTheLine(GameTestHelper helper) {
		Turnout t = turnout(helper);
		BezierConnection before = t.turnout()
			.curveWith(t.branch());

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			helper.setBlock(AHEAD, Blocks.AIR);
			helper.assertTrue(Objects.equals(t.turnout()
				.getNext(), t.branch()
					.getBlockPos()) && !t.turnout()
						.hasBranch(), "the branch should have become the line ahead");
			assertChain(helper, t.turnout(), BEHIND, TURNOUT, BRANCH);
			helper.assertTrue(CurvePlanner.deviation(before, t.turnout()
				.buildCurveToNext()) < 1.0E-6, "the curve should not have moved");

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				GraphCleanlinessTests.assertClean(helper, "after breaking the line beyond a turnout", 4);
				assertConsistent(helper, t.turnout());
				helper.succeed();
			});
		});
	}

	/**
	 * A branch whose route runs into the turnout cannot be the line on from it, so with the line beyond gone it
	 * stays a branch, on a side with no line. The turnout keeps its node at its centre, where the branch meets
	 * it, rather than turning into an end with its nodes on the boundary of its block.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void aBranchThatRunsInStaysABranchWhenTheLineBeyondGoes(GameTestHelper helper) {
		Turnout t = turnout(helper);
		t.branch()
			.reverseRoute();
		helper.assertTrue(Objects.equals(t.branch()
			.getNext(), t.turnout()
				.getBlockPos()), "setup: the branch should run into the turnout");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			helper.setBlock(AHEAD, Blocks.AIR);
			helper.assertTrue(t.turnout()
				.getNext() == null && Objects.equals(t.turnout()
					.getBranchAhead(), t.branch()
						.getBlockPos()), "the branch should still be a branch");
			Vec3 centre = Vec3.atBottomCenterOf(t.turnout()
				.getBlockPos());
			helper.assertTrue(t.turnout()
				.entryNode()
				.equals(centre) && t.turnout()
					.exitNode()
					.equals(centre), "the turnout should keep its node at its centre");

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				GraphCleanlinessTests.assertClean(helper, "after breaking the line beyond a branch that runs in", 4);
				for (BezierConnection curve : t.turnout()
					.curvesThrough())
					assertEdges(helper, curve);
				helper.succeed();
			});
		});
	}

	/** Taking the branch off leaves the line as it was, still with its node at the turnout's centre. */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void unlinkingTheBranchLeavesTheLine(GameTestHelper helper) {
		Turnout t = turnout(helper);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			t.branch()
				.unlink();
			helper.assertTrue(!t.turnout()
				.hasBranch(), "the turnout should have lost its branch");
			assertChain(helper, t.turnout(), BEHIND, TURNOUT, AHEAD);

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// The line as it was, and the end of the branch on its own, at its centre.
				GraphCleanlinessTests.assertClean(helper, "after unlinking a branch", 6);
				assertConsistent(helper, t.turnout());
				helper.succeed();
			});
		});
	}

	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void breakingATurnoutLeavesNothingBehind(GameTestHelper helper) {
		Turnout t = turnout(helper);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			helper.setBlock(TURNOUT, Blocks.AIR);
			helper.assertTrue(!t.behind()
				.isLinked() && !t.ahead()
					.isLinked()
				&& !t.branch()
					.isLinked(), "every anchor the turnout was linked to should be on its own");

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// Two for each anchor on an axis, one for the end of the branch.
				GraphCleanlinessTests.assertClean(helper, "after breaking a turnout", 5);
				helper.succeed();
			});
		});
	}

	/** A branch can be walked on like any other curve: the turnout lays a floor under the branch it holds. */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void aBranchHasAFloor(GameTestHelper helper) {
		Turnout t = turnout(helper);

		helper.runAfterDelay(20, () -> {
			CurveFloorTests.assertFloorFollowsRails(helper, t.turnout()
				.curveWith(t.branch()), "a branch");
			helper.succeed();
		});
	}

	/**
	 * A branch that goes on past its first anchor, which squares up to north: with the turnout gone, that
	 * anchor is the end of a route, and its node moves from its centre to the boundary of its block. The node
	 * at its centre has to go with the turnout, or it is left in the graph with nothing reporting it.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void breakingATurnoutWithALongerBranchLeavesNothingBehind(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] line = route(helper, EAST, BEHIND, TURNOUT, AHEAD);
		// A quarter circle from the turnout round to north, and on north from there.
		BlockPos bend = new BlockPos(22, 2, 8);
		BlockPos on = new BlockPos(22, 2, 2);
		AngledTrackAnchorBlockEntity[] branch = route(helper, CurveConsistencyTests.NORTH, bend, on);
		line[1].branchTo(branch[0], true, true, null);
		assertChain(helper, branch[0], bend, on);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			// Two at each end of the line, one at the turnout, one where the branch bends, two at its end.
			GraphCleanlinessTests.assertClean(helper, "with a longer branch", 8);
			helper.setBlock(TURNOUT, Blocks.AIR);

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// Two for every anchor left: the ends of the line on their own, and the branch's two ends.
				GraphCleanlinessTests.assertClean(helper, "after breaking the turnout of a longer branch", 8);
				assertConsistent(helper, branch[0]);
				helper.succeed();
			});
		});
	}

	/** Saved and loaded, a turnout keeps its branch, and an anchor without one writes nothing for it. */
	@GameTest(template = EMPTY)
	public static void aTurnoutKeepsItsBranchWhenSaved(GameTestHelper helper) {
		Turnout t = turnout(helper);
		Level level = helper.getLevel();

		CompoundTag plain = t.ahead()
			.saveWithFullMetadata(level.registryAccess());
		helper.assertTrue(!plain.contains("BranchAhead") && !plain.contains("BranchBehind"),
			"an anchor with no branch should not write one");

		AngledTrackAnchorBlockEntity original = t.turnout();
		CompoundTag saved = original.saveWithFullMetadata(level.registryAccess());
		BlockEntity loaded = BlockEntity.loadStatic(original.getBlockPos(), original.getBlockState(), saved,
			level.registryAccess());
		helper.assertTrue(loaded instanceof AngledTrackAnchorBlockEntity, "the saved turnout should load again");
		AngledTrackAnchorBlockEntity copy = (AngledTrackAnchorBlockEntity) loaded;
		helper.assertTrue(Objects.equals(copy.getBranchAhead(), original.getBranchAhead())
			&& copy.getBranchBehind() == null && Objects.equals(copy.getPrev(), original.getPrev())
			&& Objects.equals(copy.getNext(), original.getNext()), "the loaded turnout should keep its links");
		helper.succeed();
	}

	// ---------------------------------------------------------------- the wand

	/**
	 * Clicking an anchor a line runs through and then open ground ahead of it draws a branch, and builds what
	 * the ghost showed: the branch leaves the turnout along the line and arrives where it was aimed.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void theWandBranchesOffALine(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] line = route(helper, EAST, BEHIND, TURNOUT, AHEAD);
		Level level = helper.getLevel();

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(TURNOUT), helper.absolutePos(BRANCH),
			Direction.UP, 0);
		helper.assertTrue(plan.valid(), "a branch should be possible, but was refused: " + plan.error());
		helper.assertTrue(plan.start()
			.branch() == CurvePlanner.Branch.AHEAD, "the branch should leave on the side it was aimed at, ahead");

		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		helper.assertTrue(CurveConsistencyTests.click(helper, player, wand, TURNOUT)
			.consumesAction(), "the first click should pick the anchor to branch from");
		InteractionResult built = CurveConsistencyTests.click(helper, player, wand, BRANCH);
		helper.assertTrue(built.consumesAction(), "the second click should have built the branch, but gave " + built);

		AngledTrackAnchorBlockEntity branch = anchorAt(helper, BRANCH, false);
		helper.assertTrue(Objects.equals(line[1].getBranchAhead(), branch.getBlockPos()) && Objects.equals(branch
			.getPrev(), line[1].getBlockPos()), "the new anchor should be a branch ahead of the turnout, running on from it");
		assertChain(helper, line[1], BEHIND, TURNOUT, AHEAD);
		helper.assertTrue(CurvePlanner.deviation(plan.curve(), line[1].curveWith(branch)) < 1.0E-6,
			"what was built should be the curve the ghost showed");
		CurveWandSelection carried = wand.get(CTNDataComponents.CURVE_SELECTION);
		helper.assertTrue(carried != null && carried.pos()
			.equals(branch.getBlockPos()), "the selection should carry on from the end of the branch");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			GraphCleanlinessTests.assertClean(helper, "with a branch drawn by the wand", 6);
			for (BezierConnection curve : line[1].curvesThrough())
				assertEdges(helper, curve);
			helper.succeed();
		});
	}

	/** Aimed behind the turnout, the branch leaves beside the line behind it, against the line's heading. */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aBranchLeavesOnTheSideItIsAimedAt(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] line = route(helper, EAST, BEHIND, TURNOUT, AHEAD);
		BlockPos back = new BlockPos(2, 2, 8);

		CurvePlanner.Plan plan = CurvePlanner.plan(helper.getLevel(), helper.absolutePos(TURNOUT), helper.absolutePos(
			back), Direction.UP, 0);
		helper.assertTrue(plan.valid() && plan.start()
			.branch() == CurvePlanner.Branch.BEHIND, "a branch aimed behind should leave behind, but was " + plan.error()
				+ " / " + plan.start());
		helper.assertTrue(CurvePlanner.apply(helper.getLevel(), plan) == null, "the branch should have been built");
		helper.assertTrue(Objects.equals(line[1].getBranchBehind(), helper.absolutePos(back)) && line[1]
			.getBranchAhead() == null, "the branch should be behind the turnout");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			for (BezierConnection curve : line[1].curvesThrough())
				assertEdges(helper, curve);
			helper.succeed();
		});
	}

	/** A side has room for one branch; the other side still has room for its own. */
	@GameTest(template = EMPTY)
	public static void aSideTakesOneBranch(GameTestHelper helper) {
		Turnout t = turnout(helper);
		Level level = helper.getLevel();

		CurvePlanner.Plan again = CurvePlanner.plan(level, helper.absolutePos(TURNOUT), helper.absolutePos(
			new BlockPos(30, 2, 24)), Direction.UP, 0);
		helper.assertTrue("branch_taken".equals(again.error()), "a second branch ahead should be refused, but was "
			+ again.error());
		helper.assertTrue(CurvePlanner.canStartFrom(level, helper.absolutePos(TURNOUT)),
			"the side behind is still free for a branch, so the turnout can be started from");

		CurvePlanner.Plan behind = CurvePlanner.plan(level, helper.absolutePos(TURNOUT), helper.absolutePos(
			new BlockPos(2, 2, 24)), Direction.UP, 0);
		helper.assertTrue(behind.valid() && behind.start()
			.branch() == CurvePlanner.Branch.BEHIND, "a branch behind should still be possible, but was " + behind
				.error());
		helper.assertTrue(CurvePlanner.apply(level, behind) == null, "setup: the second branch should be built");
		helper.assertTrue(!CurvePlanner.canStartFrom(level, helper.absolutePos(TURNOUT)),
			"with a branch on both sides, nothing more can leave the turnout");
		helper.succeed();
	}

	/**
	 * A curve can end at a turnout as well as begin at one: a branch that comes in, whose route runs into the
	 * turnout. That joins the two lines up, so the selection is done with.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aCurveCanEndInABranch(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] line = route(helper, EAST, BEHIND, TURNOUT, AHEAD);
		AngledTrackAnchorBlockEntity lone = anchorAt(helper, BRANCH, true);

		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		helper.assertTrue(CurveConsistencyTests.click(helper, player, wand, BRANCH)
			.consumesAction(), "the first click should pick the lone anchor");
		InteractionResult built = CurveConsistencyTests.click(helper, player, wand, TURNOUT);
		helper.assertTrue(built.consumesAction(), "the second click should have built a branch into the turnout, but"
			+ " gave " + built);

		helper.assertTrue(Objects.equals(line[1].getBranchAhead(), lone.getBlockPos()) && Objects.equals(lone.getNext(),
			line[1].getBlockPos()), "the lone anchor should be a branch ahead of the turnout, running into it");
		helper.assertTrue(wand.get(CTNDataComponents.CURVE_SELECTION) == null,
			"the turnout has nothing left to carry on from that way, so the selection should be gone");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			for (BezierConnection curve : line[1].curvesThrough())
				assertEdges(helper, curve);
			helper.succeed();
		});
	}

	/** Two turnouts cannot be joined by one curve, since it would be a branch at both ends and no one's link. */
	@GameTest(template = EMPTY)
	public static void twoTurnoutsAreNotJoinedDirectly(GameTestHelper helper) {
		route(helper, EAST, BEHIND, TURNOUT, AHEAD);
		route(helper, EAST, new BlockPos(6, 2, 4), new BlockPos(14, 2, 4), new BlockPos(30, 2, 4));

		CurvePlanner.Plan plan = CurvePlanner.plan(helper.getLevel(), helper.absolutePos(TURNOUT), helper.absolutePos(
			new BlockPos(14, 2, 4)), Direction.UP, 0);
		helper.assertTrue("branch_to_branch".equals(plan.error()), "a curve between two turnouts should be refused,"
			+ " but was " + plan.error());
		helper.succeed();
	}

	// --------------------------------------------------------------- helpers

	/**
	 * The anchor at {@code relative}: placed there on the Z axis if {@code place}, or the one already there, as
	 * the wand left it.
	 */
	private static AngledTrackAnchorBlockEntity anchorAt(GameTestHelper helper, BlockPos relative, boolean place) {
		if (place)
			return CurveConsistencyTests.anchor(helper, relative);
		BlockEntity entity = helper.getBlockEntity(relative);
		helper.assertTrue(entity instanceof AngledTrackAnchorBlockEntity, "there should be an anchor at " + relative);
		return (AngledTrackAnchorBlockEntity) entity;
	}

	/** The node at whichever end of a curve meeting the turnout is not the turnout's, {@code centre}. */
	private static TrackNode farEnd(GameTestHelper helper, TrackGraph graph, BezierConnection curve, Vec3 centre) {
		helper.assertTrue(curve != null, "every way out of the turnout should have a curve");
		Vec3 far = curve.starts.getFirst()
			.distanceTo(centre) < 1.0E-6 ? curve.starts.getSecond() : curve.starts.getFirst();
		TrackNode node = graph.locateNode(new TrackNodeLocation(far).in(helper.getLevel()));
		helper.assertTrue(node != null, "the far end of every curve at the turnout should be in the graph");
		return node;
	}

	/**
	 * Checks that the graph has an edge each way along {@code drawn}, lying on it, as
	 * {@link CurveConsistencyTests#assertConsistent} does for a route.
	 */
	private static void assertEdges(GameTestHelper helper, BezierConnection drawn) {
		Level level = helper.getLevel();
		String link = drawn.bePositions.getFirst()
			.toShortString() + " -> "
			+ drawn.bePositions.getSecond()
				.toShortString();
		TrackNodeLocation fromLocation = new TrackNodeLocation(drawn.starts.getFirst()).in(level);
		TrackNodeLocation toLocation = new TrackNodeLocation(drawn.starts.getSecond()).in(level);
		TrackGraph graph = Create.RAILWAYS.sided(level)
			.getGraph(level, fromLocation);
		helper.assertTrue(graph != null && graph.locateNode(fromLocation) != null && graph.locateNode(toLocation) != null,
			link + " should be in the graph by now");
		TrackEdge forward = graph.getConnectionsFrom(graph.locateNode(fromLocation))
			.get(graph.locateNode(toLocation));
		TrackEdge back = graph.getConnectionsFrom(graph.locateNode(toLocation))
			.get(graph.locateNode(fromLocation));
		helper.assertTrue(forward != null && back != null, link + " should be an edge in both directions");
		double forwardOff = CurvePlanner.deviation(drawn, forward.getTurn());
		double backOff = CurvePlanner.deviation(drawn, back.getTurn());
		helper.assertTrue(forwardOff <= CurvePlanner.MAX_ORIENTATION_DEVIATION && backOff
			<= CurvePlanner.MAX_ORIENTATION_DEVIATION, link + ": the edges should lie on the drawn curve, but are "
				+ forwardOff + " and " + backOff + " off");
	}
}
