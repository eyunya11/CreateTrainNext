package com.eyunya.createtrainnext.gametest;

import java.util.List;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AnchorAxis;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlock;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks that a curve the wand builds is the same curve whichever way it is read, against the graph
 * Create actually makes from it.
 *
 * <p>Two anchors that already point somewhere cannot be joined by one bezier that is the same both
 * ways — see {@link CurvePlanner#MAX_ORIENTATION_DEVIATION} — so the wand builds two arcs and an
 * anchor where they meet. What matters in the end is not the plan but the graph: after the anchors
 * are built and Create has had its few ticks to walk them, the edge for each direction of travel has
 * to lie on the curve that is drawn. Each test ends by asking exactly that of every link in the
 * route.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class CurveConsistencyTests {

	private static final String EMPTY = "empty";

	static final float EAST = 270.0F;
	static final float NORTH = 180.0F;
	static final float WEST = 90.0F;

	/** How many ticks Create takes to build a graph out of anchors that have just been linked. */
	static final int GRAPH_TICKS = 8;

	static final BlockPos BEFORE = new BlockPos(2, 2, 26);
	static final BlockPos START = new BlockPos(10, 2, 26);
	static final BlockPos END = new BlockPos(30, 2, 16);
	static final BlockPos AFTER = new BlockPos(30, 2, 8);

	/**
	 * East into north: the kind of join the wand used to build as one curve, and which was nearly
	 * three blocks off from itself read the other way.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void anchorsPointingDifferentWaysAreJoinedByTwoArcs(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, EAST, BEFORE, START);
		route(helper, NORTH, END, AFTER);
		Level level = helper.getLevel();

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(START), helper.absolutePos(END),
			Direction.UP, 0);
		helper.assertTrue(plan.valid(), "the join should be possible, but was refused: " + plan.error());
		helper.assertTrue(plan.junction() != null && plan.curves()
			.size() == 2, "it should be built as two arcs with a point between them");
		helper.assertTrue(Math.abs(plan.length() - plan.curves()
			.get(0)
			.getLength() - plan.curves()
				.get(1)
				.getLength()) < 1.0E-9,
			"the length of the plan should be the length of both arcs");

		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the join should have been built");

		BlockPos middle = relative(helper, plan.junction()
			.pos());
		assertChain(helper, first[0], BEFORE, START, middle, END, AFTER);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, first[0]);
			helper.succeed();
		});
	}

	/**
	 * The same join from the other side: two routes whose first anchors face one another, which is
	 * drawn backwards and has the far route turned round on the way.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void aJoinDrawnBackwardsIsJoinedByTwoArcsToo(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, WEST, START, BEFORE);
		AngledTrackAnchorBlockEntity[] second = route(helper, NORTH, END, AFTER);
		Level level = helper.getLevel();

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(START), helper.absolutePos(END),
			Direction.UP, 0);
		helper.assertTrue(plan.valid(), "the join should be possible, but was refused: " + plan.error());
		helper.assertTrue(plan.reverseEnd() && !plan.forward(),
			"it should be drawn backwards, with the far route turned round");
		helper.assertTrue(plan.junction() != null, "it should be built as two arcs with a point between them");

		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the join should have been built");

		BlockPos middle = relative(helper, plan.junction()
			.pos());
		assertChain(helper, second[1], AFTER, END, middle, START, BEFORE);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, second[1]);
			helper.succeed();
		});
	}

	/**
	 * Nothing is added where one curve would already do: east to east, with the same angle to the
	 * chord at both ends.
	 */
	@GameTest(template = EMPTY)
	public static void aJoinWithEqualAnglesIsLeftAsOneCurve(GameTestHelper helper) {
		route(helper, EAST, BEFORE, START);
		route(helper, EAST, END, new BlockPos(38, 2, 16));
		Level level = helper.getLevel();

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(START), helper.absolutePos(END),
			Direction.UP, 0);
		helper.assertTrue(plan.valid(), "the join should be possible, but was refused: " + plan.error());
		helper.assertTrue(plan.junction() == null && plan.curves()
			.size() == 1, "one curve is the same curve either way here, so no point should be added");

		helper.succeed();
	}

	/**
	 * A curve turned by hand is the other way to make one that differs, and gets the same answer.
	 *
	 * <p>The far end is open ground, which the wand would aim at the arc by itself; turning it forty
	 * degrees away is what makes the angles unequal.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void turningTheFarEndByHandIsBuiltAsTwoArcs(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, EAST, BEFORE, START);
		Level level = helper.getLevel();

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(START), helper.absolutePos(END),
			Direction.UP, -40.0F);
		helper.assertTrue(plan.valid(), "the curve should be possible, but was refused: " + plan.error());
		helper.assertTrue(plan.junction() != null, "a far end turned by hand should be built as two arcs");

		helper.assertTrue(CurvePlanner.apply(level, plan) == null, "the curve should have been built");

		BlockPos middle = relative(helper, plan.junction()
			.pos());
		assertChain(helper, first[0], BEFORE, START, middle, END);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, first[0]);
			helper.succeed();
		});
	}

	/**
	 * Where there is nowhere to put the anchor, the curve is refused rather than built as one that
	 * would be two different shapes.
	 *
	 * <p>The whole layer the junction could go in is filled with stone, and what is refused is shown
	 * as the single curve, so the ghost still says where the player was aiming.
	 */
	@GameTest(template = EMPTY)
	public static void aJoinWithNowhereForTheAnchorIsRefused(GameTestHelper helper) {
		for (int x = 0; x < 48; x++)
			for (int z = 0; z < 32; z++)
				helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);

		route(helper, EAST, BEFORE, START);
		route(helper, NORTH, END, AFTER);
		Level level = helper.getLevel();

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(START), helper.absolutePos(END),
			Direction.UP, 0);
		helper.assertTrue("uneven".equals(plan.error()), "the join should be refused as uneven, not " + plan.error());
		helper.assertTrue(plan.curves()
			.size() == 1, "the single curve should be kept so that it can be shown");

		helper.succeed();
	}

	/**
	 * The control for all of the above: the check does find the fault when there is one.
	 *
	 * <p>The two anchors are linked directly, the way the wand used to join them, as one curve. The
	 * edge along the route lies on the curve that is drawn and the edge against it does not: the
	 * graph is built from {@code secondary()} of the curve for the way back, and Create works that out
	 * from the other end. If this ever stops being so, the rest of this class has stopped proving
	 * anything.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void oneCurveBetweenAnchorsPointingDifferentWaysIsADifferentCurveEachWay(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, EAST, BEFORE, START);
		AngledTrackAnchorBlockEntity[] second = route(helper, NORTH, END, AFTER);
		first[1].linkTo(second[0], null, null);

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			Level level = helper.getLevel();
			BezierConnection drawn = first[1].buildCurveToNext();
			TrackNodeLocation fromLocation = new TrackNodeLocation(first[1].exitNode()).in(level);
			TrackNodeLocation toLocation = new TrackNodeLocation(second[0].entryNode()).in(level);
			TrackGraph graph = Create.RAILWAYS.sided(level)
				.getGraph(level, fromLocation);
			helper.assertTrue(graph != null && graph.locateNode(fromLocation) != null
				&& graph.locateNode(toLocation) != null, "the link should be in the graph by now");

			TrackNode fromNode = graph.locateNode(fromLocation);
			TrackNode toNode = graph.locateNode(toLocation);
			double along = CurvePlanner.deviation(drawn, graph.getConnectionsFrom(fromNode)
				.get(toNode)
				.getTurn());
			double against = CurvePlanner.deviation(drawn, graph.getConnectionsFrom(toNode)
				.get(fromNode)
				.getTurn());

			helper.assertTrue(along <= CurvePlanner.MAX_ORIENTATION_DEVIATION,
				"the edge along the route should lie on the drawn curve, but is " + along + " off");
			helper.assertTrue(against > 1.0,
				"the edge against the route should be well away from the drawn curve, but is only " + against + " off");
			helper.succeed();
		});
	}

	/** Through the wand, the same two clicks build the same thing, and the anchor is placed as well. */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void theWandBuildsTheAnchorBetweenTheArcs(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] first = route(helper, EAST, BEFORE, START);
		route(helper, NORTH, END, AFTER);
		Level level = helper.getLevel();

		CurvePlanner.Plan plan = CurvePlanner.plan(level, helper.absolutePos(START), helper.absolutePos(END),
			Direction.UP, 0);
		helper.assertTrue(plan.junction() != null, "setup: the join should have a point between its arcs");
		BlockPos middle = relative(helper, plan.junction()
			.pos());

		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		ItemStack wand = new ItemStack(CTNRegistry.CURVE_WAND.get());
		helper.assertTrue(click(helper, player, wand, START).consumesAction(), "the first click should pick the start");
		InteractionResult built = click(helper, player, wand, END);
		helper.assertTrue(built.consumesAction(), "the second click should have built it, but gave " + built);

		assertChain(helper, first[0], BEFORE, START, middle, END, AFTER);
		helper.assertTrue(helper.getBlockState(middle)
			.is(CTNRegistry.ANGLED_TRACK_ANCHOR.get()), "an anchor should have been placed between the arcs");

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			assertConsistent(helper, first[0]);
			helper.succeed();
		});
	}

	// --------------------------------------------------------------- helpers

	/** Where an absolute position is, in the frame of the test. */
	static BlockPos relative(GameTestHelper helper, BlockPos absolute) {
		return absolute.subtract(helper.absolutePos(BlockPos.ZERO));
	}

	static AngledTrackAnchorBlockEntity anchor(GameTestHelper helper, BlockPos relative) {
		helper.setBlock(relative, CTNRegistry.ANGLED_TRACK_ANCHOR.get()
			.defaultBlockState()
			.setValue(AngledTrackAnchorBlock.AXIS, AnchorAxis.Z_ORTHO));
		BlockEntity entity = helper.getBlockEntity(relative);
		helper.assertTrue(entity instanceof AngledTrackAnchorBlockEntity,
			"no anchor block entity where one was placed at " + relative);
		return (AngledTrackAnchorBlockEntity) entity;
	}

	/** Links the given positions in order into one route, every anchor pointed along {@code yaw}. */
	static AngledTrackAnchorBlockEntity[] route(GameTestHelper helper, float yaw, BlockPos... positions) {
		AngledTrackAnchorBlockEntity[] anchors = new AngledTrackAnchorBlockEntity[positions.length];
		for (int i = 0; i < anchors.length; i++)
			anchors[i] = anchor(helper, positions[i]);

		Vec3 heading = AngledTrackAnchorBlockEntity.directionFromYaw(yaw);
		for (int i = 0; i + 1 < anchors.length; i++)
			anchors[i].linkTo(anchors[i + 1], i == 0 ? heading : null, heading);
		return anchors;
	}

	/** Checks that the route through {@code member} is the anchors at {@code expected}, in order. */
	static void assertChain(GameTestHelper helper, AngledTrackAnchorBlockEntity member, BlockPos... expected) {
		List<AngledTrackAnchorBlockEntity> route = member.route();
		helper.assertTrue(route != null, "the route should be one that can be walked in full");
		helper.assertTrue(route.size() == expected.length,
			"the route should have " + expected.length + " anchors, but has " + route.size());
		for (int i = 0; i < expected.length; i++)
			helper.assertTrue(route.get(i)
				.getBlockPos()
				.equals(helper.absolutePos(expected[i])), "anchor " + i + " of the route should be at " + expected[i]);
	}

	/**
	 * Checks every link in the route through {@code member} against the graph Create built: the edge
	 * for each direction of travel has to lie on the curve that is drawn, and be as long as the other.
	 */
	static void assertConsistent(GameTestHelper helper, AngledTrackAnchorBlockEntity member) {
		Level level = helper.getLevel();
		List<AngledTrackAnchorBlockEntity> route = member.route();
		helper.assertTrue(route != null, "the route should be one that can be walked in full");

		for (int i = 0; i + 1 < route.size(); i++) {
			AngledTrackAnchorBlockEntity from = route.get(i);
			AngledTrackAnchorBlockEntity to = route.get(i + 1);
			String link = "link " + i + " (" + from.getBlockPos()
				.toShortString() + " -> "
				+ to.getBlockPos()
					.toShortString()
				+ ")";

			BezierConnection drawn = from.buildCurveToNext();
			helper.assertTrue(drawn != null, link + " should have a curve to draw");

			TrackNodeLocation fromLocation = new TrackNodeLocation(from.exitNode()).in(level);
			TrackNodeLocation toLocation = new TrackNodeLocation(to.entryNode()).in(level);
			TrackGraph graph = Create.RAILWAYS.sided(level)
				.getGraph(level, fromLocation);
			helper.assertTrue(graph != null && graph.locateNode(fromLocation) != null
				&& graph.locateNode(toLocation) != null, link + " should be in the graph by now");

			TrackNode fromNode = graph.locateNode(fromLocation);
			TrackNode toNode = graph.locateNode(toLocation);
			TrackEdge forward = graph.getConnectionsFrom(fromNode)
				.get(toNode);
			TrackEdge back = graph.getConnectionsFrom(toNode)
				.get(fromNode);
			helper.assertTrue(forward != null && back != null, link + " should be an edge in both directions");

			double forwardOff = CurvePlanner.deviation(drawn, forward.getTurn());
			double backOff = CurvePlanner.deviation(drawn, back.getTurn());
			helper.assertTrue(forwardOff <= CurvePlanner.MAX_ORIENTATION_DEVIATION,
				link + ": the edge along the route should lie on the drawn curve, but is " + forwardOff + " off");
			helper.assertTrue(backOff <= CurvePlanner.MAX_ORIENTATION_DEVIATION,
				link + ": the edge against the route should lie on the drawn curve, but is " + backOff + " off");
			helper.assertTrue(Math.abs(forward.getLength() - back.getLength()) < 0.05,
				link + ": the two edges should be as long as one another, but are " + forward.getLength() + " and "
					+ back.getLength());
		}
	}

	private static InteractionResult click(GameTestHelper helper, Player player, ItemStack wand, BlockPos relative) {
		BlockPos pos = helper.absolutePos(relative);
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
		return CTNRegistry.CURVE_WAND.get()
			.useOn(new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, wand, hit));
	}
}
