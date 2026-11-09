package com.eyunya.createtrainnext.gametest;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlock;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlockEntity;
import com.eyunya.createtrainnext.signal.SignalAspect;
import com.eyunya.createtrainnext.signal.SignalHeads;
import com.eyunya.createtrainnext.signal.SignalSpeeds;
import com.eyunya.createtrainnext.signal.SignalType;
import com.eyunya.createtrainnext.speed.TrainSpeedLimits;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.SignalBoundary;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A head in a chunk that is not loaded holds a train to what it shows, as a loaded one does, so long as the
 * server has seen which head it is.
 *
 * <p>A chunk cannot be unloaded from inside a test, so a head that is not loaded is a position far outside
 * the world put into a signal's records, with its type written into {@link SignalHeads}' table the way a
 * head that had once been loaded there would have written it. Create does not look at those positions
 * while the signal stands, so nothing tries to load them.
 *
 * <p>Run apart from the others, so that their layout stays as it was.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class UnloadedSignalHeadTests {

	private static final String EMPTY = "empty";
	private static final String BATCH = "unloadedHeads";

	/** Outside the world border, so never loaded. */
	private static final BlockPos FAR = new BlockPos(30000000, 2, 30000000);
	private static final BlockPos FAR_UNKNOWN = new BlockPos(30000000, 2, 30000016);

	private static Train train() {
		return new Train(UUID.randomUUID(), null, null, new ArrayList<>(), new ArrayList<>(), false, 0);
	}

	/**
	 * Without any track: a head that is not loaded and whose type is known holds a train to the speed of its
	 * aspect, where it used to leave the train's limit as it was; one whose type is not known still leaves
	 * it. The signal is in no graph and has no section, so its aspect is 停止 and a passing train is held to
	 * 警戒's speed.
	 */
	@GameTest(template = EMPTY, batch = BATCH)
	public static void aHeadThatIsNotLoadedStillHoldsATrain(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		ResourceKey<Level> dimension = level.dimension();
		int restricted = CTNConfig.SERVER.restrictedSpeed.get();

		TrackNodeLocation here = new TrackNodeLocation(new Vec3(0.5, 1, 0.5)).in(dimension);
		TrackNodeLocation there = new TrackNodeLocation(new Vec3(10.5, 1, 0.5)).in(dimension);
		TrackNode toNode = new TrackNode(there, 1, new Vec3(0, 1, 0));
		SignalBoundary known = new SignalBoundary();
		known.setLocation(Couple.create(here, there), 5.0);
		known.blockEntities.get(true)
			.put(FAR, false);
		SignalBoundary unknown = new SignalBoundary();
		unknown.setLocation(known.edgeLocation, 5.0);
		unknown.blockEntities.get(true)
			.put(FAR_UNKNOWN, false);

		Train train = train();
		try {
			CTNConfig.SERVER.restrictedSpeed.set(25);
			SignalHeads.record(level, FAR, SignalType.FIVE);
			SignalHeads.forget(level, FAR_UNKNOWN);
			helper.assertFalse(level.isLoaded(FAR), "setup: the far head should not be loaded");
			helper.assertTrue(SignalHeads.typeAt(server, dimension, FAR) == SignalType.FIVE,
				"the table should say a five-aspect head is at the far position, but says " + SignalHeads.typeAt(server, dimension, FAR));
			helper.assertTrue(SignalHeads.typeAt(server, dimension, FAR_UNKNOWN) == null, "a position never written in should have no type");

			TrainSpeedLimits.applySignal(train, 90);
			TrainSpeedLimits.passedSignal(train, known, toNode);
			helper.assertTrue(Integer.valueOf(25)
				.equals(TrainSpeedLimits.signalLimitOf(train)),
				"a head that is not loaded but is known should hold a passing train to 25, but holds it to " + TrainSpeedLimits.signalLimitOf(train));
			helper.assertTrue(SignalHeads.aspectOf(server, known, true) == SignalAspect.STOP,
				"its aspect should be stop, but is " + SignalHeads.aspectOf(server, known, true));

			TrainSpeedLimits.applySignal(train, 90);
			TrainSpeedLimits.passedSignal(train, unknown, toNode);
			helper.assertTrue(Integer.valueOf(90)
				.equals(TrainSpeedLimits.signalLimitOf(train)),
				"a head the server has never seen should leave the limit as it was, but it is " + TrainSpeedLimits.signalLimitOf(train));
			helper.assertTrue(SignalHeads.aspectOf(server, unknown, true) == null, "and it has no aspect to give");

			// Once its block is gone the head is no longer known.
			SignalHeads.forget(level, FAR);
			TrainSpeedLimits.passedSignal(train, known, toNode);
			helper.assertTrue(Integer.valueOf(90)
				.equals(TrainSpeedLimits.signalLimitOf(train)), "a forgotten head should leave the limit as it was");
		} finally {
			SignalHeads.forget(level, FAR);
			TrainSpeedLimits.applySignal(train, SignalSpeeds.NONE);
			CTNConfig.SERVER.restrictedSpeed.set(restricted);
		}
		helper.succeed();
	}

	/**
	 * On a real line with nothing ahead, a head that is not loaded shows the top of its own ladder, worked
	 * out from the graph, so a passing train is let go of the last signal's limit and a train still far off
	 * is told the same; forced to red, it holds a passing train to 警戒's speed and says nothing to one far off.
	 * The real head on the signal is taken out of its records for the while, so only the far one is asked.
	 */
	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 120)
	public static void aHeadThatIsNotLoadedShowsWhatTheGraphSays(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = CurveTargetingTests.route(helper, CurveTargetingTests.EAST,
			CurveTargetingTests.along(6, 14, 22, 30));

		helper.runAfterDelay(CurveTargetingTests.BUILD_TICKS, () -> {
			AngledTrackAnchorBlockEntity holder = anchors[1];
			BlockPos farEnd = anchors[2].getBlockPos();
			int segment = (int) (holder.getConnections()
				.get(farEnd)
				.getLength() * 2) / 2;
			MultiAspectSignalBlockEntity head = CurveTargetingTests.signalOnCurve(helper, new BlockPos(18, 2, 18), holder,
				farEnd, segment, true, CTNRegistry.MULTI_ASPECT_SIGNAL.get());

			helper.runAfterDelay(CurveTargetingTests.SIGNAL_TICKS + CurveTargetingTests.OPEN_LINE_TICKS,
				() -> checkFarHead(helper, head));
		});
	}

	private static void checkFarHead(GameTestHelper helper, MultiAspectSignalBlockEntity head) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		SignalBoundary point = head.edgePoint.getEdgePoint();
		helper.assertTrue(point != null, "setup: the head should stand on a signal");
		TrackGraph graph = SignalHeads.graphOf(point);
		helper.assertTrue(graph != null, "setup: the signal should be in a graph");
		BlockPos headPos = head.getBlockPos();
		boolean side = point.blockEntities.get(true)
			.containsKey(headPos);
		Map<BlockPos, Boolean> records = point.blockEntities.get(side);
		helper.assertTrue(records.containsKey(headPos), "setup: the head should be in the signal's records");
		TrackNode toward = graph.locateNode(side ? point.edgeLocation.getSecond() : point.edgeLocation.getFirst());
		helper.assertTrue(toward != null && point.isPrimary(toward) == side, "setup: the node a train meets the head heading for");
		helper.assertTrue(SignalHeads.typeAt(server, level.dimension(), headPos) == SignalType.FIVE,
			"a loaded head's type should be read from its block");

		int restricted = CTNConfig.SERVER.restrictedSpeed.get();
		Boolean powered = records.remove(headPos);
		Train train = train();
		try {
			CTNConfig.SERVER.restrictedSpeed.set(25);
			SignalHeads.record(level, FAR, SignalType.FIVE);
			records.put(FAR, false);

			TrainSpeedLimits.applySignal(train, 90);
			TrainSpeedLimits.passedSignal(train, point, toward);
			helper.assertTrue(TrainSpeedLimits.signalLimitOf(train) == null,
				"a five-aspect head that is not loaded shows 進行 on an open line and should lift the limit, but it is "
					+ TrainSpeedLimits.signalLimitOf(train));
			helper.assertTrue(SignalSpeeds.shownTo(server, point, toward) == SignalSpeeds.NONE,
				"a train far off should be told 進行 holds it to nothing, but is told " + SignalSpeeds.shownTo(server, point, toward));
			helper.assertTrue(SignalSpeeds.shownTo(server, graph, point, toward) == SignalSpeeds.NONE,
				"the same with the graph given");
			helper.assertTrue(SignalHeads.aspectOf(server, point, side) == SignalAspect.PROCEED,
				"its aspect should be 進行, but is " + SignalHeads.aspectOf(server, point, side));

			// The ladder is the recorded type's.
			SignalHeads.record(level, FAR, SignalType.SIX);
			helper.assertTrue(SignalHeads.aspectOf(server, point, side) == SignalAspect.HIGH_SPEED_PROCEED,
				"a six-aspect head should show 高速進行, but shows " + SignalHeads.aspectOf(server, point, side));
			SignalHeads.record(level, FAR, SignalType.FIVE);

			// Forced to red, as redstone into the head would.
			records.put(FAR, true);
			TrainSpeedLimits.passedSignal(train, point, toward);
			helper.assertTrue(Integer.valueOf(25)
				.equals(TrainSpeedLimits.signalLimitOf(train)),
				"passing it at red should hold a train to 25, but holds it to " + TrainSpeedLimits.signalLimitOf(train));
			helper.assertTrue(SignalSpeeds.shownTo(server, point, toward) == SignalSpeeds.UNKNOWN,
				"a signal at stop says nothing to a train far off");
		} finally {
			records.remove(FAR);
			if (powered != null)
				records.put(headPos, powered);
			SignalHeads.forget(level, FAR);
			TrainSpeedLimits.applySignal(train, SignalSpeeds.NONE);
			CTNConfig.SERVER.restrictedSpeed.set(restricted);
		}
		helper.succeed();
	}

	/**
	 * A head writes itself into the table as it is placed, a head of another type put in its place
	 * replaces it, turning it keeps it, and breaking it takes it out.
	 */
	@GameTest(template = EMPTY, batch = BATCH)
	public static void aHeadIsWrittenInAndTakenOut(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		BlockPos pos = new BlockPos(4, 2, 4);
		BlockPos absolute = helper.absolutePos(pos);
		helper.setBlock(pos, CTNRegistry.MULTI_ASPECT_SIGNAL_3.get()
			.defaultBlockState());
		// A block entity's onLoad comes at the start of the next tick.
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(SignalHeads.recordedType(server, level.dimension(), absolute) == SignalType.THREE,
				"a placed three-aspect head should be in the table, but the table says "
					+ SignalHeads.recordedType(server, level.dimension(), absolute));
			helper.setBlock(pos, CTNRegistry.MULTI_ASPECT_SIGNAL_6.get()
				.defaultBlockState());
			helper.runAfterDelay(2, () -> {
				helper.assertTrue(SignalHeads.recordedType(server, level.dimension(), absolute) == SignalType.SIX,
					"a six-aspect head put in its place should replace it, but the table says "
						+ SignalHeads.recordedType(server, level.dimension(), absolute));
				// A property changing keeps the block entity, which does not load again, so the head must
				// not be taken out for it.
				helper.setBlock(pos, level.getBlockState(absolute)
					.setValue(MultiAspectSignalBlock.ROTATION, 4));
				helper.assertTrue(SignalHeads.recordedType(server, level.dimension(), absolute) == SignalType.SIX,
					"a head turned in place should stay in the table");
				helper.setBlock(pos, Blocks.AIR.defaultBlockState());
				helper.assertTrue(SignalHeads.recordedType(server, level.dimension(), absolute) == null,
					"a broken head should be taken out of the table");
				helper.succeed();
			});
		});
	}
}
