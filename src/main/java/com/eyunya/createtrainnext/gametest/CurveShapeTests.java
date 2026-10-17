package com.eyunya.createtrainnext.gametest;

import java.util.ArrayList;
import java.util.List;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AnchorAxis;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlock;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.Biarc;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;

import net.createmod.catnip.data.Couple;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks on the shape of a curve between two anchors, and on how it is read the other way round.
 *
 * <p>Nothing here needs a world, only Create's own classes and the arithmetic, so the tests build
 * curves directly. Angles are in the plane of x and z, east at zero and growing towards south: a
 * heading of -90 is north.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class CurveShapeTests {

	private static final String EMPTY = "empty";

	private static final Vec3 UP = new Vec3(0, 1, 0);

	/** Where the start of every curve here is, far from anything. */
	private static final Vec3 START = new Vec3(100.5, 64, 100.5);

	private static Vec3 heading(double degrees) {
		double radians = Math.toRadians(degrees);
		return new Vec3(Math.cos(radians), 0, Math.sin(radians));
	}

	private static Vec3 end(double dx, double dz) {
		return START.add(dx, 0, dz);
	}

	/** The curve between two points as the wand builds it, tangents pointing into it. */
	private static BezierConnection curve(Vec3 from, Vec3 fromHeading, Vec3 to, Vec3 toHeading) {
		return new BezierConnection(Couple.create(BlockPos.ZERO, BlockPos.ZERO), Couple.create(from, to),
			Couple.create(fromHeading, toHeading.scale(-1)), Couple.create(UP, UP), true, false,
			TrackMaterial.ANDESITE);
	}

	/**
	 * A curve whose tangents make equal angles with its chord is the same curve read either way.
	 *
	 * <p>East at both ends of a chord running 26.6 degrees north of east: 26.6 degrees to one side of
	 * the chord at the start, and the same at the end. This is what every curve Create places is, and
	 * so it has never had to know about the rest.
	 */
	@GameTest(template = EMPTY)
	public static void aCurveWithEqualAnglesAtItsEndsReadsTheSameEitherWay(GameTestHelper helper) {
		BezierConnection curve = curve(START, heading(0), end(20, -10), heading(0));

		double deviation = CurvePlanner.orientationDeviation(curve);
		helper.assertTrue(deviation < 0.005, "the curve should be the same either way round, but differs by " + deviation);

		helper.succeed();
	}

	/**
	 * Any other curve is not, and the difference is large.
	 *
	 * <p>This is a fact about Create, not about this mod, and is here so that the day Create changes
	 * how it works out a bezier's handles this says so: the length of the handles comes from the
	 * distance of the <em>second</em> end to where the normals cross, which is not the distance of the
	 * first.
	 */
	@GameTest(template = EMPTY)
	public static void aCurveWithUnequalAnglesAtItsEndsIsADifferentCurveReadTheOtherWay(GameTestHelper helper) {
		BezierConnection curve = curve(START, heading(0), end(20, -10), heading(-90));

		double deviation = CurvePlanner.orientationDeviation(curve);
		helper.assertTrue(deviation > 2.0,
			"east into north should be a different curve read the other way, but differs by only " + deviation);

		helper.succeed();
	}

	/**
	 * Two arcs can join any two tangents, and each is the same curve either way.
	 *
	 * <p>Each case is a curve from a start pointing east to an end pointing somewhere else, most of
	 * them with unequal angles and so a different curve each way: by up to nine blocks, in the last of
	 * them. The junction is found on a block, and neither arc is more than a tenth of a block from
	 * itself read the other way.
	 */
	@GameTest(template = EMPTY)
	public static void aBiarcMakesBothHalvesReadTheSameEitherWay(GameTestHelper helper) {
		// dx, dz, and the heading at the far end
		double[][] cases = { { 20, -10, -90 }, { 20, -10, -45 }, { 20, -10, 0 }, { 12, -12, -90 }, { 30, -6, -90 },
			{ 16, 8, 90 }, { 24, 0, 0 }, { 24, 4, 0 }, { 18, -9, -135 }, { 10, -10, 180 } };

		for (double[] c : cases) {
			Vec3 end = end(c[0], c[1]);
			Vec3 endHeading = heading(c[2]);
			String what = "east to (" + c[0] + ", " + c[1] + ") heading " + c[2];

			Biarc.Junction junction = Biarc.find(START, heading(0), end, endHeading, pos -> true);
			helper.assertTrue(junction != null, what + " should have somewhere to put a junction");

			helper.assertTrue(junction.point()
				.equals(Vec3.atBottomCenterOf(junction.pos())), what + ": the junction should be the centre of its block");

			double first = CurvePlanner.orientationDeviation(curve(START, heading(0), junction.point(), junction.heading()));
			double second = CurvePlanner.orientationDeviation(curve(junction.point(), junction.heading(), end, endHeading));
			helper.assertTrue(first <= CurvePlanner.MAX_ORIENTATION_DEVIATION,
				what + ": the first arc should read the same either way, but differs by " + first);
			helper.assertTrue(second <= CurvePlanner.MAX_ORIENTATION_DEVIATION,
				what + ": the second arc should read the same either way, but differs by " + second);
		}

		helper.succeed();
	}

	/**
	 * The command that finds such curves in a world finds one, and says which.
	 *
	 * <p>Two short routes laid at right angles and joined: east into north, which is a different
	 * curve read the other way by nearly three blocks.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void theCurvesCommandFindsACurveThatDiffersByDirection(GameTestHelper helper) {
		BlockPos start = new BlockPos(10, 2, 26);
		BlockPos end = new BlockPos(30, 2, 16);

		AngledTrackAnchorBlockEntity before = anchor(helper, new BlockPos(2, 2, 26));
		AngledTrackAnchorBlockEntity first = anchor(helper, start);
		AngledTrackAnchorBlockEntity second = anchor(helper, end);
		AngledTrackAnchorBlockEntity after = anchor(helper, new BlockPos(30, 2, 8));

		Vec3 east = AngledTrackAnchorBlockEntity.directionFromYaw(270.0F);
		Vec3 north = AngledTrackAnchorBlockEntity.directionFromYaw(180.0F);
		before.linkTo(first, east, east);
		second.linkTo(after, north, north);
		first.linkTo(second, null, null);

		// Curves are found through an index of the anchors that are loaded, and a block entity only
		// reaches it a few ticks after it is placed.
		helper.runAfterDelay(5, () -> {
			ServerLevel level = helper.getLevel();
			List<String> said = new ArrayList<>();
			CommandSource sink = new CommandSource() {
				@Override
				public void sendSystemMessage(Component message) {
					said.add(message.getString());
				}

				@Override
				public boolean acceptsSuccess() {
					return true;
				}

				@Override
				public boolean acceptsFailure() {
					return true;
				}

				@Override
				public boolean shouldInformAdmins() {
					return false;
				}
			};
			CommandSourceStack source = new CommandSourceStack(sink, Vec3.atCenterOf(helper.absolutePos(start)), Vec2.ZERO,
				level, 2, "test", Component.literal("test"), level.getServer(), null);

			level.getServer()
				.getCommands()
				.performPrefixedCommand(source, "createtrainnext curves 100");

			String link = helper.absolutePos(start)
				.toShortString() + " -> "
				+ helper.absolutePos(end)
					.toShortString();
			helper.assertTrue(said.stream()
				.anyMatch(line -> line.contains("!!") && line.contains(link)),
				"the command should have flagged " + link + ", but said " + said);

			helper.succeed();
		});
	}

	private static AngledTrackAnchorBlockEntity anchor(GameTestHelper helper, BlockPos relative) {
		helper.setBlock(relative, CTNRegistry.ANGLED_TRACK_ANCHOR.get()
			.defaultBlockState()
			.setValue(AngledTrackAnchorBlock.AXIS, AnchorAxis.Z_ORTHO));
		return (AngledTrackAnchorBlockEntity) helper.getBlockEntity(relative);
	}

	/** An anchor cannot go where something is, and a junction there is not offered. */
	@GameTest(template = EMPTY)
	public static void aBiarcIsNotOfferedWhereNothingMayBePlaced(GameTestHelper helper) {
		Biarc.Junction junction = Biarc.find(START, heading(0), end(20, -10), heading(-90), pos -> false);
		helper.assertTrue(junction == null, "with no block free there should be no junction");

		helper.succeed();
	}

	/**
	 * A climb is shared out between the two arcs, and neither may be steeper than Create allows.
	 *
	 * <p>Six blocks up over a run of about twenty-two, so each arc has to keep to the rule that a
	 * rise needs three times as much run, which a junction at the wrong height would break.
	 */
	@GameTest(template = EMPTY)
	public static void aBiarcSharesOutAClimb(GameTestHelper helper) {
		Vec3 end = end(20, -10).add(0, 6, 0);

		Biarc.Junction junction = Biarc.find(START, heading(0), end, heading(-90), pos -> true);
		helper.assertTrue(junction != null, "a climb should have somewhere to put a junction");

		double rise = junction.pos()
			.getY() - START.y;
		helper.assertTrue(rise > 0 && rise < 6, "the junction should be part of the way up, but is " + rise + " above the start");

		helper.succeed();
	}
}
