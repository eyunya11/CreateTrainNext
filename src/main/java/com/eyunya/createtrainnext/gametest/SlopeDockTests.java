package com.eyunya.createtrainnext.gametest;

import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.GRAPH_TICKS;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.anchor;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.assertConsistent;
import static com.eyunya.createtrainnext.gametest.GraphCleanlinessTests.assertClean;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackShape;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks curves docked onto Create's own sloped track, on a real server level: that the anchor at the
 * slope shares the slope's node and keeps it, and that the graph Create makes from the two is one a
 * train can cross either way.
 *
 * <p>The slopes here climb north, {@link TrackShape#AN}. One at {@link #SLOPE} has its top end on the
 * boundary between it and the block north of it, a block up, and its foot on the boundary south of it at
 * its floor: see {@link CreateSlopeTests}. A curve leaving the top climbs north out of it at 45 degrees
 * and levels off; one arriving at the foot comes up from the south and meets it at 45 degrees.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class SlopeDockTests {

	private static final String EMPTY = "empty";

	/** Long enough for anchors that have been placed to have been entered in the index the split reads. */
	private static final int INDEX_TICKS = 8;

	/** And for the graph to settle after something is taken away. */
	private static final int SETTLE_TICKS = 12;

	private static final Vec3 NORTH = new Vec3(0, 0, -1);

	/** A slope climbing north, the anchor docked onto its top, and where the curve from there levels off. */
	private static final BlockPos SLOPE = new BlockPos(16, 1, 20);
	private static final BlockPos TOP_DOCK = new BlockPos(16, 2, 19);
	private static final BlockPos LEVEL_BEYOND = new BlockPos(16, 6, 9);

	/** Another, higher, the anchor docked onto its foot, and where the curve up to it starts, level. */
	private static final BlockPos HIGH_SLOPE = new BlockPos(32, 5, 20);
	private static final BlockPos FOOT_DOCK = new BlockPos(32, 5, 21);
	private static final BlockPos LEVEL_BELOW = new BlockPos(32, 1, 30);

	// ------------------------------------------------------------ the anchor

	/**
	 * An end docked onto the top of a slope has one node, the slope's own, and no span: entering and
	 * leaving it are the same point, the boundary at its floor on the side the slope is. Its free side is
	 * the slope's, so a curve cannot be started there.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void anEndDockedOntoASlopeSharesTheSlopesNode(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity dock = dockedOnTop(helper);
		Vec3 top = slopeTop(helper, SLOPE);

		helper.assertTrue(dock.isSlopeDocked(), "the anchor at the top of the slope should be docked onto it");
		helper.assertTrue(dock.entryNode()
			.distanceToSqr(top) < 1.0E-12
			&& dock.exitNode()
				.distanceToSqr(top) < 1.0E-12,
			"its node should be the slope's top, " + top + ", but enters at " + dock.entryNode() + " and leaves at "
				+ dock.exitNode());
		helper.assertTrue(dock.buildSpan() == null, "a docked end should have no span across its block");
		helper.assertTrue(!dock.hasFreeSide(), "the side without a curve is the slope's, and should not be offered");
		helper.assertTrue(!CurvePlanner.canStartFrom(helper.getLevel(), dock.getBlockPos()),
			"so no curve should be started from the docked anchor");
		BezierConnection curve = dock.buildCurveToNext();
		helper.assertTrue(curve != null && Math.abs(curve.axes.getFirst()
			.normalize()
			.dot(new Vec3(0, 1, -1).normalize()) - 1) < 1.0E-9, "the curve should leave along the slope's axis");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			// The slope's foot, the shared top, and the two ends of the level anchor's span.
			assertClean(helper, "with a curve docked onto the top of a slope", 4);
			assertConsistent(helper, dock);
			helper.succeed();
		});
	}

	/** A train can go up the slope and on along the curve, and back down. */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aTrainCanCrossFromASlopeOntoADockedCurveAndBack(GameTestHelper helper) {
		dockedOnTop(helper);
		Level level = helper.getLevel();
		Vec3 foot = slopeFoot(helper, SLOPE);
		Vec3 top = slopeTop(helper, SLOPE);
		Vec3 beyond = Vec3.atBottomCenterOf(helper.absolutePos(LEVEL_BEYOND))
			.add(0, 0, 0.5);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertCrossing(helper, level, foot, top, beyond, "up the slope and onto the curve");
			assertCrossing(helper, level, beyond, top, foot, "off the curve and down the slope");
			helper.succeed();
		});
	}

	/**
	 * The same at the foot of a slope, where the route arrives: the anchor's node is ahead of it, on the
	 * side the route goes on to.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void anEndDockedOntoTheFootOfASlopeSharesItsNode(GameTestHelper helper) {
		helper.setBlock(HIGH_SLOPE, CreateSlopeTests.slope(TrackShape.AN));
		AngledTrackAnchorBlockEntity start = anchor(helper, LEVEL_BELOW);
		AngledTrackAnchorBlockEntity dock = anchor(helper, FOOT_DOCK);
		start.linkTo(dock, AngledTrackAnchorBlockEntity.tangentOf(NORTH, 0), AngledTrackAnchorBlockEntity.tangentOf(NORTH, 1),
			false, true);
		Level level = helper.getLevel();
		Vec3 foot = slopeFoot(helper, HIGH_SLOPE);
		Vec3 top = slopeTop(helper, HIGH_SLOPE);
		Vec3 below = Vec3.atBottomCenterOf(helper.absolutePos(LEVEL_BELOW))
			.add(0, 0, -0.5);

		helper.assertTrue(dock.isSlopeDocked() && dock.entryNode()
			.distanceToSqr(foot) < 1.0E-12, "the anchor at the foot should share the slope's foot, " + foot + ", but its node is "
				+ dock.entryNode());
		helper.assertTrue(!start.isSlopeDocked(), "the far end was not docked and should not be");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			// The two ends of the level anchor's span, the shared foot, and the slope's top.
			assertClean(helper, "with a curve docked onto the foot of a slope", 4);
			assertConsistent(helper, start);
			assertCrossing(helper, level, below, foot, top, "up the curve and onto the slope");
			assertCrossing(helper, level, top, foot, below, "down the slope and onto the curve");
			helper.succeed();
		});
	}

	/** Turning the route round leaves the node where it is, on the slope. */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void turningADockedRouteRoundLeavesItOnTheSlope(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity dock = dockedOnTop(helper);
		Vec3 top = slopeTop(helper, SLOPE);

		helper.assertTrue(dock.reverseRoute() == 2, "setup: the route of two should have been turned round");
		helper.assertTrue(dock.getPrev() != null && dock.getNext() == null, "setup: the docked anchor should now be the last");
		helper.assertTrue(dock.isSlopeDocked() && dock.entryNode()
			.distanceToSqr(top) < 1.0E-12, "it should still be docked at " + top + ", but its node is " + dock.entryNode());

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertClean(helper, "after turning a docked route round", 4);
			assertConsistent(helper, anchorAt(helper, LEVEL_BEYOND));
			helper.succeed();
		});
	}

	/**
	 * A point put on a docked curve leaves the dock as it was, whichever end of the curve it is at. The
	 * far end of a curve has no link for a moment while the point goes in, which is the case to watch.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void aPointPutOnADockedCurveKeepsTheDock(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity owner = dockedOnTop(helper);
		helper.setBlock(HIGH_SLOPE, CreateSlopeTests.slope(TrackShape.AN));
		AngledTrackAnchorBlockEntity start = anchor(helper, LEVEL_BELOW);
		AngledTrackAnchorBlockEntity far = anchor(helper, FOOT_DOCK);
		start.linkTo(far, AngledTrackAnchorBlockEntity.tangentOf(NORTH, 0), AngledTrackAnchorBlockEntity.tangentOf(NORTH, 1),
			false, true);
		Level level = helper.getLevel();

		helper.runAfterDelay(INDEX_TICKS, () -> {
			split(helper, level, owner.buildCurveToNext(), "the curve the docked anchor owns");
			split(helper, level, start.buildCurveToNext(), "the curve that arrives at the docked anchor");

			helper.assertTrue(owner.isSlopeDocked() && owner.entryNode()
				.distanceToSqr(slopeTop(helper, SLOPE)) < 1.0E-12, "the anchor that owns its curve should still be docked");
			helper.assertTrue(far.isSlopeDocked() && far.entryNode()
				.distanceToSqr(slopeFoot(helper, HIGH_SLOPE)) < 1.0E-12, "the anchor at the far end should still be docked");

			helper.runAfterDelay(GRAPH_TICKS, () -> {
				// Each route has a point at the centre of its new anchor as well now.
				assertClean(helper, "after putting a point on each docked curve", 10);
				assertConsistent(helper, owner);
				assertConsistent(helper, start);
				helper.succeed();
			});
		});
	}

	/**
	 * Taking the curve away takes the dock with it, whichever end of the curve the anchor broken was at
	 * and whichever anchor was unlinked. Nothing is left in the graph that no block reports.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void losingItsCurveUndocksAnEnd(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity owner = dockedOnTop(helper);
		helper.setBlock(HIGH_SLOPE, CreateSlopeTests.slope(TrackShape.AN));
		AngledTrackAnchorBlockEntity start = anchor(helper, LEVEL_BELOW);
		AngledTrackAnchorBlockEntity far = anchor(helper, FOOT_DOCK);
		start.linkTo(far, AngledTrackAnchorBlockEntity.tangentOf(NORTH, 0), AngledTrackAnchorBlockEntity.tangentOf(NORTH, 1),
			false, true);
		HolderLookup.Provider registries = helper.getLevel()
			.registryAccess();

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			// The anchor the docked one leads to, and the one that leads to the other docked one.
			helper.setBlock(LEVEL_BEYOND, Blocks.AIR);
			helper.assertTrue(!owner.isSlopeDocked() && !owner.saveWithFullMetadata(registries)
				.contains("SlopeDock"), "an anchor whose curve went with the anchor ahead of it should not be docked");
			helper.setBlock(LEVEL_BELOW, Blocks.AIR);
			helper.assertTrue(!far.isSlopeDocked() && !far.saveWithFullMetadata(registries)
				.contains("SlopeDock"), "an anchor whose curve went with the anchor behind it should not be docked");

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				// Both slopes' two ends, and a node at the centre of each anchor left at a slope, since each
				// climbs.
				assertClean(helper, "after undocking both", 6);
				helper.succeed();
			});
		});
	}

	/** An anchor unlinked with the wand is not docked any more, nor is it saved as docked. */
	@GameTest(template = EMPTY)
	public static void unlinkingADockedEndUndocksIt(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity dock = dockedOnTop(helper);
		dock.unlink();
		helper.assertTrue(!dock.isSlopeDocked() && !dock.saveWithFullMetadata(helper.getLevel()
			.registryAccess())
			.contains("SlopeDock"), "an unlinked anchor should not be docked any more");
		helper.succeed();
	}

	/**
	 * An end that gets a curve on its other side, which the wand does not offer but a link made by hand
	 * can, is not docked any more: its node goes to its centre, like any anchor's in the middle of a
	 * route. It stays undocked once that curve goes again, since nothing put its node back on the slope
	 * when it was planned.
	 */
	@GameTest(template = EMPTY)
	public static void aDockedEndThatGetsASecondCurveIsDockedNoLonger(GameTestHelper helper) {
		// The route leaves the top dock, so the second curve comes in behind it.
		AngledTrackAnchorBlockEntity leaving = dockedOnTop(helper);
		AngledTrackAnchorBlockEntity behind = anchor(helper, new BlockPos(16, 2, 28));
		behind.linkTo(leaving, null, null);
		assertUndockedAtCentre(helper, leaving, "the dock a route leaves, with a curve behind it as well");
		behind.unlink();
		helper.assertTrue(leaving.getPrev() == null && leaving.getNext() != null, "setup: it should be an end again");
		helper.assertTrue(!leaving.isSlopeDocked(), "it should stay undocked once the second curve has gone");

		// The route arrives at the foot dock, so the second curve goes on ahead of it.
		helper.setBlock(HIGH_SLOPE, CreateSlopeTests.slope(TrackShape.AN));
		AngledTrackAnchorBlockEntity start = anchor(helper, LEVEL_BELOW);
		AngledTrackAnchorBlockEntity arriving = anchor(helper, FOOT_DOCK);
		start.linkTo(arriving, AngledTrackAnchorBlockEntity.tangentOf(NORTH, 0), AngledTrackAnchorBlockEntity.tangentOf(NORTH, 1),
			false, true);
		arriving.linkTo(anchor(helper, new BlockPos(40, 5, 21)), null, null);
		assertUndockedAtCentre(helper, arriving, "the dock a route arrives at, with a curve ahead of it as well");
		helper.succeed();
	}

	private static void assertUndockedAtCentre(GameTestHelper helper, AngledTrackAnchorBlockEntity anchor, String what) {
		Vec3 centre = Vec3.atBottomCenterOf(anchor.getBlockPos());
		helper.assertTrue(!anchor.isSlopeDocked() && anchor.entryNode()
			.distanceToSqr(centre) < 1.0E-12, what + " should be undocked, its node at " + centre + ", but it is "
				+ (anchor.isSlopeDocked() ? "docked" : "at " + anchor.entryNode()));
		helper.assertTrue(!anchor.saveWithFullMetadata(helper.getLevel()
			.registryAccess())
			.contains("SlopeDock"), what + " should not be saved as docked");
	}

	/**
	 * An anchor with no curve that is linked without being docked is not docked, whatever it was set to.
	 * Nothing this mod saves is like that, but an anchor read back from such a tag would otherwise become
	 * docked the moment it became an end, with its node somewhere no plan put it.
	 */
	@GameTest(template = EMPTY)
	public static void anAnchorLinkedWithoutBeingDockedIsNotDocked(GameTestHelper helper) {
		HolderLookup.Provider registries = helper.getLevel()
			.registryAccess();
		AngledTrackAnchorBlockEntity first = anchor(helper, new BlockPos(8, 2, 8));
		AngledTrackAnchorBlockEntity second = anchor(helper, new BlockPos(8, 2, 16));
		for (AngledTrackAnchorBlockEntity each : new AngledTrackAnchorBlockEntity[] { first, second }) {
			CompoundTag tag = each.saveWithFullMetadata(registries);
			tag.putBoolean("SlopeDock", true);
			each.loadWithComponents(tag, registries);
		}

		Vec3 south = NORTH.scale(-1);
		first.linkTo(second, south, south);
		helper.assertTrue(!first.isSlopeDocked(), "the anchor the link was made from should not be docked");
		helper.assertTrue(!second.isSlopeDocked(), "nor the one it was made to");
		helper.succeed();
	}

	/** Being docked is saved with the anchor and sent to the client, and an anchor saved before is not. */
	@GameTest(template = EMPTY)
	public static void aDockIsSavedAndSent(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity dock = dockedOnTop(helper);
		HolderLookup.Provider registries = helper.getLevel()
			.registryAccess();

		CompoundTag saved = dock.saveWithFullMetadata(registries);
		helper.assertTrue(saved.getBoolean("SlopeDock"), "the dock should be saved");
		helper.assertTrue(reload(helper, dock, saved).isSlopeDocked(), "a saved dock should load docked");

		CompoundTag old = saved.copy();
		old.remove("SlopeDock");
		helper.assertTrue(!reload(helper, dock, old).isSlopeDocked(), "an anchor saved before docks should load as it was");

		AngledTrackAnchorBlockEntity seen = new AngledTrackAnchorBlockEntity(dock.getBlockPos(), dock.getBlockState());
		seen.handleUpdateTag(dock.getUpdateTag(registries), registries);
		helper.assertTrue(seen.isSlopeDocked(), "the client should be told the anchor is docked");
		helper.succeed();
	}

	// --------------------------------------------------------------- helpers

	/**
	 * {@link #SLOPE}, an anchor docked onto its top at {@link #TOP_DOCK} and a level one at
	 * {@link #LEVEL_BEYOND}, four higher and ten on, linked by hand: the curve leaves the slope at 45
	 * degrees and arrives level.
	 */
	private static AngledTrackAnchorBlockEntity dockedOnTop(GameTestHelper helper) {
		helper.setBlock(SLOPE, CreateSlopeTests.slope(TrackShape.AN));
		AngledTrackAnchorBlockEntity dock = anchor(helper, TOP_DOCK);
		AngledTrackAnchorBlockEntity beyond = anchor(helper, LEVEL_BEYOND);
		dock.linkTo(beyond, AngledTrackAnchorBlockEntity.tangentOf(NORTH, 1), AngledTrackAnchorBlockEntity.tangentOf(NORTH, 0),
			true, false);
		return dock;
	}

	/** The top end of a slope climbing north at {@code relative}. */
	private static Vec3 slopeTop(GameTestHelper helper, BlockPos relative) {
		return Vec3.atBottomCenterOf(helper.absolutePos(relative))
			.add(0, 1, -0.5);
	}

	/** And its foot. */
	private static Vec3 slopeFoot(GameTestHelper helper, BlockPos relative) {
		return Vec3.atBottomCenterOf(helper.absolutePos(relative))
			.add(0, 0, 0.5);
	}

	/** Puts a point half way along a curve, as the wand does on a sneak click there. */
	private static void split(GameTestHelper helper, Level level, @Nullable BezierConnection curve, String what) {
		helper.assertTrue(curve != null, what + " should be there to put a point on");
		CurvePlanner.SplitTarget target = CurvePlanner.findCurveNear(level, curve.getPosition(0.5), 2);
		helper.assertTrue(target != null && target.valid(),
			"a point should be possible half way along " + what + ", but: " + (target == null ? "none found" : target.error()));
		helper.assertTrue(CurvePlanner.split(level, target), "the point should have gone onto " + what);
	}

	/**
	 * Checks that a train arriving at {@code via} from {@code from} may go on to {@code to}, the way
	 * {@code TrackEdge.canTravelTo} decides it.
	 */
	private static void assertCrossing(GameTestHelper helper, Level level, Vec3 from, Vec3 via, Vec3 to, String what) {
		TrackEdge arriving = edge(level, from, via);
		TrackEdge leaving = edge(level, via, to);
		helper.assertTrue(arriving != null && leaving != null, what + ": both edges should be in the graph");
		double dot = arriving.getDirection(false)
			.dot(leaving.getDirection(true));
		helper.assertTrue(arriving.canTravelTo(leaving),
			what + ": a train should be let across, but the edges meet at a dot product of " + dot);
	}

	@Nullable
	private static TrackEdge edge(Level level, Vec3 from, Vec3 to) {
		TrackNodeLocation start = new TrackNodeLocation(from).in(level);
		TrackNodeLocation end = new TrackNodeLocation(to).in(level);
		TrackGraph graph = Create.RAILWAYS.sided(level)
			.getGraph(level, start);
		TrackNode startNode = graph == null ? null : graph.locateNode(start);
		TrackNode endNode = graph == null ? null : graph.locateNode(end);
		return startNode == null || endNode == null ? null
			: graph.getConnectionsFrom(startNode)
				.get(endNode);
	}

	private static AngledTrackAnchorBlockEntity anchorAt(GameTestHelper helper, BlockPos relative) {
		BlockEntity entity = helper.getBlockEntity(relative);
		helper.assertTrue(entity instanceof AngledTrackAnchorBlockEntity, "there should be an anchor at " + relative);
		return (AngledTrackAnchorBlockEntity) entity;
	}

	/** A block entity read back from a tag, as the world does it. */
	private static AngledTrackAnchorBlockEntity reload(GameTestHelper helper, AngledTrackAnchorBlockEntity original,
		CompoundTag tag) {
		BlockEntity loaded = BlockEntity.loadStatic(original.getBlockPos(), original.getBlockState(), tag, helper.getLevel()
			.registryAccess());
		helper.assertTrue(loaded instanceof AngledTrackAnchorBlockEntity, "the saved anchor should load again");
		return (AngledTrackAnchorBlockEntity) loaded;
	}
}
