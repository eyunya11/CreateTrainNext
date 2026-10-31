package com.eyunya.createtrainnext.gametest;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.EAST;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.GRAPH_TICKS;
import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.route;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.speed.CTNEdgePointTypes;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.entity.TravellingPoint.SteerDirection;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import java.util.List;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlockEntity;
import com.eyunya.createtrainnext.signal.SignalAspect;
import com.eyunya.createtrainnext.signal.SignalType;
import com.eyunya.createtrainnext.signal.SignalSpeeds;
import com.eyunya.createtrainnext.speed.SpeedLimitBlockEntity;
import com.eyunya.createtrainnext.speed.SpeedLimitEdgePoint;
import com.eyunya.createtrainnext.speed.TrainSpeedLimits;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.infrastructure.config.AllConfigs;
import com.simibubi.create.infrastructure.config.CTrains;

import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.UUID;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks that a speed limit sign is in km/h and goes no higher than a train can.
 *
 * <p>The ceiling is not a number in this mod: it is the fastest speed Create's own config lets a
 * train reach, in km/h, so the tests change that config and look at the sign. The settings are put
 * back whether or not the checks pass, since every test shares the one server.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class SpeedLimitTests {

	private static final String EMPTY = "empty";

	/** The ceiling is the faster of the two kinds of train, a block a second counted as 3.6 km/h. */
	@GameTest(template = EMPTY)
	public static void theCeilingIsTheFastestATrainCanGoInKmh(GameTestHelper helper) {
		CTrains trains = AllConfigs.server().trains;
		float standard = trains.trainTopSpeed.getF();
		float powered = trains.poweredTrainTopSpeed.getF();
		try {
			trains.trainTopSpeed.set(28.0);
			trains.poweredTrainTopSpeed.set(40.0);
			helper.assertTrue(SpeedLimitEdgePoint.maxLimit() == 144,
				"40 blocks a second is 144 km/h, but the ceiling is " + SpeedLimitEdgePoint.maxLimit());

			trains.trainTopSpeed.set(50.0);
			helper.assertTrue(SpeedLimitEdgePoint.maxLimit() == 180,
				"when the standard train is the faster, 50 blocks a second is 180 km/h, but the ceiling is "
					+ SpeedLimitEdgePoint.maxLimit());

			// Rounded down: a sign never reads faster than a train goes. 28 x 3.6 is 100.8.
			trains.trainTopSpeed.set(28.0);
			trains.poweredTrainTopSpeed.set(28.0);
			helper.assertTrue(SpeedLimitEdgePoint.maxLimit() == 100,
				"28 blocks a second is 100.8 km/h and the ceiling is 100, but is " + SpeedLimitEdgePoint.maxLimit());
		} finally {
			trains.trainTopSpeed.set((double) standard);
			trains.poweredTrainTopSpeed.set((double) powered);
		}
		helper.succeed();
	}

	/**
	 * The sign that ends a limit has no number of its own: it reads as the ceiling whatever that is,
	 * offers no slider, and its point does the same.
	 */
	@GameTest(template = EMPTY)
	public static void theSignThatEndsALimitIsAlwaysAsHighAsTheCeiling(GameTestHelper helper) {
		CTrains trains = AllConfigs.server().trains;
		float standard = trains.trainTopSpeed.getF();
		float powered = trains.poweredTrainTopSpeed.getF();
		BlockPos pos = new BlockPos(4, 2, 4);
		helper.setBlock(pos, CTNRegistry.TRACK_SPEED_RELEASE.get()
			.defaultBlockState());
		BlockEntity entity = helper.getBlockEntity(pos);
		helper.assertTrue(entity instanceof SpeedLimitBlockEntity, "a release sign should have the sign block entity");
		SpeedLimitBlockEntity sign = (SpeedLimitBlockEntity) entity;
		SpeedLimitEdgePoint point = new SpeedLimitEdgePoint();
		point.setLimit(20);
		point.setRelease(true);

		try {
			trains.trainTopSpeed.set(28.0);
			trains.poweredTrainTopSpeed.set(40.0);
			helper.assertTrue(sign.isRelease(), "it should know it is the one that ends a limit");
			helper.assertTrue(sign.getLimit() == 144, "it should read the ceiling, 144, but reads " + sign.getLimit());
			helper.assertTrue(!sign.limit.isActive(), "there should be no slider to set a number on it");
			helper.assertTrue(point.getLimit() == 144, "its point should be as high, but reads " + point.getLimit());

			trains.trainTopSpeed.set(20.0);
			trains.poweredTrainTopSpeed.set(20.0);
			helper.assertTrue(sign.getLimit() == 72 && point.getLimit() == 72,
				"it should follow the config, to 72, but reads " + sign.getLimit() + " and " + point.getLimit());
		} finally {
			trains.trainTopSpeed.set((double) standard);
			trains.poweredTrainTopSpeed.set((double) powered);
		}

		BlockPos limitPos = new BlockPos(8, 2, 4);
		helper.setBlock(limitPos, CTNRegistry.TRACK_SPEED_LIMIT.get()
			.defaultBlockState());
		SpeedLimitBlockEntity ordinary = (SpeedLimitBlockEntity) helper.getBlockEntity(limitPos);
		helper.assertTrue(!ordinary.isRelease() && ordinary.limit.isActive(),
			"the ordinary sign is not a release and keeps its slider");
		helper.succeed();
	}

	// ------------------------------------------------------- trains

	private static final TrackNodeLocation HERE = new TrackNodeLocation(new Vec3(0.5, 1, 0.5));
	private static final TrackNodeLocation THERE = new TrackNodeLocation(new Vec3(10.5, 1, 0.5));
	private static final TrackNode FROM_NODE = new TrackNode(HERE, 0, new Vec3(0, 1, 0));
	private static final TrackNode TO_NODE = new TrackNode(THERE, 1, new Vec3(0, 1, 0));

	/** A train with no carriages: all that is asked of it here is its top speed and what it makes of a point. */
	private static Train train() {
		return new Train(UUID.randomUUID(), null, null, new ArrayList<>(), new ArrayList<>(), false, 0);
	}

	/** A sign on the edge from {@link #HERE} to {@link #THERE}, facing the way that edge runs. */
	private static SpeedLimitEdgePoint sign(int kmh, boolean release) {
		SpeedLimitEdgePoint point = new SpeedLimitEdgePoint();
		point.setLocation(Couple.create(HERE, THERE), 5.0);
		point.setLimit(kmh);
		point.setRelease(release);
		return point;
	}

	/** The train's front passing a point, going from one node towards the other. */
	private static void pass(Train train, TrackEdgePoint point, TrackNode from, TrackNode to) {
		train.frontSignalListener()
			.test(5.0, Pair.of(point, Couple.create(from, to)));
	}

	/**
	 * Saves a graph as the world does when it stops, and reads it back. A point the graph cannot write
	 * fails the test here, instead of turning up in the log after the last test has passed.
	 */
	private static TrackGraph assertSaves(GameTestHelper helper, TrackGraph graph) {
		var registries = helper.getLevel()
			.registryAccess();
		DimensionPalette dimensions = new DimensionPalette();
		CompoundTag saved;
		try {
			saved = graph.write(registries, dimensions);
		} catch (RuntimeException e) {
			// The test report carries only the message, and the trace is what says which point could not be
			// written, so it goes to the log as well.
			CreateTrainNext.LOGGER.error("Saving a track graph failed in a game test", e);
			GameTestAssertException failure = new GameTestAssertException("the graph should save, but writing it threw " + e);
			failure.initCause(e);
			throw failure;
		}
		return TrackGraph.read(saved, registries, dimensions);
	}

	/** Makes a signal head show an aspect, by saving it, changing what it saved and reading it back as the world does. */
	private static void setAspect(GameTestHelper helper, MultiAspectSignalBlockEntity head, String aspect) {
		CompoundTag tag = head.saveWithFullMetadata(helper.getLevel()
			.registryAccess());
		tag.putString("Aspect", aspect);
		head.loadWithComponents(tag, helper.getLevel()
			.registryAccess());
		helper.assertTrue(head.getAspect() == SignalAspect.valueOf(aspect), "setup: the head should show " + aspect + ", but shows " + head.getAspect());
	}

	private static float configuredTopSpeed() {
		return AllConfigs.server().trains.trainTopSpeed.getF() / 20;
	}

	/**
	 * A train is held to the last sign it passed, going the way the sign faces, until one that lifts the
	 * limit; and a train going the other way takes no notice.
	 */
	@GameTest(template = EMPTY)
	public static void aTrainIsHeldToTheLastSignItPassed(GameTestHelper helper) {
		CTrains trains = AllConfigs.server().trains;
		float standard = trains.trainTopSpeed.getF();
		float powered = trains.poweredTrainTopSpeed.getF();
		Train train = train();
		Train other = train();
		try {
			trains.trainTopSpeed.set(28.0);
			trains.poweredTrainTopSpeed.set(40.0);
			helper.assertTrue(train.maxSpeed() == configuredTopSpeed(), "a train under no limit should go as fast as the config says");

			pass(train, sign(60, false), FROM_NODE, TO_NODE);
			float held = 60 / 3.6F / 20;
			helper.assertTrue(Math.abs(train.maxSpeed() - held) < 1.0E-6,
				"a train past a sign for 60 km/h should be held to " + held + " a tick, but may go " + train.maxSpeed());
			helper.assertTrue(TrainSpeedLimits.limitOf(train) == 60, "and it should be remembered as 60");
			helper.assertTrue(other.maxSpeed() == configuredTopSpeed(), "another train should not be held by it");

			// Going the other way the sign is behind it.
			pass(other, sign(60, false), TO_NODE, FROM_NODE);
			helper.assertTrue(other.maxSpeed() == configuredTopSpeed(), "a train going the way the sign does not face should take no notice");

			// A later sign replaces the earlier, faster or slower.
			pass(train, sign(100, false), FROM_NODE, TO_NODE);
			helper.assertTrue(Math.abs(train.maxSpeed() - 100 / 3.6F / 20) < 1.0E-6, "the next sign should replace the last, going up");
			pass(train, sign(20, false), FROM_NODE, TO_NODE);
			helper.assertTrue(Math.abs(train.maxSpeed() - 20 / 3.6F / 20) < 1.0E-6, "and going down");

			// A limit above what the train can do changes nothing.
			pass(train, sign(144, false), FROM_NODE, TO_NODE);
			helper.assertTrue(train.maxSpeed() == configuredTopSpeed(), "a limit above the config's top speed holds nothing back");

			pass(train, sign(30, false), FROM_NODE, TO_NODE);
			pass(train, sign(1, true), FROM_NODE, TO_NODE);
			helper.assertTrue(TrainSpeedLimits.limitOf(train) == null && train.maxSpeed() == configuredTopSpeed(),
				"a release sign should lift the limit");
		} finally {
			pass(train, sign(1, true), FROM_NODE, TO_NODE);
			trains.trainTopSpeed.set((double) standard);
			trains.poweredTrainTopSpeed.set((double) powered);
		}
		helper.succeed();
	}

	/** Each aspect holds a train to the speed in the config, green holds it to nothing, and red is held as the lowest. */
	@GameTest(template = EMPTY)
	public static void eachAspectHoldsATrainToItsConfiguredSpeed(GameTestHelper helper) {
		int restricted = CTNConfig.SERVER.restrictedSpeed.get();
		int approach = CTNConfig.SERVER.approachSpeed.get();
		int reduced = CTNConfig.SERVER.reducedSpeed.get();
		try {
			CTNConfig.SERVER.restrictedSpeed.set(25);
			CTNConfig.SERVER.approachSpeed.set(45);
			CTNConfig.SERVER.reducedSpeed.set(75);
			helper.assertTrue(SignalSpeeds.speedOf(SignalType.FIVE, SignalAspect.PROCEED) == SignalSpeeds.NONE, "green holds a train to nothing");
			helper.assertTrue(SignalSpeeds.speedOf(SignalType.FIVE, SignalAspect.REDUCED_SPEED) == 75, "減速 should be 75");
			helper.assertTrue(SignalSpeeds.speedOf(SignalType.FIVE, SignalAspect.APPROACH) == 45, "注意 should be 45");
			helper.assertTrue(SignalSpeeds.speedOf(SignalType.FIVE, SignalAspect.RESTRICTED) == 25, "警戒 should be 25");
			helper.assertTrue(SignalSpeeds.speedOf(SignalType.FIVE, SignalAspect.STOP) == 25, "a train passing at red is held as the lowest, 25");

			CTNConfig.SERVER.approachSpeed.set(55);
			CTNConfig.SERVER.restrictedSpeed.set(20);
			helper.assertTrue(SignalSpeeds.speedOf(SignalType.FIVE, SignalAspect.APPROACH) == 55 && SignalSpeeds.speedOf(SignalType.FIVE, SignalAspect.RESTRICTED) == 20
				&& SignalSpeeds.speedOf(SignalType.FIVE, SignalAspect.STOP) == 20, "the speeds should follow the config");
		} finally {
			CTNConfig.SERVER.restrictedSpeed.set(restricted);
			CTNConfig.SERVER.approachSpeed.set(approach);
			CTNConfig.SERVER.reducedSpeed.set(reduced);
		}
		helper.succeed();
	}

	/**
	 * A train passing a signal is held to what the heads facing it show, a signal without such a head
	 * lifts the limit, and one whose heads cannot be looked at leaves it alone; the sign's limit is
	 * kept apart, and the slower of the two holds.
	 *
	 * <p>The head here has no track under it, so it works out at stop, which is the aspect that can be
	 * had without a graph to count blocks in.
	 */
	@GameTest(template = EMPTY)
	public static void aTrainPassingASignalIsHeldToWhatItsHeadShows(GameTestHelper helper) {
		CTrains trains = AllConfigs.server().trains;
		float standard = trains.trainTopSpeed.getF();
		float powered = trains.poweredTrainTopSpeed.getF();
		int restricted = CTNConfig.SERVER.restrictedSpeed.get();
		BlockPos pos = new BlockPos(4, 2, 4);
		helper.setBlock(pos, CTNRegistry.MULTI_ASPECT_SIGNAL.get()
			.defaultBlockState());
		BlockPos absolute = helper.absolutePos(pos);

		// A node's location includes the dimension it is in, which the nodes above do not have.
		TrackNodeLocation hereIn = new TrackNodeLocation(new Vec3(0.5, 1, 0.5)).in(helper.getLevel()
			.dimension());
		TrackNodeLocation thereIn = new TrackNodeLocation(new Vec3(10.5, 1, 0.5)).in(helper.getLevel()
			.dimension());
		TrackNode fromNode = new TrackNode(hereIn, 0, new Vec3(0, 1, 0));
		TrackNode toNode = new TrackNode(thereIn, 1, new Vec3(0, 1, 0));
		SignalBoundary facing = new SignalBoundary();
		facing.setLocation(Couple.create(hereIn, thereIn), 5.0);
		facing.blockEntities.get(true)
			.put(absolute, false);
		SignalBoundary headless = new SignalBoundary();
		headless.setLocation(facing.edgeLocation, 5.0);
		SignalBoundary away = new SignalBoundary();
		away.setLocation(facing.edgeLocation, 5.0);
		away.blockEntities.get(false)
			.put(absolute, false);
		SignalBoundary unloaded = new SignalBoundary();
		unloaded.setLocation(facing.edgeLocation, 5.0);
		unloaded.blockEntities.get(true)
			.put(new BlockPos(30000000, 2, 30000000), false);

		SpeedLimitEdgePoint sixty = sign(60, false);
		sixty.setLocation(facing.edgeLocation, 5.0);
		SpeedLimitEdgePoint release = sign(1, true);
		release.setLocation(facing.edgeLocation, 5.0);

		Train train = train();
		try {
			trains.trainTopSpeed.set(28.0);
			trains.poweredTrainTopSpeed.set(40.0);
			CTNConfig.SERVER.restrictedSpeed.set(25);
			TrainSpeedLimits.passedSignal(train, facing, toNode);
			helper.assertTrue(Integer.valueOf(25)
				.equals(TrainSpeedLimits.signalLimitOf(train)), "a head at stop should hold a train to 25, but holds it to " + TrainSpeedLimits.signalLimitOf(train));
			helper.assertTrue(Math.abs(train.maxSpeed() - 25 / 3.6F / 20) < 1.0E-6, "and its top speed should follow");

			// Through the listener a train really uses as its front passes a point, not just the method behind it.
			Train viaListener = train();
			pass(viaListener, facing, fromNode, toNode);
			helper.assertTrue(Integer.valueOf(25)
				.equals(TrainSpeedLimits.signalLimitOf(viaListener)), "passing the signal should hold a train to 25 through the listener too");
			TrainSpeedLimits.applySignal(viaListener, SignalSpeeds.NONE);

			// The same signal from the other direction has no head facing the train.
			Train other = train();
			TrainSpeedLimits.passedSignal(other, facing, fromNode);
			helper.assertTrue(TrainSpeedLimits.signalLimitOf(other) == null, "the head on the other side says nothing to a train going the other way");

			// A sign's limit is its own, and the slower of the two holds.
			pass(train, sixty, fromNode, toNode);
			helper.assertTrue(Math.abs(train.maxSpeed() - 25 / 3.6F / 20) < 1.0E-6, "the signal's 25 is slower than the sign's 60");
			CTNConfig.SERVER.restrictedSpeed.set(90);
			TrainSpeedLimits.passedSignal(train, facing, toNode);
			helper.assertTrue(Math.abs(train.maxSpeed() - 60 / 3.6F / 20) < 1.0E-6, "the sign's 60 should hold now");

			// A train still far off meets what the lamps show now, and a signal at stop says nothing to it.
			MinecraftServer server = helper.getLevel()
				.getServer();
			MultiAspectSignalBlockEntity head = (MultiAspectSignalBlockEntity) helper.getBlockEntity(pos);
			setAspect(helper, head, "STOP");
			helper.assertTrue(SignalSpeeds.shownTo(server, facing, toNode) == SignalSpeeds.UNKNOWN, "a signal showing stop says nothing to a train far off");
			setAspect(helper, head, "REDUCED_SPEED");
			helper.assertTrue(SignalSpeeds.shownTo(server, facing, toNode) == CTNConfig.SERVER.reducedSpeed.get(), "a signal showing 減速 holds a train far off to its speed");
			setAspect(helper, head, "PROCEED");
			helper.assertTrue(SignalSpeeds.shownTo(server, facing, toNode) == SignalSpeeds.NONE, "a signal showing 進行 holds a train far off to nothing");

			// Not knowing leaves it; a signal with no head of the mod's lifts it.
			TrainSpeedLimits.passedSignal(train, unloaded, toNode);
			helper.assertTrue(Integer.valueOf(90)
				.equals(TrainSpeedLimits.signalLimitOf(train)), "a head that cannot be looked at should leave the signal's limit as it was");
			TrainSpeedLimits.passedSignal(train, headless, toNode);
			helper.assertTrue(TrainSpeedLimits.signalLimitOf(train) == null, "a signal without a head of the mod's should lift the signal's limit");
			TrainSpeedLimits.passedSignal(train, away, toNode);
			helper.assertTrue(TrainSpeedLimits.signalLimitOf(train) == null, "so should one whose head faces away");
		} finally {
			pass(train, release, fromNode, toNode);
			TrainSpeedLimits.applySignal(train, SignalSpeeds.NONE);
			CTNConfig.SERVER.restrictedSpeed.set(restricted);
			trains.trainTopSpeed.set((double) standard);
			trains.poweredTrainTopSpeed.set((double) powered);
		}
		helper.succeed();
	}

	// ------------------------------------------------------- slowing in time

	private static final double BRAKING = 0.006;
	private static final float TOP = 1.4F;
	private static final double KMH_PER_TICK = 72;

	/**
	 * The fastest a train may be going is the speed from which braking brings it down to a limit just as
	 * it reaches the point, and nothing is asked of it where the limit is far off or is not slower.
	 */
	@GameTest(template = EMPTY)
	public static void aTrainMaySlowToALimitJustAsItReachesIt(GameTestHelper helper) {
		float limit = (float) (40 / KMH_PER_TICK);
		double room = 100;
		float expected = (float) Math.sqrt(limit * limit + 2 * BRAKING * room);
		float allowed = TrainSpeedLimits.allowedAhead(List.of(new TrainSpeedLimits.Ahead(room + 1, true, 40)),
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, BRAKING, TOP);
		helper.assertTrue(Math.abs(allowed - expected) < 1.0E-5, "100 blocks from a limit of 40 the train may go " + expected + ", but may go " + allowed);

		helper.assertTrue(Math.abs(TrainSpeedLimits.allowedAhead(List.of(new TrainSpeedLimits.Ahead(1, true, 40)),
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, BRAKING, TOP) - limit) < 1.0E-6,
			"at the sign it should be going the limit");
		helper.assertTrue(TrainSpeedLimits.allowedAhead(List.of(new TrainSpeedLimits.Ahead(5000, true, 40)),
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, BRAKING, TOP) == TOP, "far from a limit it should be asked nothing");
		helper.assertTrue(TrainSpeedLimits.allowedAhead(List.of(new TrainSpeedLimits.Ahead(10, true, 144)),
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, BRAKING, TOP) == TOP, "a limit above the top speed asks nothing");
		helper.assertTrue(TrainSpeedLimits.allowedAhead(List.of(), 40, 40, BRAKING, TOP) == TOP,
			"nothing ahead asks nothing; the limits it is under now are applied elsewhere");

		// The limits it will be under then are what count: a release ahead of a sign changes nothing about
		// the sign, and a sign that is no slower than a signal's limit asks no more than the signal does.
		float slow = TrainSpeedLimits.allowedAhead(List.of(new TrainSpeedLimits.Ahead(51, true, 40), new TrainSpeedLimits.Ahead(81, true, -1)),
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, BRAKING, TOP);
		helper.assertTrue(Math.abs(slow - (float) Math.sqrt(limit * limit + 2 * BRAKING * 50)) < 1.0E-5, "a release after a sign does not relax it");
		float together = TrainSpeedLimits.allowedAhead(List.of(new TrainSpeedLimits.Ahead(101, false, 25)), 60, Double.POSITIVE_INFINITY, BRAKING, TOP);
		float lowest = (float) (25 / KMH_PER_TICK);
		helper.assertTrue(Math.abs(together - (float) Math.sqrt(lowest * lowest + 2 * BRAKING * 100)) < 1.0E-5,
			"a signal ahead is held to its own speed, being the slower of it and the sign's 60");
		float lifted = TrainSpeedLimits.allowedAhead(List.of(new TrainSpeedLimits.Ahead(101, false, -1)), Double.POSITIVE_INFINITY, 25, BRAKING, TOP);
		helper.assertTrue(lifted == TOP, "a green signal ahead takes the signal's limit away, so nothing is asked of the train for it");
		helper.succeed();
	}

	/**
	 * Scouting down real track finds the sign that faces the train, how far along it is, and not the
	 * one that faces away; and from the other end it finds the same sign the other way round.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void scoutingDownTheTrackFindsTheSignsAheadAndTheirDistance(GameTestHelper helper) {
		BlockPos[] places = { new BlockPos(4, 2, 16), new BlockPos(16, 2, 16), new BlockPos(28, 2, 16), new BlockPos(40, 2, 16) };
		AngledTrackAnchorBlockEntity[] anchors = route(helper, EAST, places);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			var level = helper.getLevel();
			TrackGraph graph = Create.RAILWAYS.sided(level)
				.getGraph(level, new com.simibubi.create.content.trains.graph.TrackNodeLocation(anchors[0].exitNode()).in(level));
			helper.assertTrue(graph != null, "the route should be in the graph by now");
			TrackNode a = graph.locateNode(new TrackNodeLocation(anchors[0].exitNode()).in(level));
			TrackNode b = graph.locateNode(new TrackNodeLocation(anchors[1].exitNode()).in(level));
			TrackNode c = graph.locateNode(new TrackNodeLocation(anchors[2].exitNode()).in(level));
			TrackNode d = graph.locateNode(new TrackNodeLocation(anchors[3].entryNode()).in(level));
			TrackEdge ab = graph.getConnectionsFrom(a)
				.get(b);
			TrackEdge bc = graph.getConnectionsFrom(b)
				.get(c);

			// One sign on the second edge facing along the route, one on the third facing back along it.
			SpeedLimitEdgePoint facing = new SpeedLimitEdgePoint();
			facing.setId(UUID.randomUUID());
			facing.setType(CTNEdgePointTypes.SPEED_LIMIT);
			facing.setLocation(Couple.create(b.getLocation(), c.getLocation()), 6.0);
			facing.setLimit(40);
			SpeedLimitEdgePoint away = new SpeedLimitEdgePoint();
			away.setId(UUID.randomUUID());
			away.setType(CTNEdgePointTypes.SPEED_LIMIT);
			away.setLocation(Couple.create(d.getLocation(), c.getLocation()), 3.0);
			away.setLimit(20);

			// Neither point has a sign behind it, so nothing would ever take them out of the graph again,
			// and Create cannot save a point that has no block entity to name: the world would fail to save
			// the track when the server stopped. They go whether or not the checks pass, and even if adding
			// the second one throws.
			try {
				graph.addPoint(CTNEdgePointTypes.SPEED_LIMIT, facing);
				graph.addPoint(CTNEdgePointTypes.SPEED_LIMIT, away);

				TravellingPoint start = new TravellingPoint(a, b, ab, 0, false);
				var steer = start.steer(SteerDirection.NONE, new Vec3(0, 1, 0));
				List<TrainSpeedLimits.Ahead> ahead = TrainSpeedLimits.scan(null, graph, start, true, 200, steer);
				helper.assertTrue(ahead.size() == 1, "going along the route, one sign should be met, not two: " + ahead);
				helper.assertTrue(ahead.get(0)
					.sign() && ahead.get(0)
						.kmh() == 40, "and it should be the one for 40: " + ahead);
				helper.assertTrue(Math.abs(ahead.get(0)
					.distance() - (ab.getLength() + 6.0)) < 0.5,
					"it should be along the first edge and six into the second, " + (ab.getLength() + 6.0) + ", not " + ahead.get(0)
						.distance());

				// Not far enough to reach it.
				TravellingPoint shortStart = new TravellingPoint(a, b, ab, 0, false);
				helper.assertTrue(TrainSpeedLimits.scan(null, graph, shortStart, true, 5, shortStart.steer(SteerDirection.NONE, new Vec3(0, 1, 0)))
					.isEmpty(), "a sign beyond the reach is not found");

				// The same track from the other end, going back along it: the other sign now faces the train.
				TrackEdge dc = graph.getConnectionsFrom(d)
					.get(c);
				TravellingPoint back = new TravellingPoint(d, c, dc, 0, false);
				List<TrainSpeedLimits.Ahead> reverse = TrainSpeedLimits.scan(null, graph, back, true, 200, back.steer(SteerDirection.NONE, new Vec3(0, 1, 0)));
				helper.assertTrue(reverse.size() == 1 && reverse.get(0)
					.kmh() == 20, "coming the other way the sign for 20 should be the one met: " + reverse);

				// And running backwards along the route, with the end of the train leading, by the way a sign faces
				// the train and not the way the track was laid.
				TrackEdge cd = graph.getConnectionsFrom(c)
					.get(d);
				TravellingPoint west = new TravellingPoint(c, d, cd, cd.getLength() - 1, false);
				List<TrainSpeedLimits.Ahead> backwards = TrainSpeedLimits.scan(null, graph, west, false, 200,
					west.steer(SteerDirection.NONE, new Vec3(0, 1, 0)));
				helper.assertTrue(backwards.size() == 1 && backwards.get(0)
					.kmh() == 20 && Math.abs(backwards.get(0)
						.distance() - 2.0) < 0.5,
					"going west, the sign for 20 should be met two blocks on, and the one facing east not at all: " + backwards);
			} finally {
				graph.removePoint(CTNEdgePointTypes.SPEED_LIMIT, facing.getId());
				graph.removePoint(CTNEdgePointTypes.SPEED_LIMIT, away.getId());
			}
			assertSaves(helper, graph);
			helper.succeed();
		});
	}

	/**
	 * A sign placed against real track gets a point the graph can save, and the point comes back from
	 * the save standing at the sign, with the limit the sign was set to before the point existed.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void aSignsPointIsSavedWithTheTrack(GameTestHelper helper) {
		BlockPos[] places = { new BlockPos(4, 2, 4), new BlockPos(16, 2, 4), new BlockPos(28, 2, 4) };
		AngledTrackAnchorBlockEntity[] anchors = route(helper, EAST, places);
		BlockPos signPos = new BlockPos(16, 2, 6);
		helper.setBlock(signPos, CTNRegistry.TRACK_SPEED_LIMIT.get()
			.defaultBlockState());
		SpeedLimitBlockEntity sign = (SpeedLimitBlockEntity) helper.getBlockEntity(signPos);
		sign.limit.setValue(60);
		// Told which track it stands against the way the item that places a sign tells it.
		CompoundTag target = new CompoundTag();
		target.putBoolean("TargetDirection", true);
		target.put("TargetTrack", NbtUtils.writeBlockPos(anchors[1].getBlockPos()
			.subtract(sign.getBlockPos())));
		sign.edgePoint.read(target, helper.getLevel()
			.registryAccess(), false);

		// The point appears once the graph has the route and the sign has ticked, which is not on a set tick,
		// so this is asked again every tick until it holds or the test times out.
		helper.succeedWhen(() -> {
			SpeedLimitEdgePoint point = sign.getSpeedLimitPoint();
			helper.assertTrue(point != null, "setup: the sign should have a point on the track by now");
			TrackGraph graph = Create.RAILWAYS.sided(helper.getLevel()).trackNetworks.values()
				.stream()
				.filter(g -> g.getPoint(CTNEdgePointTypes.SPEED_LIMIT, point.getId()) == point)
				.findFirst()
				.orElse(null);
			helper.assertTrue(graph != null, "setup: the sign's point should be in a graph");

			SpeedLimitEdgePoint loaded = assertSaves(helper, graph).getPoint(CTNEdgePointTypes.SPEED_LIMIT, point.getId());
			helper.assertTrue(loaded != null, "the point should come back from the save");
			helper.assertTrue(sign.getBlockPos()
				.equals(loaded.getBlockEntityPos()),
				"it should come back at the sign, " + sign.getBlockPos() + ", not " + loaded.getBlockEntityPos());
			helper.assertTrue(loaded.getLimit() == 60, "and still be the sign's 60, not " + loaded.getLimit());
		});
	}

	/** What a train was held to is saved with the world and read back. */
	@GameTest(template = EMPTY)
	public static void aTrainsLimitSurvivesBeingSaved(GameTestHelper helper) {
		UUID id = UUID.randomUUID();
		TrainSpeedLimits.TrainSpeedLimitData data = new TrainSpeedLimits.TrainSpeedLimitData();
		data.setSign(id, 75);
		data.setSignal(id, 25);
		CompoundTag saved = data.save(new CompoundTag(), helper.getLevel()
			.registryAccess());
		TrainSpeedLimits.TrainSpeedLimitData loaded = TrainSpeedLimits.TrainSpeedLimitData.load(saved, helper.getLevel()
			.registryAccess());
		helper.assertTrue(Integer.valueOf(75)
			.equals(loaded.sign(id)), "the sign's limit should come back from a save, but is " + loaded.sign(id));
		helper.assertTrue(Integer.valueOf(25)
			.equals(loaded.signal(id)), "the signal's limit should come back from a save, but is " + loaded.signal(id));
		helper.assertTrue(loaded.sign(UUID.randomUUID()) == null, "a train that was never held should not be");
		data.releaseSign(id);
		data.releaseSignal(id);
		helper.assertTrue(data.sign(id) == null && data.signal(id) == null, "releasing should forget the limits");
		helper.succeed();
	}

	/**
	 * A sign cannot be set above the ceiling, and one that was set when the ceiling was higher reads
	 * as the ceiling is now, on the block and at the point trains consult.
	 */
	@GameTest(template = EMPTY)
	public static void aSignNeverReadsAboveTheCeiling(GameTestHelper helper) {
		CTrains trains = AllConfigs.server().trains;
		float standard = trains.trainTopSpeed.getF();
		float powered = trains.poweredTrainTopSpeed.getF();
		BlockPos pos = new BlockPos(4, 2, 4);
		helper.setBlock(pos, CTNRegistry.TRACK_SPEED_LIMIT.get()
			.defaultBlockState());
		BlockEntity entity = helper.getBlockEntity(pos);
		helper.assertTrue(entity instanceof SpeedLimitBlockEntity, "a speed limit sign should have its block entity");
		SpeedLimitBlockEntity sign = (SpeedLimitBlockEntity) entity;
		SpeedLimitEdgePoint point = new SpeedLimitEdgePoint();

		try {
			trains.trainTopSpeed.set(28.0);
			trains.poweredTrainTopSpeed.set(40.0);
			helper.assertTrue(sign.getLimit() == SpeedLimitEdgePoint.DEFAULT_LIMIT,
				"a new sign should read " + SpeedLimitEdgePoint.DEFAULT_LIMIT + " km/h, but reads " + sign.getLimit());

			sign.limit.setValue(1000);
			helper.assertTrue(sign.getLimit() == 144, "a sign set too high should stop at 144, but reads " + sign.getLimit());
			point.setLimit(1000);
			helper.assertTrue(point.getLimit() == 144, "so should the point, but it reads " + point.getLimit());

			trains.trainTopSpeed.set(20.0);
			trains.poweredTrainTopSpeed.set(20.0);
			helper.assertTrue(sign.getLimit() == 72,
				"after the config was lowered the sign should read 72, but reads " + sign.getLimit());
			helper.assertTrue(point.getLimit() == 72,
				"after the config was lowered the point should read 72, but reads " + point.getLimit());
			helper.assertTrue(sign.limit.getValueSettings()
				.value() == 72, "and the slider should start at 72");

			sign.limit.setValue(100);
			helper.assertTrue(sign.getLimit() == 72, "a sign set above the lowered ceiling should stop at it");
			sign.limit.setValue(60);
			helper.assertTrue(sign.getLimit() == 60, "a limit under the ceiling should be kept, but reads " + sign.getLimit());
		} finally {
			trains.trainTopSpeed.set((double) standard);
			trains.poweredTrainTopSpeed.set((double) powered);
		}
		helper.succeed();
	}
}
