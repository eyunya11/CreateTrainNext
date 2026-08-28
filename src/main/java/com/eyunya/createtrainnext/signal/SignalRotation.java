package com.eyunya.createtrainnext.signal;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Turning a signal head, in sixteen steps of 22.5 degrees.
 *
 * <p>Shared by the five-aspect head and the relay because they turn identically, and separate from
 * the speed limit sign's own copy of this arithmetic for one reason worth being loud about:
 * <b>segment 0 here is south, and segment 0 there is north.</b> Both are "the model as authored,
 * unturned", and the two models were simply drawn facing opposite ways — the signals put their
 * lamps on +z. Every formula below is written for south; none of them can be lifted across without
 * checking, and the two {@code rotation} properties are deliberately separate objects so that a
 * block cannot accidentally be given the wrong one.
 *
 * <p>Sixteen angles is also why a signal's head has to be drawn by its block entity renderer rather
 * than by its blockstate. A variant's {@code y} only turns in quarter turns; the 22.5 that would
 * make up the difference cannot be an element rotation, because the housings already rotate their
 * elements about z and an element gets one rotation, and cannot be baked into coordinates either,
 * because a 22.5 degree turn does not leave an axis aligned box axis aligned. The mast is the part
 * that never turns, so that stays a blockstate model.
 */
public class SignalRotation {

	/** Sixteen positions around the circle, one every 22.5 degrees. */
	public static final int SEGMENTS = 16;

	/** Deliberately floating point: 360 over 16 is 22.5, and integer division would make it 22. */
	public static final float DEGREES_PER_SEGMENT = 360F / SEGMENTS;

	/**
	 * Which of the sixteen a head is turned to. Segment 0 is the model as authored, facing south,
	 * and segments run clockwise seen from above, so 4 is west.
	 */
	public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, SEGMENTS - 1);

	/**
	 * Degrees to turn the head by, in the sense a blockstate variant's {@code y} means: clockwise
	 * seen from above.
	 *
	 * <p>The renderers measure the lamps from here as well as the housing, so the lamps cannot drift
	 * off the head they sit on.
	 */
	public static float modelYaw(BlockState state) {
		return state.getValue(ROTATION) * DEGREES_PER_SEGMENT;
	}

	/**
	 * The segment that turns a head to face whoever is placing it.
	 *
	 * <p>A signal is read from in front, so it wants to end up looking back at the player rather
	 * than the way they are looking — hence the half turn. Minecraft measures a player's yaw
	 * clockwise from due south, which is the same zero and the same direction as a segment, so the
	 * rest is just rounding.
	 */
	public static int segmentForPlacement(BlockPlaceContext context) {
		int lookingAway = Math.round(context.getRotation() / DEGREES_PER_SEGMENT);
		return Math.floorMod(lookingAway + SEGMENTS / 2, SEGMENTS);
	}

	/** One segment on, or back if {@code reverse}. What an empty hand does to a head. */
	public static int nudge(int segment, boolean reverse) {
		return Math.floorMod(segment + (reverse ? -1 : 1), SEGMENTS);
	}

	public static int rotate(int segment, Rotation rotation) {
		int quarter = SEGMENTS / 4;
		int segments = switch (rotation) {
			case CLOCKWISE_90 -> quarter;
			case CLOCKWISE_180 -> quarter * 2;
			case COUNTERCLOCKWISE_90 -> quarter * 3;
			default -> 0;
		};
		return (segment + segments) % SEGMENTS;
	}

	public static int mirror(int segment, Mirror mirror) {
		// Spelled out rather than deferred to Mirror#mirror(int, int), whose arithmetic is written
		// for vanilla's convention of segment 0 pointing south... which is in fact this one. It is
		// still spelled out, because that is a coincidence of this file rather than something the
		// helper promises, and the speed limit sign next door proves the convention is not universal.
		int mirrored = switch (mirror) {
			// Flips the z axis: north and south swap, east and west stay put.
			case LEFT_RIGHT -> SEGMENTS / 2 - segment;
			// Flips the x axis, so it is north and south that stay put.
			case FRONT_BACK -> -segment;
			default -> segment;
		};
		return Math.floorMod(mirrored, SEGMENTS);
	}

	/**
	 * The cardinal direction a head most nearly faces.
	 *
	 * <p>For the questions that only have four answers — which side a pole may join from — where
	 * rounding to the nearest quarter is the whole of what is wanted.
	 */
	public static Direction nearestFacing(BlockState state) {
		int quarter = Math.floorMod(Math.round(state.getValue(ROTATION) / (SEGMENTS / 4F)), 4);
		return Direction.from2DDataValue(quarter);
	}

	/**
	 * The smallest box holding a part of the model once the head has been turned by {@code yaw}.
	 *
	 * <p>An approximation by necessity — a voxel shape is axis aligned and a turned head is not — so
	 * between the axes the box is a little wider than what is inside it. Bounding rather than
	 * inscribing is the right way to be wrong: the head stays clickable everywhere it is visible.
	 *
	 * <p>Clamped to the block, which matters for the offset heads: those hang into the block next
	 * door by design, and a shape reaching outside its own block is not reliably hit tested.
	 */
	public static VoxelShape spin(double x0, double y0, double z0, double x1, double y1, double z1, float yaw) {
		double radians = Math.toRadians(yaw);
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

		for (double x : new double[] { x0, x1 })
			for (double z : new double[] { z0, z1 }) {
				// Measured from the block's own middle, which is what the head turns about.
				double u = x - 8;
				double v = z - 8;
				double turnedX = 8 + u * cos - v * sin;
				double turnedZ = 8 + u * sin + v * cos;
				minX = Math.min(minX, turnedX);
				maxX = Math.max(maxX, turnedX);
				minZ = Math.min(minZ, turnedZ);
				maxZ = Math.max(maxZ, turnedZ);
			}

		return Block.box(clamp(minX), clamp(y0), clamp(minZ), clamp(maxX), clamp(y1), clamp(maxZ));
	}

	private static double clamp(double value) {
		return Mth.clamp(value, 0, 16);
	}

	private SignalRotation() {}
}
