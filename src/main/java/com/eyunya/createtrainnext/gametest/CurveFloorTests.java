package com.eyunya.createtrainnext.gametest;

import static com.eyunya.createtrainnext.gametest.CurveConsistencyTests.anchor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurveFillerBlock;
import com.eyunya.createtrainnext.track.CurveFillerBlockEntity;
import com.eyunya.createtrainnext.track.CurveOccupancy;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackShape;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks the floor under a curve as a player meets it: the top of whatever would hold someone standing at each
 * point of the rails, read from the collision the blocks really have.
 *
 * <p>The floor is one block per cell the curve passes through. A cell holds a single flat slab, and on a gentle
 * climb that is a step of a few pixels from one cell to the next. On a 45 degree climb the rails rise a block in
 * every block they cross, so a flat slab per cell is a step of most of a block, and it lies up to three quarters
 * of a block under the rails at the far side of its cell: whoever walks the line of the rails sinks into them, and
 * cannot climb the steps without jumping. Create's own sloped track has no collision at all, so a curve that joins
 * one is the only thing to stand on there.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class CurveFloorTests {

	private static final String EMPTY = "empty";

	/** Long enough for the anchors' first scheduled tick to have laid the floor. */
	private static final int FLOOR_TICKS = 20;

	/** How far from the rails a floor may be and still hold up someone standing on them, in blocks. */
	private static final double REACH = 0.4;

	/** The most a player walks up without jumping, less a margin: a step of a block's third is fine, of a half is not. */
	private static final double WALKABLE_STEP = 0.32;

	/** How far either rail is from the middle of the track, in blocks. */
	private static final double RAIL_GAUGE = 0.3;

	private static final Vec3 NORTH = new Vec3(0, 0, -1);
	private static final Vec3 EAST = new Vec3(1, 0, 0);

	/** A curve that leaves the top of a slope at 45 degrees and eases off to level. */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void theFloorFollowsACurveThatEasesOffAFortyFiveDegreeSlope(GameTestHelper helper) {
		helper.setBlock(new BlockPos(16, 1, 20), CreateSlopeTests.slope(TrackShape.AN));
		AngledTrackAnchorBlockEntity dock = anchor(helper, new BlockPos(16, 2, 19));
		AngledTrackAnchorBlockEntity beyond = anchor(helper, new BlockPos(16, 6, 9));
		dock.linkTo(beyond, AngledTrackAnchorBlockEntity.tangentOf(NORTH, 1), AngledTrackAnchorBlockEntity.tangentOf(NORTH, 0),
			true, false);
		afterTheFloorIsLaid(helper, dock, "a curve easing off a 45 degree slope");
	}

	/** A curve that carries a 45 degree climb straight on, which is as steep as the rails get. */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void theFloorFollowsACurveThatKeepsClimbingAtFortyFiveDegrees(GameTestHelper helper) {
		helper.setBlock(new BlockPos(16, 1, 28), CreateSlopeTests.slope(TrackShape.AN));
		AngledTrackAnchorBlockEntity dock = anchor(helper, new BlockPos(16, 2, 27));
		AngledTrackAnchorBlockEntity beyond = anchor(helper, new BlockPos(16, 7, 21));
		dock.linkTo(beyond, AngledTrackAnchorBlockEntity.tangentOf(NORTH, 1), AngledTrackAnchorBlockEntity.tangentOf(NORTH, 1),
			true, false);
		afterTheFloorIsLaid(helper, dock, "a straight climb at 45 degrees");
	}

	/** A curve that arrives at the foot of a slope: the same from the other side, going down. */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void theFloorFollowsACurveThatDescendsToTheFootOfASlope(GameTestHelper helper) {
		helper.setBlock(new BlockPos(32, 5, 20), CreateSlopeTests.slope(TrackShape.AN));
		AngledTrackAnchorBlockEntity start = anchor(helper, new BlockPos(32, 1, 30));
		AngledTrackAnchorBlockEntity dock = anchor(helper, new BlockPos(32, 5, 21));
		start.linkTo(dock, AngledTrackAnchorBlockEntity.tangentOf(NORTH, 0), AngledTrackAnchorBlockEntity.tangentOf(NORTH, 1),
			false, true);
		afterTheFloorIsLaid(helper, start, "a curve rising to the foot of a slope");
	}

	/** A level quarter turn crosses its blocks at every angle, and has a floor under both rails all the way round. */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void aTurnHasAFloorUnderBothRails(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity from = anchor(helper, new BlockPos(4, 1, 26));
		AngledTrackAnchorBlockEntity to = anchor(helper, new BlockPos(20, 1, 10));
		from.linkTo(to, AngledTrackAnchorBlockEntity.tangentOf(EAST, 0), AngledTrackAnchorBlockEntity.tangentOf(NORTH, 0));
		afterTheFloorIsLaid(helper, from, "a level quarter turn");
	}

	/** The gentle curves this has always laid keep the floor they had. */
	@GameTest(template = EMPTY, timeoutTicks = 80)
	public static void aGentleClimbKeepsItsFloor(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity low = anchor(helper, new BlockPos(4, 1, 16));
		AngledTrackAnchorBlockEntity high = anchor(helper, new BlockPos(28, 5, 16));
		Vec3 climbing = AngledTrackAnchorBlockEntity.tangentOf(EAST, 4.0 / 24.0);
		low.linkTo(high, climbing, climbing);
		afterTheFloorIsLaid(helper, low, "a climb of a sixth");
	}

	/** A floor's heights are saved with the world and sent to the clients, who work out collision too. */
	@GameTest(template = EMPTY)
	public static void aFloorIsSavedAndSent(GameTestHelper helper) {
		BlockPos pos = new BlockPos(4, 2, 4);
		byte[] floor = new byte[CurveOccupancy.CELLS * CurveOccupancy.CELLS];
		for (int i = 0; i < floor.length; i++)
			floor[i] = i % 5 == 0 ? CurveOccupancy.EMPTY : (byte) i;
		CurveFillerBlock.placeOrRenew(helper.getLevel(), helper.absolutePos(pos), floor);
		helper.assertTrue(helper.getBlockEntity(pos) instanceof CurveFillerBlockEntity, "setup: there should be a filler");
		CurveFillerBlockEntity filler = (CurveFillerBlockEntity) helper.getBlockEntity(pos);
		helper.assertTrue(Arrays.equals(filler.floor(), floor), "the filler should hold the floor it was given");

		HolderLookup.Provider registries = helper.getLevel()
			.registryAccess();
		CurveFillerBlockEntity loaded = new CurveFillerBlockEntity(filler.getBlockPos(), filler.getBlockState());
		loaded.loadWithComponents(filler.saveWithFullMetadata(registries), registries);
		helper.assertTrue(Arrays.equals(loaded.floor(), floor), "and have it back when the world is loaded");
		CurveFillerBlockEntity sent = new CurveFillerBlockEntity(filler.getBlockPos(), filler.getBlockState());
		sent.loadWithComponents(filler.getUpdateTag(registries), registries);
		helper.assertTrue(Arrays.equals(sent.floor(), floor), "and send it to the clients");

		// A curve that moves renews the filler with a floor of a different shape.
		byte[] moved = floor.clone();
		moved[1] = 9;
		CurveFillerBlock.placeOrRenew(helper.getLevel(), helper.absolutePos(pos), moved);
		helper.assertTrue(Arrays.equals(filler.floor(), moved), "renewed, it should take the floor it is given now");
		helper.succeed();
	}

	// --------------------------------------------------------------- helpers

	private static void afterTheFloorIsLaid(GameTestHelper helper, AngledTrackAnchorBlockEntity owner, String what) {
		helper.runAfterDelay(FLOOR_TICKS, () -> {
			BezierConnection curve = owner.buildCurveToNext();
			helper.assertTrue(curve != null, "setup: " + what + " should have a curve");
			assertFloorFollowsRails(helper, curve, what);
			helper.succeed();
		});
	}

	/**
	 * Checks that at every point along {@code curve} there is a floor within {@link #REACH} of the rails, and that
	 * going from one point to the next the floor never rises more than {@link #WALKABLE_STEP}.
	 */
	private static void assertFloorFollowsRails(GameTestHelper helper, BezierConnection curve, String what) {
		Level level = helper.getLevel();
		int samples = Math.max(8, (int) (curve.getLength() * 4));
		List<String> bad = new ArrayList<>();
		double previous = Double.NaN;
		double worstGap = 0;
		double biggestStep = 0;
		for (int i = 0; i <= samples; i++) {
			Vec3 rail = curve.getPosition(i / (double) samples);
			// Under each rail as well as between them: the floor is the width of the track.
			Vec3 along = curve.getPosition(Math.min(1, (i + 1) / (double) samples))
				.subtract(curve.getPosition(Math.max(0, (i - 1) / (double) samples)));
			Vec3 across = new Vec3(-along.z, 0, along.x);
			across = across.lengthSqr() < 1.0E-8 ? Vec3.ZERO : across.normalize().scale(RAIL_GAUGE);
			for (Vec3 side : new Vec3[] { rail.add(across), rail.subtract(across) }) {
				double under = floorTopUnder(level, side);
				if (Double.isNaN(under) || Math.abs(under - side.y) > REACH)
					bad.add(String.format("(beside the rail, %.1f up, %.1f along: %s)", side.y - helper.absolutePos(BlockPos.ZERO)
						.getY(), side.z - helper.absolutePos(BlockPos.ZERO)
							.getZ(), Double.isNaN(under) ? "no floor" : String.format("floor %+.2f from it", under - side.y)));
			}
			double top = floorTopUnder(level, rail);
			double gap = Double.isNaN(top) ? Double.NaN : top - rail.y;
			if (Double.isNaN(top) || Math.abs(gap) > REACH)
				bad.add(String.format("(%.1f up, %.1f along: %s)", rail.y - helper.absolutePos(BlockPos.ZERO)
					.getY(), rail.z - helper.absolutePos(BlockPos.ZERO)
						.getZ(), Double.isNaN(top) ? "no floor" : String.format("floor %+.2f from the rail", gap)));
			else
				worstGap = Math.max(worstGap, Math.abs(gap));
			if (!Double.isNaN(top) && !Double.isNaN(previous))
				biggestStep = Math.max(biggestStep, top - previous);
			previous = top;
		}
		helper.assertTrue(bad.isEmpty(), what + ": the floor should be within " + REACH + " of the rails all along, but is not at "
			+ bad.size() + " of " + (samples + 1) + " points: " + bad.subList(0, Math.min(6, bad.size())));
		helper.assertTrue(biggestStep <= WALKABLE_STEP, what + ": the floor should never step up more than " + WALKABLE_STEP
			+ " from one point to the next, but steps up " + String.format("%.2f", biggestStep));
	}

	/**
	 * The top of the highest collision under the point, found the way an entity would find it: a thin box dropped
	 * from above the point until it lands. NaN where nothing holds it up within two blocks.
	 */
	private static double floorTopUnder(Level level, Vec3 point) {
		double start = point.y + 0.8;
		AABB probe = new AABB(point.x - 0.05, start, point.z - 0.05, point.x + 0.05, start + 0.1, point.z + 0.05);
		Iterable<VoxelShape> shapes = level.getBlockCollisions(null, probe.expandTowards(0, -2.5, 0));
		double drop = Shapes.collide(Direction.Axis.Y, probe, shapes, -2.5);
		return drop <= -2.5 + 1.0E-9 ? Double.NaN : start + drop;
	}
}
