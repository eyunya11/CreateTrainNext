package com.eyunya.createtrainnext.gametest;

import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.EAST;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.GRAPH_TICKS;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.anchor;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.assertChain;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.assertConsistent;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNDataComponents;
import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.eyunya.createtrainnext.track.CurveRepair;
import com.eyunya.createtrainnext.track.CurveWandSelection;
import com.eyunya.createtrainnext.track.TurnoutSteering;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.entity.TravellingPoint.SteerDirection;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackShape;

import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Pair;
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

	/** Where a second branch ahead goes, off to the south, which makes a three-way turnout. */
	private static final BlockPos BRANCH_SOUTH = new BlockPos(30, 2, 22);

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
				.getBlockPos()) && onlyBranch(t.turnout(), true)
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
		helper.assertTrue(onlyBranch(t.turnout(), false)
			.equals(t.branch()
				.getBlockPos()) && onlyBranch(t.turnout(), true) == null, "the branch should be behind the turnout now");
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
	 * stays a branch, on a side with no line. The turnout is an end now, with its nodes on the boundary of its
	 * block like any end along an axis, and the branch meets it at the one on its side.
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
				.getNext() == null && Objects.equals(onlyBranch(t.turnout(), true), t.branch()
						.getBlockPos()), "the branch should still be a branch");
			Vec3 east = Vec3.atBottomCenterOf(t.turnout()
				.getBlockPos())
				.add(0.5, 0, 0);
			helper.assertTrue(t.turnout()
				.exitNode()
				.equals(east), "the turnout should be an end, its node ahead on the boundary of its block");
			BezierConnection branch = t.turnout()
				.curveWith(t.branch());
			helper.assertTrue(branch != null && branch.starts.getSecond()
				.equals(east), "the branch should meet the turnout at the node on its side");

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// Two at each end of what is left of the line, one at the end of the branch.
				GraphCleanlinessTests.assertClean(helper, "after breaking the line beyond a branch that runs in", 5);
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
		helper.assertTrue(!plain.contains("BranchesAhead") && !plain.contains("BranchesBehind"),
			"an anchor with no branch should not write one");

		AngledTrackAnchorBlockEntity original = t.turnout();
		CompoundTag saved = original.saveWithFullMetadata(level.registryAccess());
		BlockEntity loaded = BlockEntity.loadStatic(original.getBlockPos(), original.getBlockState(), saved,
			level.registryAccess());
		helper.assertTrue(loaded instanceof AngledTrackAnchorBlockEntity, "the saved turnout should load again");
		AngledTrackAnchorBlockEntity copy = (AngledTrackAnchorBlockEntity) loaded;
		helper.assertTrue(Objects.equals(onlyBranch(copy, true), onlyBranch(original, true))
			&& onlyBranch(copy, false) == null && Objects.equals(copy.getPrev(), original.getPrev())
			&& Objects.equals(copy.getNext(), original.getNext()), "the loaded turnout should keep its links");
		// A save made while an anchor took one branch a side holds it under a key of its own.
		CompoundTag old = saved.copy();
		old.remove("BranchesAhead");
		old.putLong("BranchAhead", t.branch()
			.getBlockPos()
			.asLong());
		BlockEntity fromOld = BlockEntity.loadStatic(original.getBlockPos(), original.getBlockState(), old,
			level.registryAccess());
		helper.assertTrue(fromOld instanceof AngledTrackAnchorBlockEntity oldCopy && Objects.equals(onlyBranch(oldCopy,
			true), t.branch()
				.getBlockPos()), "a save with the single branch key should load its branch");
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
		helper.assertTrue(Objects.equals(onlyBranch(line[1], true), branch.getBlockPos()) && Objects.equals(branch
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
		helper.assertTrue(Objects.equals(onlyBranch(line[1], false), helper.absolutePos(back)) && onlyBranch(line[1], true) == null, "the branch should be behind the turnout");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			for (BezierConnection curve : line[1].curvesThrough())
				assertEdges(helper, curve);
			helper.succeed();
		});
	}

	/**
	 * A side has room for two branches, which makes one anchor a three-way turnout, and no more. The other side
	 * has room for two of its own.
	 */
	@GameTest(template = EMPTY)
	public static void aSideTakesTwoBranches(GameTestHelper helper) {
		Turnout t = turnout(helper);
		Level level = helper.getLevel();

		CurvePlanner.Plan second = CurvePlanner.plan(level, helper.absolutePos(TURNOUT), helper.absolutePos(
			BRANCH_SOUTH), Direction.UP, 0);
		helper.assertTrue(second.valid() && second.start()
			.branch() == CurvePlanner.Branch.AHEAD, "a second branch ahead should be possible, but was " + second
				.error());
		helper.assertTrue(CurvePlanner.apply(level, second) == null, "setup: the second branch should be built");
		helper.assertTrue(t.turnout()
			.getBranches(true)
			.size() == 2, "the turnout should have two branches ahead");

		CurvePlanner.Plan third = CurvePlanner.plan(level, helper.absolutePos(TURNOUT), helper.absolutePos(
			new BlockPos(30, 2, 28)), Direction.UP, 0);
		helper.assertTrue("branch_taken".equals(third.error()), "a third branch ahead should be refused, but was "
			+ third.error());
		helper.assertTrue(CurvePlanner.canStartFrom(level, helper.absolutePos(TURNOUT)),
			"the side behind is still free for branches, so the turnout can be started from");

		for (BlockPos back : new BlockPos[] { new BlockPos(2, 2, 24), new BlockPos(2, 2, 8) }) {
			CurvePlanner.Plan behind = CurvePlanner.plan(level, helper.absolutePos(TURNOUT), helper.absolutePos(back),
				Direction.UP, 0);
			helper.assertTrue(behind.valid() && behind.start()
				.branch() == CurvePlanner.Branch.BEHIND, "a branch behind should still be possible, but was " + behind
					.error());
			helper.assertTrue(CurvePlanner.apply(level, behind) == null, "setup: a branch behind should be built");
		}
		helper.assertTrue(!CurvePlanner.canStartFrom(level, helper.absolutePos(TURNOUT)),
			"with two branches on both sides, nothing more can leave the turnout");
		helper.succeed();
	}

	/**
	 * A three-way turnout: the line and a branch either side of it, from one node. A train from behind may take
	 * any of the three, and one off a branch may go on only behind, not across onto the other branch.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aThreeWayTurnoutIsOneNodeWithFourWays(GameTestHelper helper) {
		Turnout t = turnout(helper);
		Level level = helper.getLevel();
		AngledTrackAnchorBlockEntity south = anchor(helper, BRANCH_SOUTH);
		t.turnout()
			.branchTo(south, true, true, arcEnd(helper, TURNOUT, t.turnout()
				.heading(), BRANCH_SOUTH));

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			// Two at each end of the line, one at the turnout, one at the end of each branch.
			GraphCleanlinessTests.assertClean(helper, "with a three-way turnout", 7);

			Vec3 centre = Vec3.atBottomCenterOf(t.turnout()
				.getBlockPos());
			TrackNodeLocation at = new TrackNodeLocation(centre).in(level);
			TrackGraph graph = Create.RAILWAYS.sided(level)
				.getGraph(level, at);
			helper.assertTrue(graph != null && graph.locateNode(at) != null, "the turnout should be in the graph");
			TrackNode node = graph.locateNode(at);
			Map<TrackNode, TrackEdge> out = graph.getConnectionsFrom(node);
			helper.assertTrue(out.size() == 4, "four ways should leave the turnout, but " + out.size() + " do");

			TrackNode behind = farEnd(helper, graph, t.turnout()
				.buildCurveFromPrev(), centre);
			TrackNode ahead = farEnd(helper, graph, t.turnout()
				.buildCurveToNext(), centre);
			TrackNode north = farEnd(helper, graph, t.turnout()
				.curveWith(t.branch()), centre);
			TrackNode southNode = farEnd(helper, graph, t.turnout()
				.curveWith(south), centre);

			TrackEdge fromBehind = graph.getConnectionsFrom(behind)
				.get(node);
			TrackEdge fromNorth = graph.getConnectionsFrom(north)
				.get(node);
			helper.assertTrue(fromBehind.canTravelTo(out.get(ahead)) && fromBehind.canTravelTo(out.get(north))
				&& fromBehind.canTravelTo(out.get(southNode)), "a train from behind should be let onto any of the three");
			helper.assertTrue(fromNorth.canTravelTo(out.get(behind)), "a train off a branch should be let on behind");
			helper.assertTrue(!fromNorth.canTravelTo(out.get(southNode)) && !fromNorth.canTravelTo(out.get(ahead)),
				"a train off a branch should not be let across onto the other branch or the line ahead");

			for (BezierConnection curve : t.turnout()
				.curvesThrough())
				assertEdges(helper, curve);
			helper.succeed();
		});
	}

	/**
	 * A driven train at a turnout with branches every way: straight on is the line, left and right the branches
	 * on either side of it, whichever way it comes in. Coming off a curved branch, Create's own steering measured
	 * the curve's bend into the choice, so straight on took the branch on the far side and nothing took the line.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aDrivenTrainCanTakeEveryWayThroughATurnout(GameTestHelper helper) {
		Turnout t = turnout(helper);
		Level level = helper.getLevel();
		AngledTrackAnchorBlockEntity south = anchor(helper, BRANCH_SOUTH);
		t.turnout()
			.branchTo(south, true, true, arcEnd(helper, TURNOUT, t.turnout()
				.heading(), BRANCH_SOUTH));
		BlockPos backNorth = new BlockPos(2, 2, 8);
		BlockPos backSouth = new BlockPos(2, 2, 24);
		Vec3 back = t.turnout()
			.heading()
			.scale(-1);
		for (BlockPos pos : new BlockPos[] { backNorth, backSouth }) {
			AngledTrackAnchorBlockEntity end = anchor(helper, pos);
			// Its route runs into the turnout, so it is aimed the way a train leaving along it goes, reversed.
			t.turnout()
				.branchTo(end, false, false, arcEnd(helper, TURNOUT, back, pos).scale(-1));
		}

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			Vec3 centre = Vec3.atBottomCenterOf(t.turnout()
				.getBlockPos());
			TrackNodeLocation at = new TrackNodeLocation(centre).in(level);
			TrackGraph graph = Create.RAILWAYS.sided(level)
				.getGraph(level, at);
			helper.assertTrue(graph != null && graph.locateNode(at) != null, "the turnout should be in the graph");
			TrackNode node = graph.locateNode(at);
			helper.assertTrue(graph.getConnectionsFrom(node)
				.size() == 6, "six ways should leave the turnout");

			TrackNode behind = farEnd(helper, graph, t.turnout()
				.buildCurveFromPrev(), centre);
			TrackNode ahead = farEnd(helper, graph, t.turnout()
				.buildCurveToNext(), centre);
			TrackNode north = farEnd(helper, graph, t.turnout()
				.curveWith(t.branch()), centre);
			TrackNode southEnd = farEnd(helper, graph, t.turnout()
				.curveWith(south), centre);
			TrackNode behindNorth = farEnd(helper, graph, t.turnout()
				.curveWith(anchorAt(helper, backNorth, false)), centre);
			TrackNode behindSouth = farEnd(helper, graph, t.turnout()
				.curveWith(anchorAt(helper, backSouth, false)), centre);

			// A train may run either way along its own length, and Create asks a point moving backwards at the
			// other end of its edge. Left and right are then seen from the end that leads going forwards, as
			// Create sees them, so they swap; straight on is the line either way.
			for (boolean forwards : new boolean[] { true, false }) {
				String way = forwards ? " (running forwards)" : " (running backwards)";
				// Coming off the branch to the north, going west: left is south.
				assertSteers(helper, graph, north, node, forwards, SteerDirection.NONE, behind,
					"off a branch, straight on" + way);
				assertSteers(helper, graph, north, node, forwards, SteerDirection.LEFT, forwards ? behindSouth
					: behindNorth, "off a branch, left" + way);
				assertSteers(helper, graph, north, node, forwards, SteerDirection.RIGHT, forwards ? behindNorth
					: behindSouth, "off a branch, right" + way);
				// Coming along the line from behind, going east: left is north.
				assertSteers(helper, graph, behind, node, forwards, SteerDirection.NONE, ahead,
					"along the line, straight on" + way);
				assertSteers(helper, graph, behind, node, forwards, SteerDirection.LEFT, forwards ? north : southEnd,
					"along the line, left" + way);
				assertSteers(helper, graph, behind, node, forwards, SteerDirection.RIGHT, forwards ? southEnd : north,
					"along the line, right" + way);
			}
			helper.succeed();
		});
	}

	/**
	 * Straight on is the straight track, whatever the anchor holds it as. Which way out is the anchor's line and
	 * which a branch follows the order they were drawn in: here the line ahead was drawn round a curve and the
	 * straight track after it, as a branch. A driven train still goes straight on along the straight track.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void straightOnIsTheStraightTrackEvenWhenItIsABranch(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] line = route(helper, EAST, BEHIND, TURNOUT);
		Level level = helper.getLevel();
		AngledTrackAnchorBlockEntity curved = anchor(helper, BRANCH);
		line[1].linkTo(curved, null, arcEnd(helper, TURNOUT, line[1].heading(), BRANCH));
		AngledTrackAnchorBlockEntity straight = anchor(helper, AHEAD);
		line[1].branchTo(straight, true, true, line[1].heading());
		BlockPos backSouth = new BlockPos(2, 2, 24);
		AngledTrackAnchorBlockEntity behindBranch = anchor(helper, backSouth);
		line[1].branchTo(behindBranch, false, false, arcEnd(helper, TURNOUT, line[1].heading()
			.scale(-1), backSouth).scale(-1));
		helper.assertTrue(Objects.equals(line[1].getNext(), curved.getBlockPos()) && Objects.equals(onlyBranch(line[1],
			true), straight.getBlockPos()), "setup: the line ahead should be the curve, and the straight track a branch");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			Vec3 centre = Vec3.atBottomCenterOf(line[1].getBlockPos());
			TrackNodeLocation at = new TrackNodeLocation(centre).in(level);
			TrackGraph graph = Create.RAILWAYS.sided(level)
				.getGraph(level, at);
			TrackNode node = graph.locateNode(at);
			TrackNode behind = farEnd(helper, graph, line[1].buildCurveFromPrev(), centre);
			TrackNode curveEnd = farEnd(helper, graph, line[1].buildCurveToNext(), centre);
			TrackNode straightEnd = farEnd(helper, graph, line[1].curveWith(straight), centre);
			TrackNode branchEnd = farEnd(helper, graph, line[1].curveWith(behindBranch), centre);

			for (boolean forwards : new boolean[] { true, false }) {
				String way = forwards ? " (running forwards)" : " (running backwards)";
				assertSteers(helper, graph, behind, node, forwards, SteerDirection.NONE, straightEnd,
					"along the line, straight on" + way);
				assertSteers(helper, graph, branchEnd, node, forwards, SteerDirection.NONE, straightEnd,
					"off a branch, straight on" + way);
				assertSteers(helper, graph, straightEnd, node, forwards, SteerDirection.NONE, behind,
					"back along the straight track, straight on" + way);
				// Going east, left is north, where the curve goes.
				assertSteers(helper, graph, behind, node, forwards, SteerDirection.LEFT, forwards ? curveEnd
					: straightEnd, "along the line, left" + way);
			}
			helper.succeed();
		});
	}

	/**
	 * Left and right at a turnout stay where they are with a way out of some other track there too, listed first
	 * and leaving across the turnout's heading and a little back: Create's own track sharing the node can have
	 * one. Which side the turnout's ways out are on is not that one's to say.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aWayOutAcrossTheTurnoutDoesNotTurnItsSidesRound(GameTestHelper helper) {
		Turnout t = turnout(helper);
		Level level = helper.getLevel();

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			Vec3 centre = Vec3.atBottomCenterOf(t.turnout()
				.getBlockPos());
			TrackNodeLocation at = new TrackNodeLocation(centre).in(level);
			TrackGraph graph = Create.RAILWAYS.sided(level)
				.getGraph(level, at);
			TrackNode node = graph.locateNode(at);
			TrackNode behind = farEnd(helper, graph, t.turnout()
				.buildCurveFromPrev(), centre);
			TrackNode north = farEnd(helper, graph, t.turnout()
				.curveWith(t.branch()), centre);

			// Going east, south of the turnout and an eighth back, half a block since nodes sit on the half-block
			// grid: only ever offered here, never in the graph.
			TrackNode across = new TrackNode(new TrackNodeLocation(centre.add(-0.5, 0, 4)).in(level), 0,
				new Vec3(0, 1, 0));
			List<Entry<TrackNode, TrackEdge>> targets = new ArrayList<>();
			targets.add(Map.entry(across, new TrackEdge(node, across, null, TrackMaterial.ANDESITE)));
			for (Entry<TrackNode, TrackEdge> target : graph.getConnectionsFrom(node)
				.entrySet())
				if (target.getKey() != behind)
					targets.add(target);
			helper.assertTrue(targets.size() == 3, "setup: the line ahead, the branch and the one across should be"
				+ " offered, but " + targets.size() + " are");

			Entry<TrackNode, TrackEdge> left = TurnoutSteering.choose(node, SteerDirection.LEFT, new Vec3(0, 1, 0),
				true, targets);
			helper.assertTrue(left != null && left.getKey() == north, "going east, left should still be the branch to"
				+ " the north, but is " + (left == null ? "nothing" : left.getKey()
					.getLocation()
					.getLocation()));
			helper.succeed();
		});
	}

	/**
	 * Checks which way a train driven {@code direction} goes on arriving at {@code at} from {@code from}, set up
	 * and asked the way {@code TravellingPoint#travel} does it: moving forwards the point is on the edge from
	 * {@code from} to {@code at}, which is its second node; moving backwards it is on the edge the other way, and
	 * {@code at} is its first. The ways out are those Create would offer, any the train can cross onto.
	 */
	private static void assertSteers(GameTestHelper helper, TrackGraph graph, TrackNode from, TrackNode at,
		boolean forwards, SteerDirection direction, TrackNode expected, String what) {
		TrackEdge arriving = graph.getConnectionsFrom(from)
			.get(at);
		TrackEdge backwards = graph.getConnectionsFrom(at)
			.get(from);
		helper.assertTrue(arriving != null && backwards != null, what + ": the train should have an edge to arrive on");
		List<Entry<TrackNode, TrackEdge>> targets = new ArrayList<>();
		for (Entry<TrackNode, TrackEdge> target : graph.getConnectionsFrom(at)
			.entrySet()) {
			if (target.getKey() == from)
				continue;
			boolean crossable = forwards ? arriving.canTravelTo(target.getValue())
				: graph.getConnectionsFrom(target.getKey())
					.get(at)
					.canTravelTo(backwards);
			if (crossable)
				targets.add(target);
		}
		TravellingPoint point = forwards ? new TravellingPoint(from, at, arriving, arriving.getLength(), false)
			: new TravellingPoint(at, from, backwards, 0, false);
		Entry<TrackNode, TrackEdge> chosen = point.steer(direction, new Vec3(0, 1, 0))
			.apply(graph, Pair.of(forwards, targets));
		helper.assertTrue(chosen.getKey() == expected, what + " should go to " + expected.getLocation()
			.getLocation() + ", but goes to " + chosen.getKey()
				.getLocation()
				.getLocation() + " (of " + targets.size() + " ways)");
	}

	/**
	 * With the line beyond a three-way turnout gone, the first branch that runs on from it becomes the line, and
	 * the other stays a branch beside it.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void breakingTheLineBeyondAThreeWayTurnoutKeepsTheOtherBranch(GameTestHelper helper) {
		Turnout t = turnout(helper);
		AngledTrackAnchorBlockEntity south = anchor(helper, BRANCH_SOUTH);
		t.turnout()
			.branchTo(south, true, true, arcEnd(helper, TURNOUT, t.turnout()
				.heading(), BRANCH_SOUTH));

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			helper.setBlock(AHEAD, Blocks.AIR);
			helper.assertTrue(Objects.equals(t.turnout()
				.getNext(), t.branch()
					.getBlockPos()) && Objects.equals(onlyBranch(t.turnout(), true), south.getBlockPos()),
				"the first branch should be the line now, and the other still a branch");

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				GraphCleanlinessTests.assertClean(helper, "after breaking the line beyond a three-way turnout", 5);
				for (BezierConnection curve : t.turnout()
					.curvesThrough())
					assertEdges(helper, curve);
				helper.succeed();
			});
		});
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

		helper.assertTrue(Objects.equals(onlyBranch(line[1], true), lone.getBlockPos()) && Objects.equals(lone.getNext(),
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

	/**
	 * No curve is drawn between two anchors a curve already joins. Linking them would take the link they have
	 * away: a branch from a turnout to the anchor on its line beside it would leave the turnout with no line
	 * there, and a branch to its own branch would replace the curve it has.
	 */
	@GameTest(template = EMPTY)
	public static void noCurveIsDrawnBetweenTwoAnchorsAlreadyJoined(GameTestHelper helper) {
		turnout(helper);
		Level level = helper.getLevel();
		List<String> drawn = new ArrayList<>();
		for (BlockPos[] pair : new BlockPos[][] { { TURNOUT, AHEAD }, { TURNOUT, BRANCH }, { TURNOUT, BEHIND },
			{ AHEAD, TURNOUT }, { BRANCH, TURNOUT }, { BEHIND, TURNOUT } }) {
			CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(pair[0]), helper.absolutePos(pair[1]),
				Direction.UP, 0);
			if (!"already_linked".equals(plan.error()))
				drawn.add(pair[0].toShortString() + " to " + pair[1].toShortString() + ": " + (plan.valid() ? "valid"
					: plan.error()));
		}
		helper.assertTrue(drawn.isEmpty(), "each curve between anchors already joined should be refused as such, but "
			+ drawn);
		helper.succeed();
	}

	// ------------------------------------------------------ points and mending

	/**
	 * A point put on a branch, for a signal to stand on: the turnout's branch is the new anchor now, and the
	 * rest of the branch runs on from it.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aPointCanBePutOnABranch(GameTestHelper helper) {
		Turnout t = turnout(helper);

		// New anchors are indexed a tick after they are placed, and finding a curve asks the index.
		helper.runAfterDelay(1, () -> {
			AngledTrackAnchorBlockEntity point = putPointOn(helper, t.turnout()
				.curveWith(t.branch()), t.branch());
			helper.assertTrue(Objects.equals(onlyBranch(t.turnout(), true), point.getBlockPos()) && Objects.equals(point.getPrev(), t.turnout()
					.getBlockPos()), "the point should be the turnout's branch now");
			assertChain(helper, point, relativeOf(helper, point), BRANCH);
			assertChain(helper, t.turnout(), BEHIND, TURNOUT, AHEAD);

			helper.runAfterDelay(GRAPH_TICKS, () -> {
				assertEdges(helper, t.turnout()
					.curveWith(point));
				assertConsistent(helper, point);
				GraphCleanlinessTests.assertClean(helper, "with a point on a branch", 7);
				helper.succeed();
			});
		});
	}

	/**
	 * On a three-way turnout the side is full, so the point has to take the old branch's place rather than be
	 * added beside it.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aPointCanBePutOnABranchOfAThreeWayTurnout(GameTestHelper helper) {
		Turnout t = turnout(helper);
		AngledTrackAnchorBlockEntity south = anchor(helper, BRANCH_SOUTH);
		t.turnout()
			.branchTo(south, true, true, arcEnd(helper, TURNOUT, t.turnout()
				.heading(), BRANCH_SOUTH));

		// New anchors are indexed a tick after they are placed, and finding a curve asks the index.
		helper.runAfterDelay(1, () -> {
			AngledTrackAnchorBlockEntity point = putPointOn(helper, t.turnout()
				.curveWith(t.branch()), t.branch());
			helper.assertTrue(t.turnout()
				.getBranches(true)
				.equals(List.of(point.getBlockPos(), south.getBlockPos())), "the point should have taken the old"
					+ " branch's place, beside the other, but the branches are " + t.turnout()
						.getBranches(true));
			assertChain(helper, point, relativeOf(helper, point), BRANCH);

			helper.runAfterDelay(GRAPH_TICKS, () -> {
				for (BezierConnection curve : t.turnout()
					.curvesThrough())
					assertEdges(helper, curve);
				assertConsistent(helper, point);
				helper.succeed();
			});
		});
	}

	/** The same for a branch that runs into the turnout, whose curve its own first anchor holds. */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aPointCanBePutOnABranchThatRunsIn(GameTestHelper helper) {
		Turnout t = turnout(helper);
		t.branch()
			.reverseRoute();

		// New anchors are indexed a tick after they are placed, and finding a curve asks the index.
		helper.runAfterDelay(1, () -> {
			AngledTrackAnchorBlockEntity point = putPointOn(helper, t.branch()
				.curveWith(t.turnout()), t.turnout());
			helper.assertTrue(Objects.equals(onlyBranch(t.turnout(), true), point.getBlockPos()) && Objects.equals(point.getNext(), t.turnout()
					.getBlockPos()), "the point should be the turnout's branch now, running into it");
			assertChain(helper, t.branch(), BRANCH, relativeOf(helper, point));

			helper.runAfterDelay(GRAPH_TICKS, () -> {
				assertEdges(helper, t.turnout()
					.curveWith(point));
				assertConsistent(helper, t.branch());
				helper.succeed();
			});
		});
	}

	/**
	 * A point put on a branch that runs in takes that branch's place among the turnout's branches, not the end
	 * of the list: the order is which of them becomes the line if the line beyond goes.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aPointOnABranchThatRunsInKeepsItsPlace(GameTestHelper helper) {
		Turnout t = turnout(helper);
		t.branch()
			.reverseRoute();
		AngledTrackAnchorBlockEntity south = anchor(helper, BRANCH_SOUTH);
		t.turnout()
			.branchTo(south, true, true, arcEnd(helper, TURNOUT, t.turnout()
				.heading(), BRANCH_SOUTH));
		helper.assertTrue(t.turnout()
			.getBranches(true)
			.equals(List.of(t.branch()
				.getBlockPos(), south.getBlockPos())), "setup: the branch that runs in should be first");

		// New anchors are indexed a tick after they are placed, and finding a curve asks the index.
		helper.runAfterDelay(1, () -> {
			AngledTrackAnchorBlockEntity point = putPointOn(helper, t.branch()
				.curveWith(t.turnout()), t.turnout());
			helper.assertTrue(t.turnout()
				.getBranches(true)
				.equals(List.of(point.getBlockPos(), south.getBlockPos())), "the point should have taken the old"
					+ " branch's place, first, but the branches are " + t.turnout()
						.getBranches(true));
			helper.succeed();
		});
	}

	/**
	 * A branch whose ends point different ways, which the wand never builds as one curve, is found by the
	 * command that looks for such links and mended into two arcs, still a branch of its turnout.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aBranchThatDiffersEachWayIsMended(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] line = route(helper, EAST, BEHIND, TURNOUT, AHEAD);
		BlockPos farPos = new BlockPos(30, 2, 8);
		AngledTrackAnchorBlockEntity far = anchor(helper, farPos);
		line[1].branchTo(far, true, true, AngledTrackAnchorBlockEntity.directionFromYaw(CurveConsistencyTests.NORTH));

		// New anchors are indexed a tick after they are placed, and listing links asks the index.
		helper.runAfterDelay(1, () -> {
			CurveRepair.Link link = null;
			for (CurveRepair.Link each : CurveRepair.links(helper.getLevel(), Vec3.atCenterOf(line[1].getBlockPos()),
				4))
				if (each.to()
					.equals(far.getBlockPos()))
					link = each;
			helper.assertTrue(link != null && link.overLimit(),
				"setup: the branch should be listed as differing each way");

			CurveRepair.Outcome outcome = CurveRepair.repair(helper.getLevel(), link);
			helper.assertTrue(outcome.repaired(), "the branch should have been mended, but was " + outcome.error());
			AngledTrackAnchorBlockEntity middle = anchorAt(helper, relativeOf(helper, outcome.junction()), false);
			helper.assertTrue(Objects.equals(onlyBranch(line[1], true), middle.getBlockPos()) && Objects.equals(far.getPrev(),
				middle.getBlockPos()), "the turnout's branch should run through the new anchor to the far end");

			helper.runAfterDelay(GRAPH_TICKS, () -> {
				assertEdges(helper, line[1].curveWith(middle));
				assertConsistent(helper, middle);
				helper.succeed();
			});
		});
	}

	/**
	 * Create's own picking finds a curve by the block entity that holds it, which is how a signal is put
	 * straight onto a curve: a branch is held like any other.
	 */
	@GameTest(template = EMPTY)
	public static void aBranchIsOfferedToCreatesCurvePicking(GameTestHelper helper) {
		Turnout t = turnout(helper);
		// The end with the lower position holds it.
		AngledTrackAnchorBlockEntity holder = t.turnout()
			.getBlockPos()
			.compareTo(t.branch()
				.getBlockPos()) < 0 ? t.turnout() : t.branch();
		AngledTrackAnchorBlockEntity other = holder == t.turnout() ? t.branch() : t.turnout();
		BezierConnection held = holder.getConnections()
			.get(other.getBlockPos());
		helper.assertTrue(held != null, "the branch should be among its holder's connections");
		helper.assertTrue(CurvePlanner.deviation(held, t.turnout()
			.curveWith(t.branch())) < 1.0E-6, "and be the curve that is drawn");
		helper.succeed();
	}

	// ------------------------------------------------- beside Create's track

	/** A piece of Create's level track along x, west of a route that is joined onto its east end. */
	private static final BlockPos CREATE_TRACK = new BlockPos(10, 2, 16);
	private static final BlockPos DOCKED = new BlockPos(11, 2, 16);
	private static final BlockPos DOCKED_AHEAD = new BlockPos(27, 2, 16);

	private static AngledTrackAnchorBlockEntity[] besideCreateTrack(GameTestHelper helper) {
		helper.setBlock(CREATE_TRACK, AllBlocks.TRACK.get()
			.defaultBlockState()
			.setValue(TrackBlock.SHAPE, TrackShape.XO));
		return route(helper, EAST, DOCKED, DOCKED_AHEAD);
	}

	/**
	 * The side of an end joined onto Create's track is the track's, not free: a curve out of it would run back
	 * over the track, and give the end a curve on both sides, which moves its node off the one it shares with
	 * the track. It is a place for a branch instead.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 40)
	public static void theSideOfAnEndJoinedOntoCreateTrackIsNotFree(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] line = besideCreateTrack(helper);
		Level level = helper.getLevel();

		helper.assertTrue(line[0].isDockedOnTrack(), "the end beside the track should count as joined onto it");
		helper.assertTrue(!line[1].isDockedOnTrack(), "the far end, with nothing beside it, should not");
		helper.assertTrue(!line[0].hasFreeSide(), "the side the track is on should not be free");
		helper.assertTrue(CurvePlanner.canStartFrom(level, helper.absolutePos(DOCKED)),
			"the end should still be a place to start a branch from");

		// Aimed behind, which is where a curve out of the free side used to go.
		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(DOCKED), helper.absolutePos(
			new BlockPos(2, 2, 10)), Direction.UP, 0);
		helper.assertTrue(plan.valid() && plan.start()
			.branch() == CurvePlanner.Branch.BEHIND, "aimed behind, it should be a branch beside the track, but was "
				+ plan.error() + " / " + plan.start());
		helper.succeed();
	}

	/**
	 * A branch beside Create's track leaves the node the end shares with the track, and the end keeps it: the
	 * track, the end's span and the branch all meet there, and the track is still part of the line.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aBranchBesideCreateTrackKeepsTheEndOnTheTrack(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] line = besideCreateTrack(helper);
		Level level = helper.getLevel();
		Vec3 shared = line[0].entryNode();

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(DOCKED), helper.absolutePos(
			new BlockPos(2, 2, 10)), Direction.UP, 0);
		helper.assertTrue(plan.valid(), "setup: the branch should be possible, but was " + plan.error());
		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the branch should have been built");
		helper.assertTrue(Objects.equals(onlyBranch(line[0], false), helper.absolutePos(new BlockPos(2, 2, 10))),
			"the branch should be behind the end");
		helper.assertTrue(line[0].entryNode()
			.equals(shared) && line[0].isDockedOnTrack(), "the end should keep the node it shares with the track");
		BezierConnection branch = line[0].curveWith(anchorAt(helper, new BlockPos(2, 2, 10), false));
		helper.assertTrue(branch != null && branch.starts.getFirst()
			.equals(shared), "the branch should leave that node");
		helper.assertTrue(CurvePlanner.deviation(plan.curve(), branch) < 1.0E-6, "what was built should be the curve"
			+ " the ghost showed");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertEdges(helper, branch);
			assertOneGraph(helper, "the track and the branch", Vec3.atBottomCenterOf(helper.absolutePos(CREATE_TRACK))
				.add(-0.5, 0, 0), branch.starts.getSecond());
			assertOneGraph(helper, "the track and the line", Vec3.atBottomCenterOf(helper.absolutePos(CREATE_TRACK))
				.add(-0.5, 0, 0), line[1].exitNode());
			TrackNodeLocation at = new TrackNodeLocation(shared).in(level);
			TrackGraph graph = Create.RAILWAYS.sided(level)
				.getGraph(level, at);
			helper.assertTrue(graph != null && graph.locateNode(at) != null && graph.getConnectionsFrom(graph.locateNode(
				at))
				.size() == 3, "the track, the span and the branch should meet at the shared node");
			GraphCleanlinessTests.assertClean(helper, "with a branch beside Create's track", -1);
			helper.succeed();
		});
	}

	/** A branch on the side of the curve leaves the end's other boundary, and the end stays on the track. */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aBranchAheadOfAnEndOnCreateTrackLeavesItOnTheTrack(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] line = besideCreateTrack(helper);
		Level level = helper.getLevel();
		Vec3 shared = line[0].entryNode();
		BlockPos target = new BlockPos(27, 2, 10);

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(DOCKED), helper.absolutePos(target),
			Direction.UP, 0);
		helper.assertTrue(plan.valid() && plan.start()
			.branch() == CurvePlanner.Branch.AHEAD, "aimed ahead, it should be a branch beside the line, but was " + plan
				.error());
		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the branch should have been built");
		helper.assertTrue(line[0].entryNode()
			.equals(shared) && line[0].isDockedOnTrack(), "the end should keep the node it shares with the track");
		BezierConnection branch = line[0].curveWith(anchorAt(helper, target, false));
		helper.assertTrue(branch != null && branch.starts.getFirst()
			.equals(line[0].exitNode()), "the branch should leave the end's node ahead, beside the line");
		helper.assertTrue(CurvePlanner.deviation(plan.curve(), branch) < 1.0E-6, "what was built should be the curve"
			+ " the ghost showed");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertEdges(helper, branch);
			assertEdges(helper, line[0].buildCurveToNext());
			assertOneGraph(helper, "the track and the branch", Vec3.atBottomCenterOf(helper.absolutePos(CREATE_TRACK))
				.add(-0.5, 0, 0), branch.starts.getSecond());
			GraphCleanlinessTests.assertClean(helper, "with a branch ahead of an end on Create's track", -1);
			helper.succeed();
		});
	}

	// --------------------------------------- Create's track that branches or crosses

	private static final BlockPos CROSSING = new BlockPos(10, 2, 16);

	/**
	 * A crossing has a line each way, and a curve drawn out of one joins the end of the line it runs nearer
	 * to: east out of the line along x, north out of the line along z.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aCurveLeavesTheLineOfACrossingItIsDrawnAlong(GameTestHelper helper) {
		helper.setBlock(CROSSING, AllBlocks.TRACK.get()
			.defaultBlockState()
			.setValue(TrackBlock.SHAPE, TrackShape.CR_O));
		Level level = helper.getLevel();
		helper.assertTrue(CurvePlanner.canStartFrom(level, helper.absolutePos(CROSSING)),
			"a crossing should be somewhere a curve can start");

		CurvePlanner.Plan north = CurvePlanner.plan(level, helper.absolutePos(CROSSING), helper.absolutePos(
			new BlockPos(12, 2, 0)), Direction.UP, 0);
		helper.assertTrue(north.valid() && north.start()
			.anchorPos()
			.equals(helper.absolutePos(new BlockPos(10, 2, 15))), "aimed north, the curve should leave the north end"
				+ " of the line along z, but was " + north.error() + " at " + north.start());

		BlockPos target = new BlockPos(26, 2, 12);
		CurvePlanner.Plan east = CurvePlanner.plan(level, helper.absolutePos(CROSSING), helper.absolutePos(target),
			Direction.UP, 0);
		helper.assertTrue(east.valid() && east.start()
			.docked() && east.start()
				.anchorPos()
				.equals(helper.absolutePos(new BlockPos(11, 2, 16))), "aimed east, the curve should leave the east end of"
					+ " the line along x, but was " + east.error() + " at " + east.start());
		helper.assertTrue(CurvePlanner.apply(level, east) == null, "the curve should have been built");
		AngledTrackAnchorBlockEntity end = anchorAt(helper, new BlockPos(11, 2, 16), false);
		helper.assertTrue(end.isDockedOnTrack(), "the end should be joined onto the crossing");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			BezierConnection curve = end.buildCurveToNext();
			assertEdges(helper, curve);
			assertOneGraph(helper, "the crossing's line and the curve", Vec3.atBottomCenterOf(helper.absolutePos(
				CROSSING))
				.add(-0.5, 0, 0), curve.starts.getSecond());
			GraphCleanlinessTests.assertClean(helper, "with a curve off a crossing", -1);
			helper.succeed();
		});
	}

	/** Through the wand: the crossing is picked by the first click, and the second builds. */
	@GameTest(template = EMPTY)
	public static void theWandDrawsOutOfACrossing(GameTestHelper helper) {
		helper.setBlock(CROSSING, AllBlocks.TRACK.get()
			.defaultBlockState()
			.setValue(TrackBlock.SHAPE, TrackShape.CR_D));
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		helper.assertTrue(CurveConsistencyTests.click(helper, player, wand, CROSSING)
			.consumesAction(), "the first click should pick the crossing");
		InteractionResult built = CurveConsistencyTests.click(helper, player, wand, new BlockPos(24, 2, 30));
		helper.assertTrue(built.consumesAction(), "the second click should have built a curve, but gave " + built);
		helper.succeed();
	}

	/**
	 * Create's own junction, a curve of Create's leaving the end of a straight piece of its track. The wand
	 * joins the same end, and the node there is one with three ways out: the straight track, Create's curve
	 * and this mod's.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aCurveCanLeaveTheEndOfCreatesOwnJunction(GameTestHelper helper) {
		Level level = helper.getLevel();
		BlockPos straight = new BlockPos(10, 2, 16);
		BlockPos turned = new BlockPos(16, 2, 10);
		helper.setBlock(straight, AllBlocks.TRACK.get()
			.defaultBlockState()
			.setValue(TrackBlock.SHAPE, TrackShape.XO)
			.setValue(TrackBlock.HAS_BE, true));
		helper.setBlock(turned, AllBlocks.TRACK.get()
			.defaultBlockState()
			.setValue(TrackBlock.SHAPE, TrackShape.ZO)
			.setValue(TrackBlock.HAS_BE, true));

		// A quarter circle from the east end of the straight piece round to the south end of the other, which
		// is how Create's own wand would lay it.
		Vec3 junction = Vec3.atBottomCenterOf(helper.absolutePos(straight))
			.add(0.5, 0, 0);
		Vec3 far = Vec3.atBottomCenterOf(helper.absolutePos(turned))
			.add(0, 0, 0.5);
		Vec3 up = new Vec3(0, 1, 0);
		BezierConnection createCurve = new BezierConnection(Couple.create(helper.absolutePos(straight), helper
			.absolutePos(turned)), Couple.create(junction, far), Couple.create(new Vec3(1, 0, 0), new Vec3(0, 0, 1)),
			Couple.create(up, up), true, false, TrackMaterial.ANDESITE);
		((TrackBlockEntity) helper.getBlockEntity(straight)).addConnection(createCurve);
		((TrackBlockEntity) helper.getBlockEntity(turned)).addConnection(createCurve.secondary());
		TrackPropagator.onRailAdded(level, helper.absolutePos(straight), helper.getBlockState(straight));

		helper.assertTrue(CurvePlanner.canStartFrom(level, helper.absolutePos(straight)),
			"the straight piece of Create's junction should be somewhere a curve can start");
		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(straight), helper.absolutePos(
			new BlockPos(30, 2, 22)), Direction.UP, 0);
		helper.assertTrue(plan.valid() && plan.start()
			.anchorPos()
			.equals(helper.absolutePos(new BlockPos(11, 2, 16))), "the curve should leave the end Create's curve leaves,"
				+ " but was " + plan.error() + " at " + plan.start());
		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the curve should have been built");
		AngledTrackAnchorBlockEntity end = anchorAt(helper, new BlockPos(11, 2, 16), false);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			BezierConnection curve = end.buildCurveToNext();
			assertEdges(helper, curve);
			TrackNodeLocation at = new TrackNodeLocation(junction).in(level);
			TrackGraph graph = Create.RAILWAYS.sided(level)
				.getGraph(level, at);
			helper.assertTrue(graph != null && graph.locateNode(at) != null && graph.getConnectionsFrom(graph.locateNode(
				at))
				.size() == 3, "the straight track, Create's curve and the anchor should meet at the end of the straight");
			assertOneGraph(helper, "Create's curve and this mod's", far, curve.starts.getSecond());
			helper.succeed();
		});
	}

	// ------------------------------------------------------------- review fixes

	/**
	 * An anchor left with branches and no line, its line gone from both sides past branches that run into it,
	 * is an end once a curve is drawn from it, with its node on the boundary of its block. The ghost has to be
	 * drawn from that node, the one the curve is built from, not the centre.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aCurveFromAnAnchorWithOnlyBranchesIsBuiltAsShown(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] line = route(helper, EAST, BEHIND, TURNOUT, AHEAD);
		AngledTrackAnchorBlockEntity branch = anchor(helper, BRANCH);
		// Its route runs into the turnout, so it cannot take the line's place.
		line[1].branchTo(branch, true, false, arcEnd(helper, TURNOUT, line[1].heading(), BRANCH).scale(-1));
		line[0].unlink();
		line[2].unlink();
		helper.assertTrue(line[1].getPrev() == null && line[1].getNext() == null && line[1].hasBranch(),
			"setup: the turnout should be left with its branch and no line");

		Level level = helper.getLevel();
		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(TURNOUT), helper.absolutePos(
			BRANCH_SOUTH), Direction.UP, 0);
		helper.assertTrue(plan.valid(), "a curve on from the anchor should be possible, but was " + plan.error());
		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the curve should have been built");
		BezierConnection built = line[1].buildCurveToNext();
		helper.assertTrue(built != null && CurvePlanner.deviation(plan.curve(), built) < 1.0E-6,
			"what was built should be the curve the ghost showed, but they are " + (built == null ? "-"
				: CurvePlanner.deviation(plan.curve(), built)) + " apart");
		helper.succeed();
	}

	/**
	 * Two anchors are joined by one curve. Linking an anchor to one of its own branches makes the branch the
	 * line, and leaves it nothing else: not a branch as well, and not linked back the other way.
	 */
	@GameTest(template = EMPTY)
	public static void linkingToABranchLeavesOneLinkBetweenTheTwo(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] line = route(helper, EAST, BEHIND, TURNOUT);
		AngledTrackAnchorBlockEntity branch = anchor(helper, BRANCH);
		line[1].branchTo(branch, true, false, arcEnd(helper, TURNOUT, line[1].heading(), BRANCH).scale(-1));
		helper.assertTrue(Objects.equals(branch.getNext(), line[1].getBlockPos()), "setup: the branch should run in");

		line[1].linkTo(branch, null, arcEnd(helper, TURNOUT, line[1].heading(), BRANCH));
		helper.assertTrue(Objects.equals(line[1].getNext(), branch.getBlockPos()) && !line[1].hasBranch(),
			"the anchor should be the turnout's next and no longer a branch");
		helper.assertTrue(Objects.equals(branch.getPrev(), line[1].getBlockPos()) && branch.getNext() == null,
			"the anchor should hold the turnout as its prev only, but its next is " + branch.getNext());
		helper.assertTrue(line[1].neighbours()
			.size() == 2 && line[1].curvesThrough()
				.size() == 2, "the turnout should have one curve each side and nothing twice");
		helper.succeed();
	}

	/**
	 * A branch is refused, and nothing is left behind, when its side has filled up between the plan and the
	 * build: the anchor the plan put at the far end is taken out again.
	 */
	@GameTest(template = EMPTY)
	public static void aBranchWithNoRoomLeftIsRefusedAndUndone(GameTestHelper helper) {
		Turnout t = turnout(helper);
		Level level = helper.getLevel();
		BlockPos target = new BlockPos(30, 2, 28);
		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(TURNOUT), helper.absolutePos(target),
			Direction.UP, 0);
		helper.assertTrue(plan.valid(), "setup: a second branch should be possible, but was " + plan.error());

		AngledTrackAnchorBlockEntity south = anchor(helper, BRANCH_SOUTH);
		helper.assertTrue(t.turnout()
			.branchTo(south, true, true, arcEnd(helper, TURNOUT, t.turnout()
				.heading(), BRANCH_SOUTH)), "setup: the side should take a second branch");
		helper.assertTrue(!t.turnout()
			.branchTo(anchor(helper, new BlockPos(40, 2, 28)), true, true, null), "a third branch should be refused");

		helper.assertTrue("branch_taken".equals(CurvePlanner.apply(level, plan)), "building the plan made before"
			+ " the side filled up should be refused");
		helper.assertTrue(!helper.getBlockState(target)
			.is(CTNRegistry.ANGLED_TRACK_ANCHOR.get()), "the anchor the plan placed should be taken out again");
		helper.assertTrue(t.turnout()
			.getBranches(true)
			.equals(List.of(t.branch()
				.getBlockPos(), south.getBlockPos())), "the branches should be as they were");
		helper.succeed();
	}

	/**
	 * A branch that differs each way is mended into two arcs, behind the turnout whichever way its route runs,
	 * and ahead of it where its route runs in, which is the one way the curve arrives at the turnout's front.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aBranchIsMendedOnEitherSideWhicheverWayItRuns(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] line = route(helper, EAST, BEHIND, TURNOUT, AHEAD);
		Vec3 north = AngledTrackAnchorBlockEntity.directionFromYaw(CurveConsistencyTests.NORTH);
		// One runs on from the turnout, west and round to arrive pointing north; the other comes in from the
		// south-west, leaving pointing north and arriving at the turnout going east.
		BlockPos onPos = new BlockPos(2, 2, 8);
		BlockPos inPos = new BlockPos(2, 2, 26);
		AngledTrackAnchorBlockEntity on = anchor(helper, onPos);
		AngledTrackAnchorBlockEntity in = anchor(helper, inPos);
		// The one that runs in first, so that mending it shows whether it keeps its place.
		line[1].branchTo(in, false, false, north);
		line[1].branchTo(on, false, true, north);
		// Ahead, one that leaves pointing south and arrives at the turnout going west.
		BlockPos aheadInPos = new BlockPos(30, 2, 8);
		AngledTrackAnchorBlockEntity aheadIn = anchor(helper, aheadInPos);
		line[1].branchTo(aheadIn, true, false, north.scale(-1));

		helper.runAfterDelay(1, () -> {
			for (AngledTrackAnchorBlockEntity far : new AngledTrackAnchorBlockEntity[] { on, in, aheadIn }) {
				CurveRepair.Link link = null;
				for (CurveRepair.Link each : CurveRepair.links(helper.getLevel(), Vec3.atCenterOf(far.getBlockPos()),
					24))
					if (each.from() == far && each.to()
						.equals(line[1].getBlockPos())
						|| each.from() == line[1] && each.to()
							.equals(far.getBlockPos()))
						link = each;
				helper.assertTrue(link != null && link.overLimit(), "setup: the branch to " + far.getBlockPos()
					.toShortString() + " should be listed as differing each way");
				CurveRepair.Outcome outcome = CurveRepair.repair(helper.getLevel(), link);
				helper.assertTrue(outcome.repaired(), "the branch to " + far.getBlockPos()
					.toShortString() + " should have been mended, but was " + outcome.error());
			}
			helper.assertTrue(line[1].getBranches(false)
				.size() == 2 && !line[1].getBranches(false)
					.contains(onPos) && !line[1].getBranches(false)
						.contains(inPos), "both branches behind should run through their new anchors now");
			helper.assertTrue(line[1].getBranches(false)
				.get(0)
				.equals(in.getNext()), "the new anchor on the branch that runs in should have its place, first, but the"
					+ " branches are " + line[1].getBranches(false));
			helper.assertTrue(line[1].getBranches(true)
				.size() == 1 && !line[1].getBranches(true)
					.contains(aheadInPos), "the branch ahead should run through its new anchor now");

			helper.runAfterDelay(GRAPH_TICKS, () -> {
				for (BezierConnection curve : line[1].curvesThrough())
					assertEdges(helper, curve);
				for (BlockPos middle : line[1].neighbours())
					for (BezierConnection curve : anchorAt(helper, relativeOf(helper, middle), false).curvesThrough())
						assertEdges(helper, curve);
				helper.succeed();
			});
		});
	}

	// --------------------------------------------------------------- helpers

	/** The one branch on a side of {@code anchor}, or null if that side has none or more than one. */
	@Nullable
	private static BlockPos onlyBranch(AngledTrackAnchorBlockEntity anchor, boolean ahead) {
		List<BlockPos> branches = anchor.getBranches(ahead);
		return branches.size() == 1 ? branches.get(0) : null;
	}

	/** Puts a point half way along {@code curve} the way the wand does, and returns the anchor it placed. */
	private static AngledTrackAnchorBlockEntity putPointOn(GameTestHelper helper, BezierConnection curve,
		AngledTrackAnchorBlockEntity farEnd) {
		helper.assertTrue(curve != null, "setup: there should be a curve to put a point on");
		CurvePlanner.SplitTarget target = CurvePlanner.findCurveNear(helper.getLevel(), curve.getPosition(0.5), 2.0);
		helper.assertTrue(target != null && target.valid(), "a point should be possible half way along, but was "
			+ (target == null ? "nothing found" : target.error()));
		helper.assertTrue(Objects.equals(target.far(), farEnd.getBlockPos()), "the curve found should be the one asked"
			+ " about");
		helper.assertTrue(CurvePlanner.split(helper.getLevel(), target), "the point should have been put");
		return anchorAt(helper, relativeOf(helper, target.anchorPos()), false);
	}

	private static BlockPos relativeOf(GameTestHelper helper, BlockPos absolute) {
		return CurveConsistencyTests.relative(helper, absolute);
	}

	private static BlockPos relativeOf(GameTestHelper helper, AngledTrackAnchorBlockEntity anchor) {
		return relativeOf(helper, anchor.getBlockPos());
	}

	/** Checks that the graph has nodes at both places, and that they are in the same graph. */
	private static void assertOneGraph(GameTestHelper helper, String what, Vec3 a, Vec3 b) {
		Level level = helper.getLevel();
		TrackNodeLocation first = new TrackNodeLocation(a).in(level);
		TrackNodeLocation second = new TrackNodeLocation(b).in(level);
		TrackGraph graph = Create.RAILWAYS.sided(level)
			.getGraph(level, first);
		helper.assertTrue(graph != null && graph.locateNode(first) != null, what + ": the first should be a node");
		helper.assertTrue(graph.locateNode(second) != null, what + ": the second should be a node of the same graph");
	}

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

	/** Checks that the graph has an edge each way along {@code drawn}, lying on it: see assertCurveInGraph. */
	private static void assertEdges(GameTestHelper helper, BezierConnection drawn) {
		CurveConsistencyTests.assertCurveInGraph(helper, drawn, drawn.bePositions.getFirst()
			.toShortString() + " -> "
			+ drawn.bePositions.getSecond()
				.toShortString());
	}
}
