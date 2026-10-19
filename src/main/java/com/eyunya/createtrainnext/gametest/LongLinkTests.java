package com.eyunya.createtrainnext.gametest;

import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.EAST;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.WEST;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.anchor;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurveFillerBlockEntity;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks that a link between anchors a long way apart stays in the track graph while the anchor at its
 * far end is out of the loaded chunks.
 *
 * <p>A curve is built from the anchors at both of its ends, so an anchor whose neighbour is not loaded
 * reports fewer curves than it has. The graph is rebuilt from what an anchor reports, and doing that
 * with a neighbour out of reach took the link to it out of the graph and put nothing back: the anchors,
 * the curve and the floor were all still there and nothing could drive along it. Links of Create's
 * longest, 32 blocks, are never far enough for the neighbour to be out of reach when the anchor is
 * loaded. A link of a hundred blocks is, and a grade too gentle to climb in 32 is what asks for one.
 *
 * <p>The far anchor here is six chunks from the others, which are held loaded, and its own chunk is
 * held until the test lets it go, so the one thing that differs between a link that holds and one that
 * does not is whether that chunk is there when the rebuild runs.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class LongLinkTests {

	private static final String EMPTY = "empty";

	/**
	 * Each test lays its anchors along a row of its own, far to the side of every other test's, so that no
	 * chunk of one is held by another and a command run in one cannot reach another's anchors. Test
	 * structures are laid out side by side, so the rows have to be far enough apart that it does not matter
	 * which of them a test was put beside.
	 */
	private static final int ROW_KEEPS = 6000;
	private static final int ROW_PUT_OFF = 8000;
	private static final int ROW_FLOOR = 10000;
	private static final int ROW_COMMAND = 12000;
	private static final int ROW_RUN_ON = 14000;
	private static final int ROW_PENDING = 16000;

	/** How long a chunk takes to go once nothing holds it, with a margin. */
	private static final long UNLOADED_BY = 60;

	/**
	 * Four anchors in a line east: three close together and the fourth a hundred blocks along and four
	 * higher, which is 40 permille, the kind of link a gentle grade needs. The last two climb at that
	 * grade, and every chunk is held loaded until the test lets one go.
	 */
	private record Line(int row, AngledTrackAnchorBlockEntity[] anchors) {

		BlockPos[] positions() {
			return positionsIn(row);
		}

		BlockPos middle() {
			return positions()[2];
		}

		BlockPos far() {
			return positions()[3];
		}

		AngledTrackAnchorBlockEntity middleAnchor() {
			return anchors[2];
		}

		AngledTrackAnchorBlockEntity farAnchor() {
			return anchors[3];
		}
	}

	private static BlockPos[] positionsIn(int row) {
		return new BlockPos[] { new BlockPos(0, 2, row), new BlockPos(8, 2, row), new BlockPos(16, 2, row),
			new BlockPos(116, 6, row) };
	}

	private static Line line(GameTestHelper helper, int row) {
		BlockPos[] positions = positionsIn(row);
		AngledTrackAnchorBlockEntity[] anchors = new AngledTrackAnchorBlockEntity[positions.length];
		for (BlockPos position : positions)
			hold(helper, position, true);
		for (int i = 0; i < positions.length; i++)
			anchors[i] = anchor(helper, positions[i]);

		Vec3 east = AngledTrackAnchorBlockEntity.directionFromYaw(EAST);
		Vec3 climbing = AngledTrackAnchorBlockEntity.tangentOf(east, 0.04);
		anchors[0].linkTo(anchors[1], east, east);
		anchors[1].linkTo(anchors[2], null, east);
		anchors[2].linkTo(anchors[3], climbing, climbing);
		return new Line(row, anchors);
	}

	private static void hold(GameTestHelper helper, BlockPos relative, boolean on) {
		BlockPos absolute = helper.absolutePos(relative);
		helper.getLevel()
			.setChunkForced(absolute.getX() >> 4, absolute.getZ() >> 4, on);
	}

	private static void releaseAll(GameTestHelper helper, Line line) {
		for (BlockPos each : line.positions())
			hold(helper, each, false);
	}

	/** Whether the graph has an edge between the nodes at two places, in both directions. */
	private static boolean joined(Level level, Vec3 from, Vec3 to) {
		TrackNodeLocation fromLocation = new TrackNodeLocation(from).in(level);
		TrackNodeLocation toLocation = new TrackNodeLocation(to).in(level);
		TrackGraph graph = Create.RAILWAYS.sided(level)
			.getGraph(level, fromLocation);
		TrackNode fromNode = graph == null ? null : graph.locateNode(fromLocation);
		TrackNode toNode = graph == null ? null : graph.locateNode(toLocation);
		return fromNode != null && toNode != null && graph.getConnectionsFrom(fromNode)
			.containsKey(toNode)
			&& graph.getConnectionsFrom(toNode)
				.containsKey(fromNode);
	}

	/** The node at a place, or null. */
	private static TrackNode nodeAt(Level level, Vec3 at) {
		TrackNodeLocation location = new TrackNodeLocation(at).in(level);
		TrackGraph graph = Create.RAILWAYS.sided(level)
			.getGraph(level, location);
		return graph == null ? null : graph.locateNode(location);
	}

	/** Takes the edge between two places out of the graph, as a rebuild that could not see it did. */
	private static void loseLink(Level level, Vec3 from, Vec3 to) {
		TrackNodeLocation fromLocation = new TrackNodeLocation(from).in(level);
		TrackGraph graph = Create.RAILWAYS.sided(level)
			.getGraph(level, fromLocation);
		graph.disconnectNodes(graph.locateNode(fromLocation), graph.locateNode(new TrackNodeLocation(to).in(level)));
		Create.RAILWAYS.markTracksDirty();
	}

	private static void assertGone(GameTestHelper helper, Line line) {
		helper.assertTrue(!helper.getLevel()
			.isLoaded(helper.absolutePos(line.far())),
			"setup: the far end's chunk should be out of memory by now, but it is loaded");
	}

	// ----------------------------------------------------------------- tests

	/**
	 * A rebuild asked for at an anchor whose far neighbour is not loaded leaves the link to it in the
	 * graph. It used to take it out: the anchor reported the curve behind it and not the curve ahead, and
	 * the graph was built again from that.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 200)
	public static void aRebuildWithTheFarEndOutOfReachKeepsTheLink(GameTestHelper helper) {
		Line line = line(helper, ROW_KEEPS);
		Vec3 from = line.middleAnchor()
			.exitNode();
		Vec3 to = line.farAnchor()
			.entryNode();

		helper.runAfterDelay(15, () -> {
			helper.assertTrue(joined(helper.getLevel(), from, to), "setup: the long link should be in the graph by now");
			hold(helper, line.far(), false);
		});
		helper.runAfterDelay(15 + UNLOADED_BY, () -> {
			assertGone(helper, line);
			helper.assertTrue(line.middleAnchor()
				.hasUnloadedNeighbour(), "the anchor should know its neighbour is out of reach");
			line.middleAnchor()
				.requestRebuild(1);
		});
		helper.runAfterDelay(15 + UNLOADED_BY + 10, () -> {
			helper.assertTrue(joined(helper.getLevel(), from, to),
				"the link to an anchor that is out of reach should still be in the graph after the rebuild was asked for");
			hold(helper, line.far(), true);
		});
		helper.runAfterDelay(15 + UNLOADED_BY + 60, () -> {
			helper.assertTrue(joined(helper.getLevel(), from, to), "and once it is back it should still be");
			releaseAll(helper, line);
			helper.succeed();
		});
	}

	/**
	 * A link that has already been lost is put back when the far end comes back, by the rebuild that was
	 * put off until then.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 250)
	public static void aRebuildPutOffIsDoneOnceTheFarEndIsLoaded(GameTestHelper helper) {
		Line line = line(helper, ROW_PUT_OFF);
		Vec3 from = line.middleAnchor()
			.exitNode();
		Vec3 to = line.farAnchor()
			.entryNode();

		helper.runAfterDelay(15, () -> {
			helper.assertTrue(joined(helper.getLevel(), from, to), "setup: the long link should be in the graph by now");
			loseLink(helper.getLevel(), from, to);
			hold(helper, line.far(), false);
		});
		helper.runAfterDelay(15 + UNLOADED_BY, () -> {
			assertGone(helper, line);
			line.middleAnchor()
				.requestRebuild(1);
		});
		helper.runAfterDelay(15 + UNLOADED_BY + 10, () -> {
			helper.assertTrue(!joined(helper.getLevel(), from, to),
				"with the far end out of reach there is nothing to rebuild it from");
			hold(helper, line.far(), true);
		});
		helper.runAfterDelay(15 + UNLOADED_BY + 100, () -> {
			helper.assertTrue(joined(helper.getLevel(), from, to),
				"the rebuild that was put off should have run once the far end was loaded");
			releaseAll(helper, line);
			helper.succeed();
		});
	}

	/**
	 * The floor under a link stays while its far end is out of reach, and is kept alive there. The anchor
	 * renews it every few seconds from the curve; with the curve unbuildable it took the floor away, and
	 * a guard that only left it alone would have let it time out instead.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 200)
	public static void theFloorUnderALongLinkStaysWhileItsFarEndIsOutOfReach(GameTestHelper helper) {
		Line line = line(helper, ROW_FLOOR);
		BlockPos underfoot = line.middle()
			.offset(2, 0, 0);

		helper.runAfterDelay(15, () -> {
			helper.assertTrue(helper.getBlockState(underfoot)
				.is(CTNRegistry.CURVE_FILLER.get()), "setup: the link should have a floor along it");
			hold(helper, line.far(), false);
		});
		helper.runAfterDelay(15 + UNLOADED_BY, () -> {
			assertGone(helper, line);

			// Checked in the tick it is done in: taking the floor away loads the chunks it crosses, which
			// brings the far end back and has the next refresh put the floor down again.
			line.middleAnchor()
				.updateOccupancy();
			helper.assertTrue(helper.getBlockState(underfoot)
				.is(CTNRegistry.CURVE_FILLER.get()), "the floor should still be there with the far end out of reach");

			// Two of its three lives gone, then a refresh, then two more: it only survives if it was renewed.
			wear(helper, underfoot, 2);
			line.middleAnchor()
				.updateOccupancy();
			wear(helper, underfoot, 2);
			helper.assertTrue(helper.getBlockState(underfoot)
				.is(CTNRegistry.CURVE_FILLER.get()), "the floor should have been renewed, but it ran out");
			releaseAll(helper, line);
			helper.succeed();
		});
	}

	/**
	 * A route run on from an anchor whose old neighbour is out of reach is in the graph at once. The anchor
	 * itself has to wait for that neighbour, since building it now would lose the long link, but the new
	 * link and the anchor ahead of it have nothing out of reach, and used to wait with it for a chunk that
	 * can be a long way off.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 250)
	public static void aRouteRunOnFromAnAnchorWithItsOldNeighbourOutOfReachIsInTheGraph(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos[] at = positionsIn(ROW_RUN_ON);
		for (BlockPos each : at)
			hold(helper, each, true);
		AngledTrackAnchorBlockEntity ahead = anchor(helper, at[1]);
		AngledTrackAnchorBlockEntity end = anchor(helper, at[2]);
		AngledTrackAnchorBlockEntity far = anchor(helper, at[3]);

		Vec3 west = AngledTrackAnchorBlockEntity.directionFromYaw(WEST);
		Vec3 descending = AngledTrackAnchorBlockEntity.tangentOf(west, -0.04);
		far.linkTo(end, descending, descending);

		helper.runAfterDelay(15, () -> {
			helper.assertTrue(joined(level, far.exitNode(), end.entryNode()),
				"setup: the long link should be in the graph by now");
			hold(helper, at[3], false);
		});
		helper.runAfterDelay(15 + UNLOADED_BY, () -> {
			helper.assertTrue(!level.isLoaded(helper.absolutePos(at[3])),
				"setup: the far end's chunk should be out of memory by now, but it is loaded");
			end.linkTo(ahead, west, west);
		});
		helper.runAfterDelay(15 + UNLOADED_BY + 30, () -> {
			helper.assertTrue(joined(level, end.exitNode(), ahead.entryNode()),
				"the new link should be in the graph without waiting for the far end");
			hold(helper, at[3], true);
		});
		helper.runAfterDelay(15 + UNLOADED_BY + 90, () -> {
			helper.assertTrue(joined(level, far.exitNode(), end.entryNode()),
				"once the far end is back the long link should be there");
			helper.assertTrue(joined(level, end.exitNode(), ahead.entryNode()), "and so should the new one");
			for (BlockPos each : at)
				hold(helper, each, false);
			helper.succeed();
		});
	}

	/**
	 * Uses up some of a floor block's lives, as the random ticks that clean up after a curve do. A block that
	 * has run out is gone, and is left gone.
	 */
	private static void wear(GameTestHelper helper, BlockPos relative, int lives) {
		for (int i = 0; i < lives; i++)
			if (helper.getLevel()
				.getBlockEntity(helper.absolutePos(relative)) instanceof CurveFillerBlockEntity filler)
				filler.expire();
	}

	/**
	 * {@code /createtrainnext rebuild} puts back a link that is missing from the graph, and does not
	 * touch the ones that are not: the nodes of a working route are the same ones afterwards.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 120)
	public static void theRebuildCommandMendsOnlyWhatIsMissing(GameTestHelper helper) {
		Line line = line(helper, ROW_COMMAND);
		ServerLevel level = helper.getLevel();
		Vec3 from = line.middleAnchor()
			.exitNode();
		Vec3 to = line.farAnchor()
			.entryNode();

		// Rebuilding at an anchor takes out the nodes of the anchors beside it and of the ones beside those:
		// Create walks on from every node it has just removed. So the node to watch is the first one of the
		// route, three anchors from the one being rebuilt.
		Vec3 behind = line.anchors()[0].entryNode();

		TrackNode[] before = new TrackNode[1];
		helper.runAfterDelay(15, () -> {
			helper.assertTrue(joined(level, from, to), "setup: the long link should be in the graph by now");
			before[0] = nodeAt(level, behind);
			helper.assertTrue(before[0] != null, "setup: the first anchor should have a node");

			run(helper, line, "rebuild");
		});
		helper.runAfterDelay(15 + 15, () -> {
			helper.assertTrue(nodeAt(level, behind) == before[0],
				"with every link in the graph nothing should be rebuilt, but the first node is a new one");
			loseLink(level, from, to);
			helper.assertTrue(!joined(level, from, to), "setup: the link should be missing now");

			run(helper, line, "rebuild");
		});
		helper.runAfterDelay(15 + 15 + 15, () -> {
			helper.assertTrue(joined(level, from, to), "the link that was missing should have been put back");
			helper.assertTrue(nodeAt(level, behind) == before[0],
				"a link three anchors away was working, and its node should not have been touched");
			releaseAll(helper, line);
			helper.succeed();
		});
	}

	/**
	 * {@code /createtrainnext rebuild} leaves alone a link whose rebuild is already waiting: it is missing
	 * from the graph only because the rebuild has not run, and asking for another takes the nodes beside it
	 * out a second time. It reports the link as not judged instead.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void theRebuildCommandLeavesAlonePendingLinks(GameTestHelper helper) {
		Line line = line(helper, ROW_PENDING);
		ServerLevel level = helper.getLevel();
		Vec3 from = line.middleAnchor()
			.exitNode();
		Vec3 to = line.farAnchor()
			.entryNode();

		helper.runAfterDelay(15, () -> {
			helper.assertTrue(joined(level, from, to), "setup: the long link should be in the graph by now");
			loseLink(level, from, to);
			line.middleAnchor()
				.requestRebuild(40);
			helper.assertTrue(line.middleAnchor()
				.hasRebuildPending(), "setup: the rebuild should be waiting");
			helper.assertTrue(run(helper, line, "rebuild") == 0, "a link with its rebuild waiting should not be queued again");
		});
		helper.runAfterDelay(15 + 60, () -> {
			helper.assertTrue(joined(level, from, to), "the rebuild that was waiting should have put the link back");
			releaseAll(helper, line);
			helper.succeed();
		});
	}

	/** Runs one of the mod's commands as an operator standing at the middle anchor. */
	private static int run(GameTestHelper helper, Line line, String subcommand) {
		ServerLevel level = helper.getLevel();
		var source = level.getServer()
			.createCommandSourceStack()
			.withLevel(level)
			.withPosition(Vec3.atCenterOf(helper.absolutePos(line.middle())));
		try {
			return level.getServer()
				.getCommands()
				.getDispatcher()
				.execute(CreateTrainNext.ID + " " + subcommand, source);
		} catch (CommandSyntaxException e) {
			throw new IllegalStateException(e);
		}
	}
}
