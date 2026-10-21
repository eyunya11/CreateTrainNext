package com.eyunya.createtrainnext.gametest;

import java.util.ArrayList;
import java.util.List;

import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackShape;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Pins down what Create's own sloped track is, which is what a curve docking onto it has to meet.
 *
 * <p>None of this is this mod's code. It is here so that a Create that changes any of it shows up as a
 * failure here, before it shows up as a curve that stops a hair short of the slope or a train that will
 * not cross the joint: where a slope's two nodes are, that the joint between a slope and flat track laid
 * straight against it is one no train crosses — which is why a curve is wanted at all — and why a curve
 * leaving a slope is not allowed to turn.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class CreateSlopeTests {

	private static final String EMPTY = "empty";

	/** How many ticks Create takes to build a graph out of track that has just been placed. */
	private static final int GRAPH_TICKS = 8;

	private static final Vec3 UP = new Vec3(0, 1, 0);
	private static final Vec3 NORTH = new Vec3(0, 0, -1);
	private static final Vec3 EAST = new Vec3(1, 0, 0);

	/**
	 * A slope's axis climbs one block over one block of run, and its two nodes are half of that either
	 * side of a centre half a block up: the top one on the boundary it climbs towards, a block above the
	 * slope's floor, and the bottom one on the other boundary, at the floor. Both are on the half-block
	 * lattice and at a whole block's height, which is what lets an anchor share either of them.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 40)
	public static void aSlopeHasANodeAtTheTopOfOneFaceAndTheFootOfTheOther(GameTestHelper helper) {
		Level level = helper.getLevel();
		TrackShape[] shapes = { TrackShape.AN, TrackShape.AS, TrackShape.AE, TrackShape.AW };
		Vec3[] climbing = { NORTH, NORTH.scale(-1), EAST, EAST.scale(-1) };

		for (int i = 0; i < shapes.length; i++) {
			BlockPos relative = new BlockPos(8 + 8 * i, 2, 8);
			helper.setBlock(relative, slope(shapes[i]));
			BlockPos pos = helper.absolutePos(relative);
			BlockState state = level.getBlockState(pos);
			ITrackBlock track = (ITrackBlock) state.getBlock();

			List<Vec3> axes = track.getTrackAxes(level, pos, state);
			helper.assertTrue(axes.size() == 1, shapes[i] + " should report one axis, but reports " + axes);
			Vec3 axis = axes.get(0);
			Vec3 run = new Vec3(axis.x, 0, axis.z);
			helper.assertTrue(axis.y == 1 && run.subtract(climbing[i])
				.lengthSqr() < 1.0E-12, shapes[i] + " should climb one block per block towards " + climbing[i]
					+ ", but its axis is " + axis);

			Vec3 floor = Vec3.atBottomCenterOf(pos);
			Vec3 top = floor.add(run.scale(0.5))
				.add(UP);
			Vec3 bottom = floor.subtract(run.scale(0.5));
			List<Vec3> reported = new ArrayList<>();
			for (TrackNodeLocation location : track.getConnected(level, pos, state, false, null))
				reported.add(location.getLocation());

			helper.assertTrue(reported.size() == 2 && contains(reported, top) && contains(reported, bottom),
				shapes[i] + " should have its nodes at " + top + " and " + bottom + ", but reports " + reported);
		}
		helper.succeed();
	}

	/**
	 * Flat track laid straight up to the foot of a slope meets it at a node, and the two edges there differ
	 * in direction by the whole of the slope, 45 degrees. Create lets a train across a node only where they
	 * differ by less than about 29, so none does: getting onto a slope takes a curve that leaves it at its
	 * own angle and bends round to the level.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 60)
	public static void flatTrackLaidStraightAgainstASlopeIsAJointNoTrainCrosses(GameTestHelper helper) {
		BlockPos slopePos = new BlockPos(16, 2, 16);
		BlockPos flatPos = new BlockPos(16, 2, 17);
		helper.setBlock(slopePos, slope(TrackShape.AN));
		helper.setBlock(flatPos, AllBlocks.TRACK.get()
			.defaultBlockState()
			.setValue(TrackBlock.SHAPE, TrackShape.ZO));

		helper.runAfterDelay(GRAPH_TICKS, () -> {
			Level level = helper.getLevel();
			Vec3 floor = Vec3.atBottomCenterOf(helper.absolutePos(slopePos));
			Vec3 foot = floor.add(0, 0, 0.5);
			Vec3 south = foot.add(0, 0, 1);
			Vec3 top = floor.add(0, 1, -0.5);

			TrackGraph graph = Create.RAILWAYS.sided(level)
				.getGraph(level, location(level, foot));
			helper.assertTrue(graph != null, "the slope and the flat track should be in the graph by now");
			TrackNode footNode = graph.locateNode(location(level, foot));
			TrackNode southNode = graph.locateNode(location(level, south));
			TrackNode topNode = graph.locateNode(location(level, top));
			helper.assertTrue(footNode != null && southNode != null && topNode != null,
				"the joint and both far ends should be nodes of the graph");

			TrackEdge arriving = graph.getConnectionsFrom(southNode)
				.get(footNode);
			TrackEdge leaving = graph.getConnectionsFrom(footNode)
				.get(topNode);
			helper.assertTrue(arriving != null && leaving != null, "the flat track and the slope should each be an edge");

			double dot = arriving.getDirection(false)
				.dot(leaving.getDirection(true));
			helper.assertTrue(Math.abs(dot - Math.sqrt(0.5)) < 1.0E-3,
				"the two should differ by 45 degrees, a dot product of 0.707, but it is " + dot);
			helper.assertTrue(!arriving.canTravelTo(leaving), "a train should not be let across a 45 degree joint");
			helper.succeed();
		});
	}

	/**
	 * Why a curve off a slope may not turn. Create works a bezier's handles out with its axes made unit
	 * vectors in three dimensions, so the end that climbs at 45 degrees has the horizontal part of its
	 * handle shortened by cos 45 — and the reverse edge, worked out from the other end, measures the turn
	 * from there and comes out with longer handles at both ends. Off a slope into the level, a quarter turn
	 * of ten blocks is a different curve each way round by well over the tenth of a block the planner
	 * allows. Laid straight, both ways have the same handles and the curve is one curve; turning at the
	 * same climb all the way round is too, so it is the difference between the climbs at the two ends, and
	 * not the turn, that does it.
	 */
	@GameTest(template = EMPTY, timeoutTicks = 20)
	public static void aCurveThatTurnsOffASlopeIsADifferentCurveEachWayRound(GameTestHelper helper) {
		Vec3 from = Vec3.ZERO;

		BezierConnection turning = curve(from, new Vec3(10, 6, -10), NORTH, 1, EAST, 0);
		double turningOff = CurvePlanner.orientationDeviation(turning);
		helper.assertTrue(turningOff > CurvePlanner.MAX_ORIENTATION_DEVIATION,
			"a quarter turn from 45 degrees into the level should differ from itself read the other way, but is only "
				+ turningOff + " off");

		BezierConnection straight = curve(from, new Vec3(0, 5, -10), NORTH, 1, NORTH, 0);
		double straightOff = CurvePlanner.orientationDeviation(straight);
		helper.assertTrue(straightOff < 0.01,
			"the same climb laid straight should be one curve either way, but is " + straightOff + " off");

		BezierConnection steady = curve(from, new Vec3(10, 8, -10), NORTH, 0.5, EAST, 0.5);
		double steadyOff = CurvePlanner.orientationDeviation(steady);
		helper.assertTrue(steadyOff < 0.01,
			"a quarter turn at one climb all the way round should be one curve either way, but is " + steadyOff + " off");
		helper.succeed();
	}

	// --------------------------------------------------------------- helpers

	static BlockState slope(TrackShape shape) {
		return AllBlocks.TRACK.get()
			.defaultBlockState()
			.setValue(TrackBlock.SHAPE, shape);
	}

	private static TrackNodeLocation location(Level level, Vec3 where) {
		return new TrackNodeLocation(where).in(level);
	}

	private static boolean contains(List<Vec3> points, Vec3 wanted) {
		for (Vec3 point : points)
			if (point.distanceToSqr(wanted) < 1.0E-8)
				return true;
		return false;
	}

	/**
	 * A curve the way an anchor builds the one it owns: leaving {@code from} along {@code fromHeading}
	 * climbing at {@code fromSlope}, and arriving at {@code to} along {@code toHeading} climbing at
	 * {@code toSlope}, both in the direction of travel.
	 */
	private static BezierConnection curve(Vec3 from, Vec3 to, Vec3 fromHeading, double fromSlope, Vec3 toHeading,
		double toSlope) {
		return new BezierConnection(Couple.create(BlockPos.ZERO, BlockPos.ZERO), Couple.create(from, to),
			Couple.create(AngledTrackAnchorBlockEntity.tangentOf(fromHeading, fromSlope),
				AngledTrackAnchorBlockEntity.tangentOf(toHeading, toSlope)
					.scale(-1)),
			Couple.create(UP, UP), true, false, TrackMaterial.ANDESITE);
	}
}
