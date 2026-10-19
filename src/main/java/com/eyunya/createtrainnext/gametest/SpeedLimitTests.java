package com.eyunya.createtrainnext.gametest;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.speed.SpeedLimitBlockEntity;
import com.eyunya.createtrainnext.speed.SpeedLimitEdgePoint;
import com.simibubi.create.infrastructure.config.AllConfigs;
import com.simibubi.create.infrastructure.config.CTrains;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;

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
