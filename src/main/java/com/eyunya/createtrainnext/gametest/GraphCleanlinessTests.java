package com.eyunya.createtrainnext.gametest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AnchorAxis;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlock;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.eyunya.createtrainnext.track.GraphPruner;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackShape;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks that the graph holds nothing that no track block reports.
 *
 * <p>Create builds the graph from what the blocks say when it walks them, and a node nothing reports
 * any more is never walked to, so nothing ever removes it. It sits in the graph as an island for as
 * long as the world is kept, and shows in the F3 view as a coloured post where an anchor used to be.
 * Making a route, extending one, unlinking and breaking an anchor all used to leave some behind.
 *
 * <p>The check is the same each time: take every node of the graph that lies in the test's box, and
 * every location any track block still standing reports, and nothing may be in the first that is not
 * in the second. How many nodes there are is checked too, so that a pass which took out too much
 * would not look like a clean one. Those counts follow from how anchors place their nodes: the two
 * ends of its span for an anchor with no curve through it or at the end of a route, and one at its
 * centre for an anchor in the middle.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class GraphCleanlinessTests {

	private static final String EMPTY = "empty";

	private static final float EAST = 270.0F;
	private static final float NORTH = 180.0F;
	private static final float WEST = 90.0F;

	/** How long Create takes to build a graph out of anchors that have just been linked. */
	private static final int BUILD_TICKS = 8;

	/** And to settle after something is taken away or changed. */
	private static final int SETTLE_TICKS = 12;

	private static BlockPos at(int x) {
		return new BlockPos(x, 2, 16);
	}

	private static BlockPos[] along(int... xs) {
		BlockPos[] positions = new BlockPos[xs.length];
		for (int i = 0; i < xs.length; i++)
			positions[i] = at(xs[i]);
		return positions;
	}

	/** A route through the given positions in order, every anchor pointed along {@code yaw}. */
	private static AngledTrackAnchorBlockEntity[] route(GameTestHelper helper, float yaw, BlockPos... positions) {
		AngledTrackAnchorBlockEntity[] anchors = new AngledTrackAnchorBlockEntity[positions.length];
		for (int i = 0; i < positions.length; i++) {
			helper.setBlock(positions[i], CTNRegistry.ANGLED_TRACK_ANCHOR.get()
				.defaultBlockState()
				.setValue(AngledTrackAnchorBlock.AXIS, AnchorAxis.Z_ORTHO));
			anchors[i] = (AngledTrackAnchorBlockEntity) helper.getBlockEntity(positions[i]);
		}

		Vec3 heading = AngledTrackAnchorBlockEntity.directionFromYaw(yaw);
		for (int i = 0; i + 1 < anchors.length; i++)
			anchors[i].linkTo(anchors[i + 1], i == 0 ? heading : null, heading);
		return anchors;
	}

	/** The route most of these start from: four anchors in a line, two ends and two in the middle. */
	private static AngledTrackAnchorBlockEntity[] fourInALine(GameTestHelper helper) {
		return route(helper, EAST, along(6, 14, 22, 30));
	}

	// ------------------------------------------------------- making a route

	/** A route made in one go, with nothing yet to take out or change. */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aRouteLeavesNothingInTheGraphThatNoAnchorReports(GameTestHelper helper) {
		fourInALine(helper);

		helper.runAfterDelay(BUILD_TICKS, () -> {
			// Two at each end of the route, one at the centre of each anchor between them.
			assertClean(helper, "after building a route of four", 6);
			helper.succeed();
		});
	}

	/**
	 * Carrying a route on out of its last anchor, which stops being an end: the two nodes it had at
	 * its boundary are not reported by anything once it has a curve on each side.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void extendingARouteLeavesNothingBehind(GameTestHelper helper) {
		route(helper, EAST, along(6, 14));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			Level level = helper.getLevel();
			CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(at(14)), helper.absolutePos(at(30)),
				Direction.UP, 0);
			helper.assertTrue(plan.valid(), "setup: the route should be possible to carry on, but: " + plan.error());
			helper.assertTrue(CurvePlanner.apply(level, plan) == null, "setup: the curve should have been built");

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// 6 and 30 are the ends now, and 14 is in the middle.
				assertClean(helper, "after carrying a route of two on to a third anchor", 5);
				helper.succeed();
			});
		});
	}

	/** Two routes laid away from each other, one turned round to meet the other. */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void joiningTwoRoutesLeavesNothingBehind(GameTestHelper helper) {
		route(helper, EAST, along(4, 12, 20));
		route(helper, WEST, along(46, 38, 30));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			Level level = helper.getLevel();
			CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(at(20)), helper.absolutePos(at(30)),
				Direction.UP, 0);
			helper.assertTrue(plan.valid() && plan.reverseEnd(),
				"setup: the join should turn the far route round, but: " + plan.error());
			helper.assertTrue(CurvePlanner.apply(level, plan) == null, "setup: the join should have been built");

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// One route of six: the ends have two each, the four in the middle one each.
				assertClean(helper, "after joining two tails", 8);
				helper.succeed();
			});
		});
	}

	/**
	 * A curve built as two arcs, which is three links and an anchor the player did not place. Each of
	 * them changes what its neighbours report.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void aCurveBuiltAsTwoArcsLeavesNothingBehind(GameTestHelper helper) {
		BlockPos start = new BlockPos(10, 2, 26);
		BlockPos end = new BlockPos(30, 2, 16);
		route(helper, EAST, new BlockPos(2, 2, 26), start);
		route(helper, NORTH, end, new BlockPos(30, 2, 8));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			Level level = helper.getLevel();
			CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(start), helper.absolutePos(end),
				Direction.UP, 0);
			helper.assertTrue(plan.valid() && plan.junction() != null,
				"setup: the join should be built as two arcs, but: " + plan.error());
			helper.assertTrue(CurvePlanner.apply(level, plan) == null, "setup: the join should have been built");

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// Five anchors in one route: two ends, and the three in the middle.
				assertClean(helper, "after joining two anchors that point different ways", 7);
				helper.succeed();
			});
		});
	}

	// ----------------------------------------------------- taking one apart

	/** An anchor in the middle is broken, which is how a route is most often taken apart. */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void breakingAnInteriorAnchorLeavesNothingBehind(GameTestHelper helper) {
		fourInALine(helper);

		helper.runAfterDelay(BUILD_TICKS, () -> {
			helper.setBlock(at(14), Blocks.AIR);
			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// 6 is alone, and 22 and 30 are a route of two: two nodes each.
				assertClean(helper, "after breaking the second anchor of four", 6);
				helper.succeed();
			});
		});
	}

	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void breakingAnEndAnchorLeavesNothingBehind(GameTestHelper helper) {
		fourInALine(helper);

		helper.runAfterDelay(BUILD_TICKS, () -> {
			helper.setBlock(at(6), Blocks.AIR);
			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// 14 is the first of a route of three now, so two at each end and one between.
				assertClean(helper, "after breaking the first anchor of four", 5);
				helper.succeed();
			});
		});
	}

	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void breakingTwoNeighbouringAnchorsLeavesNothingBehind(GameTestHelper helper) {
		fourInALine(helper);

		helper.runAfterDelay(BUILD_TICKS, () -> {
			helper.setBlock(at(14), Blocks.AIR);
			helper.setBlock(at(22), Blocks.AIR);
			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// What is left is the two ends, alone.
				assertClean(helper, "after breaking the two in the middle", 4);
				helper.succeed();
			});
		});
	}

	/** Break all of them, and the graph is left with nothing in this box at all. */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void breakingEveryAnchorEmptiesTheGraph(GameTestHelper helper) {
		fourInALine(helper);

		helper.runAfterDelay(BUILD_TICKS, () -> {
			for (int x : new int[] { 6, 14, 22, 30 })
				helper.setBlock(at(x), Blocks.AIR);
			helper.runAfterDelay(SETTLE_TICKS, () -> {
				assertClean(helper, "after breaking all four", 0);
				helper.succeed();
			});
		});
	}

	/** The way the wand takes an anchor's curves apart without breaking the block. */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void unlinkingAnAnchorLeavesNothingBehind(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = fourInALine(helper);

		helper.runAfterDelay(BUILD_TICKS, () -> {
			anchors[1].unlink();
			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// 6 and 14 are alone, 22 and 30 are a route of two, and every one of them has two.
				assertClean(helper, "after unlinking the second anchor of four", 8);
				helper.succeed();
			});
		});
	}

	// ------------------------------------------------------------ leaving alone

	/** Turning a route round moves no node, so the graph is left exactly as it was. */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void turningARouteRoundLeavesTheGraphAlone(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = fourInALine(helper);

		helper.runAfterDelay(BUILD_TICKS, () -> {
			helper.assertTrue(anchors[0].reverseRoute() == 4, "setup: the route should have been turned round");
			helper.runAfterDelay(SETTLE_TICKS, () -> {
				assertClean(helper, "after turning the route round", 6);
				helper.succeed();
			});
		});
	}

	/**
	 * Anchors are docked to Create's track all the time, and the two share the node where they meet.
	 * Taking the anchor away has to leave the track a graph of its own with a node at each end, and
	 * nothing of the anchor's.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void breakingAnAnchorBesideCreateTrackLeavesTheTrackItsNodes(GameTestHelper helper) {
		helper.setBlock(at(10), createTrackAlongX());
		route(helper, EAST, along(11, 20));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			assertNodeAt(helper, "before anything is broken, the west end of the track", at(10), 0.0);

			helper.setBlock(at(11), Blocks.AIR);
			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// The track's two ends, and the anchor left on its own at 20.
				assertClean(helper, "after breaking the anchor beside the track", 4);
				assertNodeAt(helper, "after breaking the anchor beside it, the west end of the track", at(10), 0.0);
				assertNodeAt(helper, "after breaking the anchor beside it, the east end of the track", at(10), 1.0);
				helper.succeed();
			});
		});
	}

	/**
	 * The same thing without any anchor at all to be reasoned from: Create's track on its own reports
	 * its two ends, and a pass that asked only the anchors would find nobody reporting them.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void pruningLeavesNodesThatOnlyCreateTrackReports(GameTestHelper helper) {
		helper.setBlock(at(10), createTrackAlongX());

		helper.runAfterDelay(BUILD_TICKS, () -> {
			assertNodeAt(helper, "the west end of a lone track", at(10), 0.0);
			assertNodeAt(helper, "the east end of a lone track", at(10), 1.0);

			List<TrackNodeLocation> both = new ArrayList<>();
			for (double along : new double[] { 0.0, 1.0 })
				both.add(new TrackNodeLocation(Vec3.atBottomCenterOf(helper.absolutePos(at(10)))
					.add(along - 0.5, 0, 0)).in(helper.getLevel()));
			GraphPruner.Result result = GraphPruner.prune(helper.getLevel(), both);

			helper.assertTrue(result.examined() == 2 && result.removed() == 0,
				"both ends are reported by the track, so neither should have been removed: " + result);
			assertNodeAt(helper, "after pruning, the west end of a lone track", at(10), 0.0);
			assertNodeAt(helper, "after pruning, the east end of a lone track", at(10), 1.0);
			helper.succeed();
		});
	}

	private static BlockState createTrackAlongX() {
		return AllBlocks.TRACK.get()
			.defaultBlockState()
			.setValue(TrackBlock.SHAPE, TrackShape.XO);
	}

	// -------------------------------------------- an anchor on its own, pointed freely

	/** Where two anchors on a free heading go, fifteen degrees round from east, a line apart. */
	private static final float FREE = 255.0F;

	private static AngledTrackAnchorBlockEntity[] twoOnAFreeHeading(GameTestHelper helper) {
		return route(helper, FREE, at(6), new BlockPos(18, 2, 13));
	}

	/**
	 * An anchor that is not on one of the four axes keeps a single node at its centre, and an anchor
	 * with nothing linked to it has no curve to say so. Create makes the node anyway, when it builds
	 * the graph from the block, and whatever the block does not report is out of reach of the
	 * propagator: breaking the anchor used to leave that node in the graph for good.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void breakingAnAnchorWithAFreeHeadingLeavesNothingBehind(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = twoOnAFreeHeading(helper);

		helper.runAfterDelay(BUILD_TICKS, () -> {
			assertClean(helper, "before anything is broken, a route of two on a free heading", 2);

			anchors[0].unlink();
			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// Neither has a curve, and each keeps its node.
				assertClean(helper, "after unlinking the two", 2);

				helper.setBlock(at(6), Blocks.AIR);
				helper.setBlock(new BlockPos(18, 2, 13), Blocks.AIR);
				helper.runAfterDelay(SETTLE_TICKS, () -> {
					assertClean(helper, "after breaking both", 0);
					helper.succeed();
				});
			});
		});
	}

	/**
	 * Each time the graph is rebuilt around such an anchor, the node it makes at its centre is the one
	 * it made before. It used to be added again each time, in a graph of its own, as the old one was
	 * not among the nodes the rebuild knew to take out first.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void rebuildingAroundAnAnchorWithAFreeHeadingDoesNotMultiplyItsNode(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = twoOnAFreeHeading(helper);

		helper.runAfterDelay(BUILD_TICKS, () -> {
			anchors[0].unlink();
			helper.runAfterDelay(SETTLE_TICKS, () -> {
				BlockPos pos = anchors[0].getBlockPos();
				for (int i = 0; i < 3; i++)
					TrackPropagator.onRailAdded(helper.getLevel(), pos, helper.getLevel()
						.getBlockState(pos));

				assertClean(helper, "after rebuilding around a lone anchor three more times", 2);
				helper.succeed();
			});
		});
	}

	// ----------------------------------------------------------- the command

	/**
	 * Nodes that were already left behind are taken out by the command, which is how a world that has
	 * some is cleaned.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 40)
	public static void thePruneCommandRemovesANodeNothingReports(GameTestHelper helper) {
		Level level = helper.getLevel();
		Vec3 where = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(40, 2, 4)));

		TrackGraph graph = new TrackGraph();
		Create.RAILWAYS.putGraphWithDefaultGroup(graph);
		graph.createNodeIfAbsent(new DiscoveredLocation(level.dimension(), where));
		TrackNodeLocation location = new TrackNodeLocation(where).in(level);
		helper.assertTrue(graph.locateNode(location) != null, "setup: the stray node should be in the graph");

		// A small radius: the tests run side by side, and a command that reached into the next one's
		// box would clean up after it.
		List<String> said = run(helper, "createtrainnext prune 4", where);

		helper.assertTrue(graph.locateNode(location) == null, "the node nothing reports should have been removed; said "
			+ said);
		helper.assertTrue(!Create.RAILWAYS.trackNetworks.containsKey(graph.id),
			"the graph it was alone in should have gone with it");
		helper.succeed();
	}

	/** And it leaves alone what track is there. */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void thePruneCommandLeavesTrackThatIsThere(GameTestHelper helper) {
		fourInALine(helper);

		helper.runAfterDelay(BUILD_TICKS, () -> {
			int before = nodesHere(helper);
			helper.assertTrue(before > 0, "setup: there should be a graph here by now");

			// Wide enough for the whole route, and not so wide as to reach the next test's box.
			run(helper, "createtrainnext prune 16", Vec3.atCenterOf(helper.absolutePos(at(18))));

			helper.assertTrue(nodesHere(helper) == before, "pruning should not touch track that is there: " + before
				+ " nodes became " + nodesHere(helper));
			helper.succeed();
		});
	}

	// --------------------------------------------------------------- helpers

	/** Runs a command as an operator standing at {@code where}, and returns what it said. */
	private static List<String> run(GameTestHelper helper, String command, Vec3 where) {
		ServerLevel level = helper.getLevel();
		List<String> said = new ArrayList<>();
		CommandSource sink = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				said.add(message.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		CommandSourceStack source = new CommandSourceStack(sink, where, Vec2.ZERO, level, 2, "test",
			Component.literal("test"), level.getServer(), null);
		level.getServer()
			.getCommands()
			.performPrefixedCommand(source, command);
		return said;
	}

	private static boolean inBox(GameTestHelper helper, Vec3 v) {
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		return v.x >= origin.getX() && v.x < origin.getX() + 48 && v.z >= origin.getZ() && v.z < origin.getZ() + 32
			&& v.y >= origin.getY() && v.y < origin.getY() + 8;
	}

	private static int nodesHere(GameTestHelper helper) {
		int total = 0;
		for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values())
			for (TrackNodeLocation location : graph.getNodes())
				if (inBox(helper, location.getLocation()))
					total++;
		return total;
	}

	/**
	 * Checks that the graph has a node {@code along} blocks from the west face of a block, at the
	 * middle of its depth.
	 */
	private static void assertNodeAt(GameTestHelper helper, String what, BlockPos block, double along) {
		Vec3 where = Vec3.atBottomCenterOf(helper.absolutePos(block))
			.add(along - 0.5, 0, 0);
		TrackNodeLocation location = new TrackNodeLocation(where).in(helper.getLevel());
		boolean found = false;
		for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values())
			found |= graph.locateNode(location) != null;
		helper.assertTrue(found, what + " should be a node of the graph");
	}

	/**
	 * Checks that every node of the graph in this test's box is reported by a track block still
	 * standing, and that there are as many as {@code expectedNodes} — unless that is negative, for
	 * when it is only the first that is being asked.
	 */
	private static void assertClean(GameTestHelper helper, String when, int expectedNodes) {
		Level level = helper.getLevel();
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);

		Set<TrackNodeLocation> reported = new HashSet<>();
		for (int x = 0; x < 48; x++)
			for (int y = 0; y < 8; y++)
				for (int z = 0; z < 32; z++) {
					BlockPos pos = origin.offset(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (state.getBlock() instanceof ITrackBlock track)
						reported.addAll(track.getConnected(level, pos, state, false, null));
				}

		List<String> strays = new ArrayList<>();
		int total = 0;
		for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values())
			for (TrackNodeLocation location : graph.getNodes()) {
				Vec3 v = location.getLocation();
				if (!inBox(helper, v))
					continue;
				total++;
				if (!reported.contains(location))
					strays.add(String.format("(%.1f, %.1f)", v.x - origin.getX(), v.z - origin.getZ()));
			}

		helper.assertTrue(strays.isEmpty(), when + ", the graph holds " + strays.size()
			+ " nodes that no track block reports, at (x, z) " + strays);
		if (expectedNodes >= 0)
			helper.assertTrue(total == expectedNodes, when + ", the graph should hold " + expectedNodes
				+ " nodes but holds " + total);
	}
}
