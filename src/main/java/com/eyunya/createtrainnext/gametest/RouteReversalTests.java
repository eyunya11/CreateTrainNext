package com.eyunya.createtrainnext.gametest;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AnchorAxis;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlock;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks that turning a route end for end changes nothing that anything outside the anchors can
 * see.
 *
 * <p>The thing at risk is a signal. Create stores which way one faces as a sign against the axis
 * the anchor under it reports, and the axis an anchor in the middle of a route reports is measured
 * along the route — so a route turned round without care would put every signal on it facing the
 * other way, with nothing visibly wrong until a train arrived. These tests are about that, and
 * about the route being left alone when it cannot be turned in full.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class RouteReversalTests {

	private static final String EMPTY = "empty";

	/**
	 * How close two vectors have to be to count as the same.
	 *
	 * <p>Not zero, because a heading goes through a yaw stored as a float and 180 degrees are added
	 * to it, which loses a little in the seventh decimal place. Create's own tolerance on the axis
	 * is about 0.06, so this is far inside anything that could matter.
	 */
	private static final double SAME = 1.0E-4;

	/**
	 * Four anchors on a gentle bend, heading roughly east and drifting north.
	 *
	 * <p>The two ends are squared up to the axis, which gives them a span and has them report the
	 * block's discrete axis; the two between them are not, and sit at their block's centre reporting
	 * an axis measured off the curves either side. Both kinds have to come through a reversal
	 * unchanged, and for different reasons.
	 */
	private static final BlockPos[] POSITIONS = { new BlockPos(4, 2, 18), new BlockPos(16, 2, 16),
		new BlockPos(30, 2, 10), new BlockPos(42, 2, 10) };

	private static final float[] YAWS = { 270.0F, 254.0F, 258.0F, 270.0F };

	private record Snapshot(@Nullable BlockPos prev, @Nullable BlockPos next, float yaw, boolean flipped, Vec3 heading,
		Vec3 axis, Vec3 entry, Vec3 exit) {}

	/**
	 * Everything Create reads off the anchors comes through a reversal as it was, and a second
	 * reversal puts the anchors themselves back too.
	 */
	@GameTest(template = EMPTY)
	public static void reversalChangesNothingCreateCanSee(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = bend(helper);
		Level level = helper.getLevel();
		Snapshot[] before = snapshots(level, anchors);

		int turned = anchors[1].reverseRoute();
		helper.assertTrue(turned == anchors.length,
			"every anchor in the route should have been turned round, but " + turned + " were");

		for (int i = 0; i < anchors.length; i++) {
			Snapshot was = before[i];
			Snapshot now = snapshot(level, anchors[i]);

			// What a reversal is for.
			helper.assertTrue(Objects.equals(now.prev(), was.next()) && Objects.equals(now.next(), was.prev()),
				"anchor " + i + " should have had its prev and next swapped");
			helper.assertTrue(now.heading()
				.dot(was.heading()) < -0.999, "anchor " + i + " should point the other way");
			// An anchor at the centre of its block reports an axis that turns with the route, so its
			// flag has to turn as well. An end reports its block's own axis, which does not turn.
			if (i == 1 || i == 2)
				helper.assertTrue(now.flipped() != was.flipped(),
					"anchor " + i + " should have its flag turned along with the route");

			// What it must not disturb: the axis Create compares signal placement against, and the
			// two nodes of the anchor, which are the same two with their names exchanged.
			helper.assertTrue(near(now.axis(), was.axis()),
				"anchor " + i + " reported " + was.axis() + " and now reports " + now.axis());
			helper.assertTrue(near(now.entry(), was.exit()) && near(now.exit(), was.entry()),
				"anchor " + i + " should keep its nodes where they were, entry and exit swapped");
		}

		anchors[2].reverseRoute();

		for (int i = 0; i < anchors.length; i++) {
			Snapshot was = before[i];
			Snapshot now = snapshot(level, anchors[i]);

			helper.assertTrue(Objects.equals(now.prev(), was.prev()) && Objects.equals(now.next(), was.next()),
				"anchor " + i + " should have its links back after a second reversal");
			helper.assertTrue(now.flipped() == was.flipped(),
				"anchor " + i + " should have its flag back after a second reversal");
			helper.assertTrue(Mth.degreesDifferenceAbs(now.yaw(), was.yaw()) < 0.01F,
				"anchor " + i + " should point as it did, but turned from " + was.yaw() + " to " + now.yaw());
			helper.assertTrue(near(now.axis(), was.axis()),
				"anchor " + i + " reported " + was.axis() + " and now reports " + now.axis());
		}

		helper.succeed();
	}

	/**
	 * A signal stays on the edge it was placed on.
	 *
	 * <p>This is the same thing as the axis staying put, asked the way a signal asks it: which edge
	 * of the graph does a placement on this side of the anchor resolve to, and does it still resolve
	 * to that one afterwards. If a reversal were to negate the axis, front and back would trade
	 * places here.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void reversalKeepsAnEdgePointOnItsEdge(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = bend(helper);
		BlockPos middle = helper.absolutePos(POSITIONS[1]);

		// The graph is built from a scheduled tick, one tick after the anchors are linked.
		helper.runAfterDelay(5, () -> {
			Level level = helper.getLevel();

			TrackGraphLocation frontBefore = locate(level, middle, true);
			TrackGraphLocation backBefore = locate(level, middle, false);
			helper.assertTrue(frontBefore != null && backBefore != null,
				"the graph should have an edge on each side of the middle anchor by now");
			helper.assertTrue(!sameEdge(frontBefore, backBefore),
				"the two sides of an anchor should resolve to different edges, or this proves nothing");

			helper.assertTrue(anchors[1].reverseRoute() == anchors.length, "the route should have been turned round");

			TrackGraphLocation frontAfter = locate(level, middle, true);
			TrackGraphLocation backAfter = locate(level, middle, false);
			helper.assertTrue(frontAfter != null && backAfter != null,
				"the anchor should still resolve an edge on each side after the route was turned round");
			helper.assertTrue(sameEdge(frontBefore, frontAfter),
				"the front of the middle anchor should still be on the edge it was on");
			helper.assertTrue(sameEdge(backBefore, backAfter),
				"the back of the middle anchor should still be on the edge it was on");

			helper.succeed();
		});
	}

	/**
	 * A route that cannot be walked in full is left exactly as it was.
	 *
	 * <p>Turning half of one round would leave two stretches running opposite ways with a link
	 * between them that agrees with neither, which is worse than the problem it was meant to solve.
	 */
	@GameTest(template = EMPTY)
	public static void reversalRefusesARouteItCannotWalk(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = bend(helper);
		Level level = helper.getLevel();

		// Takes the second anchor's block entity out from under its neighbours, which still link to
		// it. Asked for again it comes back new and blank, with no links to agree with theirs.
		level.removeBlockEntity(anchors[1].getBlockPos());

		Snapshot first = snapshot(level, anchors[0]);
		Snapshot last = snapshot(level, anchors[3]);

		helper.assertTrue(anchors[0].reverseRoute() == 0, "a route with a broken link should not be turned round");
		helper.assertTrue(anchors[3].reverseRoute() == 0, "nor from its other end");

		for (int i : new int[] { 0, 2, 3 }) {
			helper.assertTrue(!anchors[i].isFlipped(), "anchor " + i + " should not have been touched");
		}
		Snapshot firstAfter = snapshot(level, anchors[0]);
		Snapshot lastAfter = snapshot(level, anchors[3]);
		helper.assertTrue(Objects.equals(first.next(), firstAfter.next())
			&& Objects.equals(first.prev(), firstAfter.prev()) && first.yaw() == firstAfter.yaw(),
			"the first anchor should be as it was");
		helper.assertTrue(Objects.equals(last.next(), lastAfter.next())
			&& Objects.equals(last.prev(), lastAfter.prev()) && last.yaw() == lastAfter.yaw(),
			"the last anchor should be as it was");

		helper.succeed();
	}

	/**
	 * Being turned round is remembered across a save, and an anchor that never was saves nothing
	 * extra, so worlds made before this existed read exactly as they did.
	 */
	@GameTest(template = EMPTY)
	public static void theFlagSurvivesBeingSaved(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = bend(helper);
		Level level = helper.getLevel();

		AngledTrackAnchorBlockEntity lone = anchor(helper, new BlockPos(10, 2, 4));
		CompoundTag untouched = lone.saveWithFullMetadata(level.registryAccess());
		helper.assertTrue(!untouched.contains("AxisFlipped"), "an anchor with nothing to hold should not write a flag at all");

		// A reversal turns the flag of an anchor at the centre of its block, whichever way it was, so
		// this leaves it set either way.
		anchors[1].reverseRoute();
		if (!anchors[1].isFlipped())
			anchors[1].reverseRoute();
		AngledTrackAnchorBlockEntity original = anchors[1];
		helper.assertTrue(original.isFlipped(), "setup: the anchor should be holding its sign");
		CompoundTag saved = original.saveWithFullMetadata(level.registryAccess());
		helper.assertTrue(saved.contains("AxisFlipped"), "the flag should be written");

		BlockEntity loaded = BlockEntity.loadStatic(original.getBlockPos(), original.getBlockState(), saved,
			level.registryAccess());
		helper.assertTrue(loaded instanceof AngledTrackAnchorBlockEntity, "the saved anchor should load again");
		AngledTrackAnchorBlockEntity copy = (AngledTrackAnchorBlockEntity) loaded;

		helper.assertTrue(copy.isFlipped(), "the loaded anchor should still count as turned round");
		helper.assertTrue(Objects.equals(copy.getPrev(), original.getPrev())
			&& Objects.equals(copy.getNext(), original.getNext()), "the loaded anchor should keep its links");
		helper.assertTrue(Mth.degreesDifferenceAbs(copy.getYaw(), original.getYaw()) < 0.001F,
			"the loaded anchor should keep its heading");

		helper.succeed();
	}

	// --------------------------------------------------------------- helpers

	/** The four anchors of {@link #POSITIONS}, placed and linked in order, each pointed along {@link #YAWS}. */
	private static AngledTrackAnchorBlockEntity[] bend(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = new AngledTrackAnchorBlockEntity[POSITIONS.length];
		for (int i = 0; i < anchors.length; i++)
			anchors[i] = anchor(helper, POSITIONS[i]);

		for (int i = 0; i + 1 < anchors.length; i++) {
			// An anchor is aimed by the first link it takes part in, and left alone after that.
			Vec3 mine = i == 0 ? AngledTrackAnchorBlockEntity.directionFromYaw(YAWS[0]) : null;
			anchors[i].linkTo(anchors[i + 1], mine, AngledTrackAnchorBlockEntity.directionFromYaw(YAWS[i + 1]));
		}
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

	private static Snapshot[] snapshots(Level level, AngledTrackAnchorBlockEntity[] anchors) {
		Snapshot[] snapshots = new Snapshot[anchors.length];
		for (int i = 0; i < anchors.length; i++)
			snapshots[i] = snapshot(level, anchors[i]);
		return snapshots;
	}

	private static Snapshot snapshot(Level level, AngledTrackAnchorBlockEntity anchor) {
		return new Snapshot(anchor.getPrev(), anchor.getNext(), anchor.getYaw(), anchor.isFlipped(), anchor.heading(),
			reportedAxis(level, anchor), anchor.entryNode(), anchor.exitNode());
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

	private static boolean near(Vec3 a, Vec3 b) {
		return a.distanceTo(b) < SAME;
	}
}
