package com.eyunya.createtrainnext.gametest;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AnchorAxis;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlock;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks on how routes are drawn, run against a real server level rather than read off the code.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}. Each test builds what it needs out of anchors
 * directly, which is the same state the wand leaves behind, and then asks the planner the question
 * the wand would ask — so what is checked is the decision, not the clicking.
 *
 * <p>The one template is an empty box: nothing here needs ground, only room, and
 * {@link PrefixGameTestTemplate} keeps its name from being prefixed with the class's.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class RouteTests {

	private static final String EMPTY = "empty";

	/** South, which is yaw 0: the way an anchor points when it has just been placed on Z_ORTHO. */
	private static final Vec3 SOUTH = new Vec3(0, 0, 1);

	/**
	 * A route's first anchor has nothing behind it, and a curve can be drawn out of that side.
	 *
	 * <p>This is the case "carry on backwards out of the anchor a route was started from". The
	 * planner has handled it since the free side stopped being tied to which click came first, but
	 * the two checks in front of it — {@code canStartFrom} and the wand's own refusal — still asked
	 * whether the anchor's <em>next</em> was free, so an anchor like this one could be the second
	 * click of a curve and never the first.
	 */
	@GameTest(template = EMPTY)
	public static void startsFromAnOriginAnchor(GameTestHelper helper) {
		BlockPos origin = new BlockPos(24, 2, 20);
		BlockPos ahead = new BlockPos(24, 2, 28);
		BlockPos target = new BlockPos(24, 2, 8);

		AngledTrackAnchorBlockEntity first = anchor(helper, origin);
		AngledTrackAnchorBlockEntity second = anchor(helper, ahead);
		first.linkTo(second, SOUTH, SOUTH);

		Level level = helper.getLevel();
		BlockPos originPos = helper.absolutePos(origin);
		BlockPos targetPos = helper.absolutePos(target);

		helper.assertTrue(first.getNext() != null && first.getPrev() == null,
			"setup: the origin anchor should have a curve ahead and nothing behind");

		helper.assertTrue(CurvePlanner.canStartFrom(level, originPos),
			"an anchor with its prev free should be somewhere a curve can start");

		// The target is behind the origin, so the only way to reach it is out of the free side.
		CurvePlanner.Plan plan = CurvePlanner.plan(level, originPos, targetPos, Direction.UP, 0);
		helper.assertTrue(plan.valid(), "the curve should be buildable, but was refused: " + plan.error());
		helper.assertTrue(!plan.forward(), "a curve out of the free prev side should be drawn backwards");

		CurvePlanner.apply(level, plan);

		BlockEntity built = level.getBlockEntity(targetPos);
		helper.assertTrue(built instanceof AngledTrackAnchorBlockEntity,
			"an anchor should have been placed where the curve ends");
		AngledTrackAnchorBlockEntity end = (AngledTrackAnchorBlockEntity) built;
		helper.assertTrue(originPos.equals(end.getNext()), "the new anchor should lead into the origin");
		helper.assertTrue(targetPos.equals(first.getPrev()), "the origin should now arrive from the new anchor");

		// The wand carries its selection on to the end it just reached, so that end has to be
		// somewhere a curve can start as well, or a route drawn backwards stops after one curve.
		helper.assertTrue(CurvePlanner.canStartFrom(level, targetPos),
			"the end just reached should still have a free side to carry on from");

		BlockPos furtherPos = helper.absolutePos(new BlockPos(24, 2, 1));
		CurvePlanner.Plan onward = CurvePlanner.plan(level, targetPos, furtherPos, Direction.UP, 0);
		helper.assertTrue(onward.valid() && !onward.forward(),
			"the route should carry on backwards, but was refused: " + onward.error());
		CurvePlanner.apply(level, onward);
		helper.assertTrue(furtherPos.equals(end.getPrev()), "the route should now arrive from the further anchor");

		helper.succeed();
	}

	// --------------------------------------------------------------- helpers

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
}
