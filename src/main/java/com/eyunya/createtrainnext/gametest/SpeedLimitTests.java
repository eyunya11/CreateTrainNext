package com.eyunya.createtrainnext.gametest;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.signal.SignalAspect;
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
import net.minecraft.world.phys.Vec3;
import net.minecraft.gametest.framework.GameTest;
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
			helper.assertTrue(SignalSpeeds.speedOf(SignalAspect.PROCEED) == SignalSpeeds.NONE, "green holds a train to nothing");
			helper.assertTrue(SignalSpeeds.speedOf(SignalAspect.REDUCED_SPEED) == 75, "減速 should be 75");
			helper.assertTrue(SignalSpeeds.speedOf(SignalAspect.APPROACH) == 45, "注意 should be 45");
			helper.assertTrue(SignalSpeeds.speedOf(SignalAspect.RESTRICTED) == 25, "警戒 should be 25");
			helper.assertTrue(SignalSpeeds.speedOf(SignalAspect.STOP) == 25, "a train passing at red is held as the lowest, 25");

			CTNConfig.SERVER.approachSpeed.set(55);
			CTNConfig.SERVER.restrictedSpeed.set(20);
			helper.assertTrue(SignalSpeeds.speedOf(SignalAspect.APPROACH) == 55 && SignalSpeeds.speedOf(SignalAspect.RESTRICTED) == 20
				&& SignalSpeeds.speedOf(SignalAspect.STOP) == 20, "the speeds should follow the config");
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
