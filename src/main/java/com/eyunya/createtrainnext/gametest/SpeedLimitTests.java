package com.eyunya.createtrainnext.gametest;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.speed.SpeedLimitBlockEntity;
import com.eyunya.createtrainnext.speed.SpeedLimitEdgePoint;
import com.eyunya.createtrainnext.speed.TrainSpeedLimits;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
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
	private static void pass(Train train, SpeedLimitEdgePoint point, TrackNode from, TrackNode to) {
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

	/** What a train was held to is saved with the world and read back. */
	@GameTest(template = EMPTY)
	public static void aTrainsLimitSurvivesBeingSaved(GameTestHelper helper) {
		UUID id = UUID.randomUUID();
		TrainSpeedLimits.TrainSpeedLimitData data = new TrainSpeedLimits.TrainSpeedLimitData();
		data.set(id, 75);
		CompoundTag saved = data.save(new CompoundTag(), helper.getLevel()
			.registryAccess());
		TrainSpeedLimits.TrainSpeedLimitData loaded = TrainSpeedLimits.TrainSpeedLimitData.load(saved, helper.getLevel()
			.registryAccess());
		helper.assertTrue(Integer.valueOf(75)
			.equals(loaded.get(id)), "the limit should come back from a save, but is " + loaded.get(id));
		helper.assertTrue(loaded.get(UUID.randomUUID()) == null, "a train that was never held should not be");
		data.release(id);
		helper.assertTrue(data.get(id) == null, "releasing should forget the limit");
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
