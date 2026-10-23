package com.eyunya.createtrainnext.gametest;

import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.GRAPH_TICKS;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.anchor;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.assertConsistent;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.click;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.relative;
import static com.eyunya.createtrainnext.gametest.GraphCleanlinessTests.assertClean;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.eyunya.createtrainnext.track.CurveWandItem;
import com.eyunya.createtrainnext.track.CurveWandSelection;
import com.eyunya.createtrainnext.track.GradeSpec;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackShape;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
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
 * Checks curves docked onto Create's own sloped track, on a real server level: that the anchor at the
 * slope shares the slope's node and keeps it, that the graph Create makes from the two is one a train
 * can cross either way, and that the planner and the wand lay such a curve only straight on along the
 * slope's line and only the way the slope goes.
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

	/**
	 * The node an anchor shares with a slope has the slope's normal, and so does the slope's edge from it,
	 * whichever block last built the graph around them.
	 *
	 * <p>Create gives a straight edge the normal of its first node and puts a carriage's anchor a block along
	 * it, and a node keeps the normal of whatever created it first. The anchor creates the shared node when
	 * it rebuilds the graph from itself, from its own seed, or from the curve its far neighbour reports; the
	 * slope creates it when the graph is rebuilt from the slope. All three have to say the same, or a train
	 * on the slope in one direction is a few tenths of a block off the rail, and which direction it is
	 * depends on who rebuilt last.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void theNodeSharedWithASlopeHasTheSlopesNormal(GameTestHelper helper) {
		dockedOnTop(helper);
		helper.setBlock(HIGH_SLOPE, CreateSlopeTests.slope(TrackShape.AN));
		AngledTrackAnchorBlockEntity start = anchor(helper, LEVEL_BELOW);
		AngledTrackAnchorBlockEntity foot = anchor(helper, FOOT_DOCK);
		start.linkTo(foot, AngledTrackAnchorBlockEntity.tangentOf(NORTH, 0), AngledTrackAnchorBlockEntity.tangentOf(NORTH, 1),
			false, true);
		Level level = helper.getLevel();

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertSlopeNormals(helper, level, SLOPE, "after the graph was built from the top dock");
			assertSlopeNormals(helper, level, HIGH_SLOPE, "after the graph was built from the foot dock");

			// Rebuilt from each block that reports the node, one at a time: the slope, the anchor docked onto
			// it, and the anchor at the far end of the curve, which reports the node as the end of a curve.
			BlockPos[][] triggers = { { SLOPE, TOP_DOCK, LEVEL_BEYOND }, { HIGH_SLOPE, FOOT_DOCK, LEVEL_BELOW } };
			String[] names = { "the slope", "the docked anchor", "the anchor at the far end" };
			for (BlockPos[] group : triggers)
				for (int i = 0; i < group.length; i++) {
					BlockPos at = helper.absolutePos(group[i]);
					TrackPropagator.onRailAdded(level, at, level.getBlockState(at));
					BlockPos slope = group[0];
					assertSlopeNormals(helper, level, slope, "after the graph was rebuilt from " + names[i] + " of the slope at "
						+ slope);
				}
			helper.succeed();
		});
	}

	/**
	 * A docked end whose neighbour cannot be found, because it is in a chunk that is not loaded, still reports
	 * the slope's node, with the slope's normal, rather than falling back on the middle of its block.
	 *
	 * <p>What a block reports is what the graph is rebuilt from, and what nothing reports is never taken out
	 * again: a node made at the centre of the block, which nothing else knows of, would stay as a post of its
	 * own in the F3 view for good. The neighbour is made unfindable by pointing the link at a block with
	 * nothing in it, which is how the anchor sees an unloaded one as well.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 40)
	public static void aDockedEndWithNoNeighbourToBeFoundStillReportsTheSlopesNode(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity dock = dockedOnTop(helper);
		HolderLookup.Provider registries = helper.getLevel()
			.registryAccess();
		CompoundTag tag = dock.saveWithFullMetadata(registries);
		tag.putLong("Next", helper.absolutePos(new BlockPos(16, 3, 4))
			.asLong());
		dock.loadWithComponents(tag, registries);
		helper.assertTrue(dock.isSlopeDocked() && dock.buildCurveToNext() == null,
			"setup: the anchor should be docked and have no curve it can build");

		Level level = helper.getLevel();
		BlockPos pos = helper.absolutePos(TOP_DOCK);
		BlockState state = level.getBlockState(pos);
		List<DiscoveredLocation> reported = new ArrayList<>(((ITrackBlock) state.getBlock()).getConnected(level, pos, state, false, null));
		helper.assertTrue(reported.size() == 1, "it should report one place, but reports " + reported.size());
		Vec3 top = slopeTop(helper, SLOPE);
		helper.assertTrue(reported.get(0)
			.getLocation()
			.distanceToSqr(top) < 1.0E-12, "it should report the slope's top, " + top + ", but reports " + reported.get(0)
				.getLocation());

		// The normal is not readable from the location; it is what a node made from it comes out with.
		TrackGraph graph = new TrackGraph();
		Create.RAILWAYS.putGraphWithDefaultGroup(graph);
		try {
			graph.createNodeIfAbsent(reported.get(0));
			Vec3 normal = graph.locateNode(reported.get(0))
				.getNormal();
			helper.assertTrue(normal.distanceToSqr(TrackShape.AN.getNormal()) < 1.0E-9,
				"and with the slope's normal " + TrackShape.AN.getNormal() + ", but has " + normal);
		} finally {
			Create.RAILWAYS.removeGraphAndGroup(graph);
		}
		helper.succeed();
	}

	/**
	 * The normal an anchor docked onto a slope is given is the one Create gives that slope, whichever of the
	 * four it is and whichever way the route runs over it.
	 */
	@GameTest(template = EMPTY)
	public static void theNormalOfADockIsTheNormalOfEachCreateSlope(GameTestHelper helper) {
		for (TrackShape shape : new TrackShape[] { TrackShape.AN, TrackShape.AS, TrackShape.AE, TrackShape.AW }) {
			Vec3 axis = shape.getAxes()
				.get(0);
			double run = Math.hypot(axis.x, axis.z);
			Vec3 heading = new Vec3(axis.x / run, 0, axis.z / run);
			double climb = axis.y / run;
			for (double way : new double[] { 1, -1 }) {
				Vec3 normal = AngledTrackAnchorBlockEntity.slopeNormal(heading.scale(way), climb * way);
				helper.assertTrue(normal.distanceToSqr(shape.getNormal()) < 1.0E-12,
					shape + " should have the normal " + shape.getNormal() + " running either way, but running " + (way > 0 ? "up" : "down")
						+ " it is " + normal);
			}
		}
		helper.assertTrue(AngledTrackAnchorBlockEntity.slopeNormal(new Vec3(0, 0, 1), 0)
			.distanceToSqr(new Vec3(0, 1, 0)) < 1.0E-12, "level track should have straight up for a normal");
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

	// ------------------------------------------------------------ the planner

	/**
	 * Clicked from a slope, a curve leaves the end that faces where it is aimed, from the slope's own node, at
	 * the slope's climb, and runs straight on along the slope's line: a block aimed at two to the side is
	 * taken onto the line. Built, the anchor at the slope is docked, and a train can cross the joint.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aCurveLeavesTheTopOfASlopeStraightOnAlongItsLine(GameTestHelper helper) {
		atFortyFiveDegrees(() -> {
			helper.setBlock(SLOPE, CreateSlopeTests.slope(TrackShape.AN));
			Level level = helper.getLevel();
			Vec3 top = slopeTop(helper, SLOPE);

			CurvePlanner.Plan plan = plan(helper, SLOPE, new BlockPos(18, 7, 8));
			helper.assertTrue(plan.valid(), "a curve on from the top of the slope should be possible, but: " + plan.error());
			helper.assertTrue(plan.start()
				.onSlope()
				&& plan.start()
					.anchorPos()
					.equals(helper.absolutePos(TOP_DOCK)),
				"it should start from an anchor at " + TOP_DOCK + " docked onto the slope");
			helper.assertTrue(plan.curve().starts.getFirst()
				.distanceToSqr(top) < 1.0E-12, "it should leave from the slope's top, " + top);
			helper.assertTrue(Math.abs(plan.curve().axes.getFirst()
				.normalize()
				.dot(new Vec3(0, 1, -1).normalize()) - 1) < 1.0E-9 && plan.startSlope() == 1.0,
				"it should leave along the slope's axis, climbing at 1000 permille, but climbs at " + plan.startSlope());
			helper.assertTrue(plan.end()
				.anchorPos()
				.equals(helper.absolutePos(new BlockPos(16, 7, 8))), "the far end should have been taken onto the slope's line, but is at "
					+ relative(helper, plan.end()
						.anchorPos()));
			helper.assertTrue(plan.turnDegrees() < 1.0E-6 && plan.junction() == null, "it should be one straight curve");

			helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the curve should have been built");
			AngledTrackAnchorBlockEntity dock = anchorAt(helper, TOP_DOCK);
			helper.assertTrue(dock.isSlopeDocked() && dock.entryNode()
				.distanceToSqr(top) < 1.0E-12, "the anchor at the slope should be docked onto it");

			helper.runAfterDelay(GRAPH_TICKS, () -> {
				// The slope's foot, the shared top, and the far anchor's centre, since it climbs.
				assertClean(helper, "with a curve laid from the top of a slope", 3);
				assertConsistent(helper, dock);
				Vec3 far = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(16, 7, 8)));
				assertCrossing(helper, level, slopeFoot(helper, SLOPE), top, far, "up the slope and onto the curve");
				assertCrossing(helper, level, far, top, slopeFoot(helper, SLOPE), "off the curve and down the slope");
				helper.succeed();
			});
		});
	}

	/** The same for a slope that climbs east, whose line runs along x rather than z. */
	@GameTest(template = EMPTY)
	public static void aCurveRunsAlongASlopeThatClimbsEastToo(GameTestHelper helper) {
		atFortyFiveDegrees(() -> {
			BlockPos slope = new BlockPos(8, 1, 8);
			helper.setBlock(slope, CreateSlopeTests.slope(TrackShape.AE));
			Vec3 top = Vec3.atBottomCenterOf(helper.absolutePos(slope))
				.add(0.5, 1, 0);

			CurvePlanner.Plan plan = plan(helper, slope, new BlockPos(20, 7, 9));
			helper.assertTrue(plan.valid(), "a curve on from the top of the slope should be possible, but: " + plan.error());
			helper.assertTrue(plan.start()
				.anchorPos()
				.equals(helper.absolutePos(new BlockPos(9, 2, 8))) && plan.curve().starts.getFirst()
					.distanceToSqr(top) < 1.0E-12, "it should leave from the slope's top, " + top + ", from an anchor at (9, 2, 8)");
			helper.assertTrue(plan.end()
				.anchorPos()
				.equals(helper.absolutePos(new BlockPos(20, 7, 8))), "the far end should have been taken onto the slope's line, but is at "
					+ relative(helper, plan.end()
						.anchorPos()));
			helper.succeed();
		});
	}

	/** From the foot, the curve leaves going down. */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aCurveLeavesTheFootOfASlopeGoingDown(GameTestHelper helper) {
		atFortyFiveDegrees(() -> {
			helper.setBlock(HIGH_SLOPE, CreateSlopeTests.slope(TrackShape.AN));
			Level level = helper.getLevel();
			Vec3 foot = slopeFoot(helper, HIGH_SLOPE);

			CurvePlanner.Plan plan = plan(helper, HIGH_SLOPE, LEVEL_BELOW);
			helper.assertTrue(plan.valid(), "a curve on from the foot of the slope should be possible, but: " + plan.error());
			helper.assertTrue(plan.start()
				.anchorPos()
				.equals(helper.absolutePos(FOOT_DOCK)) && plan.curve().starts.getFirst()
					.distanceToSqr(foot) < 1.0E-12, "it should leave from the slope's foot, at " + FOOT_DOCK);
			helper.assertTrue(plan.startSlope() == -1.0, "it should leave falling at 1000 permille, but climbs at " + plan.startSlope());

			helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the curve should have been built");
			helper.runAfterDelay(GRAPH_TICKS, () -> {
				assertClean(helper, "with a curve laid from the foot of a slope", 3);
				Vec3 far = Vec3.atBottomCenterOf(helper.absolutePos(LEVEL_BELOW));
				assertCrossing(helper, level, slopeTop(helper, HIGH_SLOPE), foot, far, "down the slope and onto the curve");
				assertCrossing(helper, level, far, foot, slopeTop(helper, HIGH_SLOPE), "off the curve and up the slope");
				helper.succeed();
			});
		});
	}

	/**
	 * A curve may end at a slope from the slope's line, and from nowhere else: one from an anchor to the side
	 * of it would have to turn.
	 */
	@GameTest(template = EMPTY)
	public static void aCurveMayArriveAtASlopeOnlyAlongItsLine(GameTestHelper helper) {
		atFortyFiveDegrees(() -> {
			helper.setBlock(SLOPE, CreateSlopeTests.slope(TrackShape.AN));
			BlockPos inLine = new BlockPos(16, 6, 8);
			BlockPos aside = new BlockPos(22, 6, 8);
			anchor(helper, inLine);
			anchor(helper, aside);

			CurvePlanner.Plan straight = plan(helper, inLine, SLOPE);
			helper.assertTrue(straight.valid(), "a curve down to the top of the slope along its line should be possible, but: "
				+ straight.error());
			helper.assertTrue(straight.end()
				.onSlope()
				&& straight.end()
					.anchorPos()
					.equals(helper.absolutePos(TOP_DOCK)) && straight.endSlope() == -1.0,
				"it should arrive at the top dock going down at 1000 permille, but arrives at " + straight.endSlope());
			helper.assertTrue(straight.curve().starts.getSecond()
				.distanceToSqr(slopeTop(helper, SLOPE)) < 1.0E-12,
				"and the ghost should end on the slope's top, " + slopeTop(helper, SLOPE) + ", not at "
					+ straight.curve().starts.getSecond());

			CurvePlanner.Plan turning = plan(helper, aside, SLOPE);
			helper.assertTrue("slope_turn".equals(turning.error()), "one from the side should be refused as a turn, but: " + turning.error());
			helper.assertTrue(turning.curve() != null, "and still have a curve to draw in red");
			helper.succeed();
		});
	}

	/**
	 * A curve leaving the top of a slope only goes up from there, and one leaving the foot only down: nothing
	 * that climbs out of the slope and comes back over itself.
	 */
	@GameTest(template = EMPTY)
	public static void nothingComesBackDownOffTheTopOfASlope(GameTestHelper helper) {
		helper.setBlock(SLOPE, CreateSlopeTests.slope(TrackShape.AN));
		for (int y : new int[] { 1, 2, 4 }) {
			CurvePlanner.Plan plan = plan(helper, SLOPE, new BlockPos(16, y, 8));
			helper.assertTrue("slope_way".equals(plan.error()),
				"a curve from the top of the slope to a block at height " + y + " should be refused, but: " + plan.error());
		}
		helper.succeed();
	}

	/** And from the foot, nothing that goes down out of the slope and climbs back up: level with the foot or above it is refused. */
	@GameTest(template = EMPTY)
	public static void nothingClimbsBackUpOffTheFootOfASlope(GameTestHelper helper) {
		helper.setBlock(HIGH_SLOPE, CreateSlopeTests.slope(TrackShape.AN));
		for (int y : new int[] { 5, 6, 7 }) {
			CurvePlanner.Plan plan = plan(helper, HIGH_SLOPE, new BlockPos(32, y, 30));
			helper.assertTrue("slope_way".equals(plan.error()),
				"a curve from the foot of the slope to a block at height " + y + " should be refused, but: " + plan.error());
		}
		helper.succeed();
	}

	/** The grade screen's level-off works from a slope: the curve eases from 45 degrees to level. */
	@GameTest(template = EMPTY)
	public static void aGradeOfNothingLevelsACurveOffASlope(GameTestHelper helper) {
		atFortyFiveDegrees(() -> {
			helper.setBlock(SLOPE, CreateSlopeTests.slope(TrackShape.AN));
			CurvePlanner.Plan plan = CurvePlanner.plan(helper.getLevel(), helper.absolutePos(SLOPE),
				helper.absolutePos(new BlockPos(16, 1, 14)), Direction.UP, 0, new GradeSpec(0));
			helper.assertTrue(plan.valid() && plan.grade() != null, "a curve laid at no grade should be possible, but: " + plan.error());
			helper.assertTrue(plan.endSlope() == 0.0 && plan.rise() > 0,
				"it should climb out of the slope and arrive level, but rises " + plan.rise() + " and arrives at " + plan.endSlope());
			helper.assertTrue(!CurvePlanner.startsLevel(helper.getLevel(), helper.absolutePos(SLOPE)),
				"a slope does not start level, so a grade of nothing there means levelling off");

			CurvePlanner.Plan ahead = CurvePlanner.planByDistance(helper.getLevel(), helper.absolutePos(SLOPE), new GradeSpec(0), 10,
				NORTH);
			helper.assertTrue(ahead.valid() && ahead.end()
				.anchorPos()
				.getX() == helper.absolutePos(SLOPE)
					.getX(), "a distance laid from the slope should run along its line, but: " + ahead.error());
			helper.succeed();
		});
	}

	/**
	 * Under a limit below 45 degrees no curve can be docked onto a slope at all, since its end there is as
	 * steep as the slope.
	 */
	@GameTest(template = EMPTY)
	public static void aLimitBelowFortyFiveDegreesLeavesSlopesAlone(GameTestHelper helper) {
		helper.setBlock(SLOPE, CreateSlopeTests.slope(TrackShape.AN));
		int original = CTNConfig.SERVER.maxGrade.get();
		try {
			CTNConfig.SERVER.maxGrade.set(333);
			CurvePlanner.Plan plan = plan(helper, SLOPE, new BlockPos(16, 7, 8));
			helper.assertTrue("too_steep".equals(plan.error()), "under a limit of 333 it should be too steep, but: " + plan.error());
		} finally {
			CTNConfig.SERVER.maxGrade.set(original);
		}
		helper.succeed();
	}

	/**
	 * An anchor standing on its own where the dock goes is docked as it stands; one that is already the end
	 * of another route is in the way; and a docked one is no end to start or finish another curve at.
	 */
	@GameTest(template = EMPTY)
	public static void whatIsAlreadyBesideASlope(GameTestHelper helper) {
		atFortyFiveDegrees(() -> {
			helper.setBlock(SLOPE, CreateSlopeTests.slope(TrackShape.AN));
			anchor(helper, TOP_DOCK);
			CurvePlanner.Plan lone = plan(helper, SLOPE, new BlockPos(16, 7, 8));
			helper.assertTrue(lone.valid() && !lone.start()
				.mustPlace() && lone.start()
					.onSlope(), "an anchor on its own beside the slope should be docked as it is, but: " + lone.error());
			helper.assertTrue(CurvePlanner.apply(helper.getLevel(), lone) == null, "the curve should have been built");
			AngledTrackAnchorBlockEntity docked = anchorAt(helper, TOP_DOCK);
			helper.assertTrue(docked.isSlopeDocked() && docked.entryNode()
				.distanceToSqr(slopeTop(helper, SLOPE)) < 1.0E-12
				&& docked.tangent()
					.subtract(new Vec3(0, 1, -1))
					.lengthSqr() < 1.0E-6,
				"and the anchor that was there docked, aimed up the slope's line, but it points along " + docked.tangent());

			helper.assertTrue("start_taken".equals(plan(helper, TOP_DOCK, new BlockPos(16, 7, 4)).error()),
				"a docked anchor should not be somewhere to start a curve");
			helper.assertTrue("start_taken".equals(plan(helper, SLOPE, new BlockPos(16, 7, 4)).error()),
				"nor the slope it is docked onto, at that end");
			BlockPos other = new BlockPos(16, 6, 2);
			anchor(helper, other);
			helper.assertTrue("end_taken".equals(plan(helper, other, TOP_DOCK).error()), "nor somewhere to finish one");

			helper.setBlock(HIGH_SLOPE, CreateSlopeTests.slope(TrackShape.AN));
			CurveConsistencyTests.route(helper, 0.0F, new BlockPos(32, 6, 10), new BlockPos(32, 6, 19));
			helper.assertTrue("no_room".equals(plan(helper, HIGH_SLOPE, new BlockPos(32, 7, 4)).error()),
				"the end of another route where the dock would go should be in the way");
			helper.succeed();
		});
	}

	/** A slope is somewhere to start; a junction still is not. */
	@GameTest(template = EMPTY)
	public static void aSlopeIsSomewhereToStartAndAJunctionIsNot(GameTestHelper helper) {
		helper.setBlock(SLOPE, CreateSlopeTests.slope(TrackShape.AN));
		BlockPos crossing = new BlockPos(32, 1, 16);
		helper.setBlock(crossing, CreateSlopeTests.slope(TrackShape.CR_O));
		Level level = helper.getLevel();
		helper.assertTrue(CurvePlanner.canStartFrom(level, helper.absolutePos(SLOPE)), "a slope should be somewhere to start");
		helper.assertTrue(!CurvePlanner.canStartFrom(level, helper.absolutePos(crossing)), "a crossing should not");
		helper.assertTrue("junction_track".equals(plan(helper, crossing, new BlockPos(32, 1, 4)).error()),
			"a curve from a crossing should be refused as one, but: " + plan(helper, crossing, new BlockPos(32, 1, 4)).error());
		helper.succeed();
	}

	/** Turning the far end by hand does not turn a curve off a slope: it runs along the slope's line all the same. */
	@GameTest(template = EMPTY)
	public static void aCurveOffASlopeIsNotTurnedByHand(GameTestHelper helper) {
		atFortyFiveDegrees(() -> {
			helper.setBlock(SLOPE, CreateSlopeTests.slope(TrackShape.AN));
			CurvePlanner.Plan plan = CurvePlanner.plan(helper.getLevel(), helper.absolutePos(SLOPE),
				helper.absolutePos(new BlockPos(16, 7, 8)), Direction.UP, 10.0F);
			helper.assertTrue(plan.valid() && plan.turnDegrees() < 1.0E-6 && !plan.adjustable(),
				"turned by hand it should still be straight and not to be turned, but: " + plan.error() + ", turning "
					+ plan.turnDegrees() + " degrees");
			helper.succeed();
		});
	}

	/**
	 * Where the block on the slope's line has something in it already: an anchor there is where the curve
	 * ends, and anything else is in the way. So is something in the block the anchor at the slope needs.
	 */
	@GameTest(template = EMPTY)
	public static void whatIsInTheWayOfACurveOffASlope(GameTestHelper helper) {
		atFortyFiveDegrees(() -> {
			helper.setBlock(SLOPE, CreateSlopeTests.slope(TrackShape.AN));
			BlockPos waiting = new BlockPos(16, 7, 8);
			anchor(helper, waiting);
			CurvePlanner.Plan toAnchor = plan(helper, SLOPE, new BlockPos(18, 7, 8));
			helper.assertTrue(toAnchor.valid() && !toAnchor.end()
				.mustPlace()
				&& toAnchor.end()
					.anchorPos()
					.equals(helper.absolutePos(waiting)),
				"a curve aimed beside an anchor on the line should end at the anchor, but: " + toAnchor.error());

			helper.setBlock(new BlockPos(16, 6, 12), Blocks.STONE);
			CurvePlanner.Plan blocked = plan(helper, SLOPE, new BlockPos(17, 6, 12));
			helper.assertTrue("no_room".equals(blocked.error()), "a block in the way on the line should be no room, but: " + blocked.error());

			helper.setBlock(HIGH_SLOPE, CreateSlopeTests.slope(TrackShape.AN));
			helper.setBlock(FOOT_DOCK, Blocks.STONE);
			CurvePlanner.Plan noDock = plan(helper, HIGH_SLOPE, LEVEL_BELOW);
			helper.assertTrue("no_room".equals(noDock.error()),
				"a block where the anchor at the slope would go should be no room, but: " + noDock.error());
			helper.succeed();
		});
	}

	/**
	 * The block a curve off a slope is moved to takes one coordinate from the slope and the other from the
	 * block aimed at, so it can be in a chunk that is not loaded while both of those are. Looking at it there
	 * would load it, on the server and on every click: it is no room instead, and the chunk stays as it was.
	 */
	@GameTest(template = EMPTY)
	public static void aCurveOffASlopeDoesNotLoadTheChunkItsLineRunsInto(GameTestHelper helper) {
		helper.setBlock(SLOPE, CreateSlopeTests.slope(TrackShape.AN));
		ServerLevel level = helper.getLevel();
		BlockPos slope = helper.absolutePos(SLOPE);
		// Far from every test's box, where nothing has had a reason to load a chunk. Given relative to the test,
		// as the planner is given it, since a test's box can be turned.
		BlockPos far = SLOPE.offset(3000, 6, -3000);
		BlockPos aimed = helper.absolutePos(far);
		level.getChunk(aimed.getX() >> 4, aimed.getZ() >> 4);

		CurvePlanner.Plan plan = plan(helper, SLOPE, far);
		helper.assertTrue(plan.end() != null && !plan.valid(), "a curve that far should not be possible, but should get as far as its far end");
		BlockPos onLine = plan.end()
			.anchorPos();
		helper.assertTrue(onLine.getX() == slope.getX() || onLine.getZ() == slope.getZ(),
			"setup: the far end should have been moved onto the slope's line, to " + onLine);
		helper.assertTrue(level.isLoaded(aimed) && (onLine.getX() >> 4 != aimed.getX() >> 4 || onLine.getZ() >> 4 != aimed.getZ() >> 4),
			"setup: the block aimed at should be loaded, and the one on the slope's line in another chunk");
		helper.assertTrue(!level.isLoaded(onLine), "planning it should not have loaded the chunk on the slope's line, at " + onLine);
		helper.succeed();
	}

	// ------------------------------------------------------------ the wand

	/**
	 * Through the wand: a click on the slope and one where the curve should go lays it and carries the
	 * selection on from the far end, and a curve laid onto a slope is where the route stops. A click on the
	 * docked anchor starts nothing.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void theWandDocksCurvesOntoSlopes(GameTestHelper helper) {
		atFortyFiveDegrees(() -> {
			helper.setBlock(SLOPE, CreateSlopeTests.slope(TrackShape.AN));
			helper.setBlock(HIGH_SLOPE, CreateSlopeTests.slope(TrackShape.AN));
			anchor(helper, LEVEL_BELOW);
			Player player = helper.makeMockPlayer(GameType.CREATIVE);
			Level level = helper.getLevel();

			// What the ghost shows before each click, to hold the built curves to.
			CurvePlanner.Plan off = plan(helper, SLOPE, new BlockPos(17, 7, 8));
			CurvePlanner.Plan onto = plan(helper, LEVEL_BELOW, HIGH_SLOPE);

			ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
			helper.assertTrue(click(helper, player, wand, SLOPE).consumesAction(), "the click on the slope should pick it as the start");
			helper.assertTrue(click(helper, player, wand, new BlockPos(17, 7, 8)).consumesAction(), "the second click should lay the curve");
			CurveWandSelection carried = CurveWandItem.selectionIn(wand, level);
			helper.assertTrue(carried != null && carried.pos()
				.equals(helper.absolutePos(new BlockPos(16, 7, 8))), "the selection should carry on from the far end, on the line");
			helper.assertTrue(anchorAt(helper, TOP_DOCK).isSlopeDocked(), "the anchor at the slope should be docked");

			ItemStack fresh = new ItemStack(CTNRegistry.CURVE_WAND.get());
			helper.assertTrue(click(helper, player, fresh, TOP_DOCK) == InteractionResult.FAIL,
				"a click on the docked anchor should start nothing");

			ItemStack up = new ItemStack(CTNRegistry.CURVE_WAND.get());
			helper.assertTrue(click(helper, player, up, LEVEL_BELOW).consumesAction(), "the click on the anchor should pick it");
			helper.assertTrue(click(helper, player, up, HIGH_SLOPE).consumesAction(), "the click on the slope should lay the curve up to it");
			helper.assertTrue(CurveWandItem.selectionIn(up, level) == null, "a curve laid onto a slope should end the selection");
			helper.assertTrue(anchorAt(helper, FOOT_DOCK).isSlopeDocked(), "and the anchor at its foot should be docked");

			// The ghost is the track: the same curve, to the same node at the slope, whichever end it is.
			double offBy = CurvePlanner.deviation(off.curve(), anchorAt(helper, TOP_DOCK).buildCurveToNext());
			double ontoBy = CurvePlanner.deviation(onto.curve(), anchorAt(helper, LEVEL_BELOW).buildCurveToNext());
			helper.assertTrue(offBy < 1.0E-6 && ontoBy < 1.0E-6,
				"the curves built should be the ones planned, but are " + offBy + " and " + ontoBy + " off them");
			assertSameNormals(helper, off.curve(), anchorAt(helper, TOP_DOCK).buildCurveToNext(), "the curve off the top");
			assertSameNormals(helper, onto.curve(), anchorAt(helper, LEVEL_BELOW).buildCurveToNext(), "the curve onto the foot");

			helper.runAfterDelay(GRAPH_TICKS, () -> {
				// Each slope's two ends, one shared, and the centre of the anchor at the other end of each curve.
				assertClean(helper, "with a curve docked onto each slope", 6);
				assertConsistent(helper, anchorAt(helper, TOP_DOCK));
				assertConsistent(helper, anchorAt(helper, LEVEL_BELOW));
				helper.succeed();
			});
		});
	}

	/**
	 * Runs the part of a test that plans at the limit the test is written for, 45 degrees, whatever the server
	 * config says. {@code build/gametest/config/createtrainnext-server.toml} outlives the test world and keeps
	 * the defaults it was first written with, and one written before the default was raised still says 333,
	 * under which every dock is too steep. What a test checks later, once the graph is built, does not ask.
	 */
	private static void atFortyFiveDegrees(Runnable body) {
		int original = CTNConfig.SERVER.maxGrade.get();
		try {
			CTNConfig.SERVER.maxGrade.set(1000);
			body.run();
		} finally {
			CTNConfig.SERVER.maxGrade.set(original);
		}
	}

	private static CurvePlanner.Plan plan(GameTestHelper helper, BlockPos start, BlockPos end) {
		return CurvePlanner.plan(helper.getLevel(), helper.absolutePos(start), helper.absolutePos(end), Direction.UP, 0);
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

	/** Checks the normal of both nodes of the slope at {@code slope}, and of its edge each way. */
	private static void assertSlopeNormals(GameTestHelper helper, Level level, BlockPos slope, String when) {
		Vec3 expected = TrackShape.AN.getNormal();
		TrackNodeLocation topLocation = new TrackNodeLocation(slopeTop(helper, slope)).in(level);
		TrackNodeLocation footLocation = new TrackNodeLocation(slopeFoot(helper, slope)).in(level);
		TrackGraph graph = Create.RAILWAYS.sided(level)
			.getGraph(level, topLocation);
		TrackNode top = graph == null ? null : graph.locateNode(topLocation);
		TrackNode foot = graph == null ? null : graph.locateNode(footLocation);
		helper.assertTrue(top != null && foot != null, when + ", both ends of the slope should be nodes of the graph");
		TrackEdge down = graph.getConnectionsFrom(top)
			.get(foot);
		TrackEdge up = graph.getConnectionsFrom(foot)
			.get(top);
		helper.assertTrue(down != null && up != null, when + ", the slope should be an edge each way");

		String[] what = { "the top node", "the foot node", "the edge from the top", "the edge from the foot" };
		Vec3[] seen = { top.getNormal(), foot.getNormal(), down.getNormal(graph, 0.5), up.getNormal(graph, 0.5) };
		for (int i = 0; i < what.length; i++)
			helper.assertTrue(seen[i].distanceToSqr(expected) < 1.0E-9,
				when + ", " + what[i] + " should have the slope's normal " + expected + ", but has " + seen[i]);
	}

	/** Checks that two curves have the same normal at each end, which is what rails are laid and trains stand by. */
	private static void assertSameNormals(GameTestHelper helper, BezierConnection planned, BezierConnection built, String what) {
		helper.assertTrue(built != null, what + " should have been built");
		for (boolean first : new boolean[] { true, false })
			helper.assertTrue(planned.normals.get(first)
				.distanceToSqr(built.normals.get(first)) < 1.0E-9,
				what + ": the ghost's normal at its " + (first ? "start" : "end") + " should be the built curve's, "
					+ built.normals.get(first) + ", but is " + planned.normals.get(first));
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
