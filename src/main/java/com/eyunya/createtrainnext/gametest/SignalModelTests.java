package com.eyunya.createtrainnext.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import static com.eyunya.createtrainnext.gametest.SignalTypeTests.json;

import com.eyunya.createtrainnext.signal.MultiAspectSignalBlock;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlock.HorizontalOffset;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlockEntity;
import com.eyunya.createtrainnext.signal.SignalType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks that the numbers in {@link SignalType} and {@link MultiAspectSignalBlock} are the ones in the
 * models they describe, and that a signal taller than its block leaves the block above free.
 *
 * <p>The models are drawn by hand and the code has to be told where things are in them: how high each
 * lamp's socket is, how tall the housing is and so how big a box it makes. A model redrawn without the
 * numbers following it still loads and still draws, with the lamps off the sockets and the box the wrong
 * size, and nothing but looking at it in the game says so. These read the model files and say so.
 *
 * <p>The tables of numbers are written out here a second time on purpose, as in {@link SignalTypeTests}.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class SignalModelTests {

	private static final String EMPTY = "empty";

	/** How close a measurement of a model has to be: pixels, as the models are drawn in. */
	private static final double WITHIN = 0.05;

	/** Bottom of the body, top of the body and pole, then the height of each lamp's socket, top lamp first. */
	private static final Map<SignalType, double[]> HOUSING = Map.of(
		SignalType.TWO, new double[] { 3.5, 12.5, 9, 6.75 },
		SignalType.THREE, new double[] { 2, 14, 10.25, 8, 5.75 },
		SignalType.FOUR, new double[] { 1, 15, 11.25, 9, 6.75, 4.5 },
		SignalType.FIVE, new double[] { 0, 16, 12.25, 10, 7.75, 5.5, 3.25 },
		SignalType.SIX, new double[] { 0, 18.25, 14.5, 12.25, 10, 7.75, 5.5, 3.25 });

	@GameTest(template = EMPTY)
	public static void eachTypeHasTheHousingAndSocketsItIsToldItHas(GameTestHelper helper) {
		for (SignalType type : SignalType.values()) {
			double[] expected = HOUSING.get(type);
			helper.assertTrue(type.lampCount() == expected.length - 2, type + " should have " + (expected.length - 2) + " lamps");
			helper.assertTrue(type.bodyBottom() == expected[0] && type.bodyTop() == expected[1],
				type + " should have its body from " + expected[0] + " to " + expected[1] + ", but it is from " + type.bodyBottom() + " to " + type.bodyTop());
			for (int position = 1; position <= type.lampCount(); position++)
				helper.assertTrue(type.socketY(position) == expected[position + 1],
					"lamp " + position + " of " + type + " should be at " + expected[position + 1] + ", but is at " + type.socketY(position));
		}
		helper.succeed();
	}

	/**
	 * The sockets are where the models have them: each lamp sits in a ring of small pieces, and the
	 * middle of the ring is the middle of the socket. The pole and the body end where the code says too.
	 */
	@GameTest(template = EMPTY)
	public static void theCodeAgreesWithEachTypesHousing(GameTestHelper helper) {
		for (SignalType type : SignalType.values()) {
			JsonObject housing = json(helper, "assets", "models/block/" + type.modelDir(), "housing.json");
			JsonArray elements = housing.getAsJsonArray("elements");

			JsonObject lights = group(housing.getAsJsonArray("groups"), "lights");
			helper.assertTrue(lights != null, type + "'s housing should have a group of lights");
			List<JsonObject> sockets = new ArrayList<>();
			for (JsonElement child : lights.getAsJsonArray("children"))
				if (child.isJsonObject())
					sockets.add(child.getAsJsonObject());
			helper.assertTrue(sockets.size() == type.lampCount(),
				type + "'s housing should have a group for each of its " + type.lampCount() + " lamps, but has " + sockets.size());

			for (int position = 1; position <= sockets.size(); position++) {
				JsonObject socket = sockets.get(position - 1);
				helper.assertTrue(socket.get("name")
					.getAsString()
					.equals(Integer.toString(position)), "the groups of " + type + " should be numbered from the top, but " + position + " is called " + socket.get("name"));
				double[] middle = ringMiddle(elements, socket.getAsJsonArray("children"));
				helper.assertTrue(middle != null, "lamp " + position + " of " + type + " should have a ring round its socket");
				helper.assertTrue(Math.abs(middle[1] - type.socketY(position)) <= WITHIN,
					"lamp " + position + " of " + type + " is told to be " + type.socketY(position) + " high, but its socket is " + middle[1] + " high");
				helper.assertTrue(Math.abs(middle[0] - 8) <= 2 * WITHIN,
					"lamp " + position + " of " + type + " should be in the middle of the block, but its socket is at " + middle[0]);
			}

			JsonObject pole = null;
			double lowest = Double.MAX_VALUE;
			double highest = -Double.MAX_VALUE;
			for (JsonElement element : elements) {
				JsonObject e = element.getAsJsonObject();
				if (e.has("name") && e.get("name")
					.getAsString()
					.equals("pole")) {
					pole = e;
					continue;
				}
				for (double[] corner : corners(e)) {
					lowest = Math.min(lowest, corner[1]);
					highest = Math.max(highest, corner[1]);
				}
			}
			helper.assertTrue(pole != null, type + "'s housing should have its pole");
			double poleBottom = pole.getAsJsonArray("from")
				.get(1)
				.getAsDouble();
			double poleTop = pole.getAsJsonArray("to")
				.get(1)
				.getAsDouble();
			helper.assertTrue(Math.abs(poleBottom) <= WITHIN && Math.abs(poleTop - type.bodyTop()) <= WITHIN,
				type + "'s pole should stand on the ground and end at " + type.bodyTop() + ", but runs from " + poleBottom + " to " + poleTop);
			helper.assertTrue(Math.abs(lowest - type.bodyBottom()) <= WITHIN && Math.abs(highest - type.bodyTop()) <= WITHIN,
				type + "'s body should run from " + type.bodyBottom() + " to " + type.bodyTop() + ", but runs from " + lowest + " to " + highest);
		}
		helper.succeed();
	}

	/**
	 * A head's box is what its model spans, for every offset, and the mast is as tall as the pole in the
	 * mast's own model. This is the check that the models split from housing.json and the ones drawn by
	 * hand for the five-aspect are the size the code has them.
	 */
	@GameTest(template = EMPTY)
	public static void theBoxOfAHeadIsTheSizeOfItsModel(GameTestHelper helper) {
		for (SignalType type : SignalType.values())
			for (HorizontalOffset offset : HorizontalOffset.values()) {
				String name = offset == HorizontalOffset.CENTER ? "housing_head.json" : "housing_" + offset.getSerializedName() + "_head.json";
				double[] model = bounds(json(helper, "assets", "models/block/" + type.modelDir(), name)
					.getAsJsonArray("elements"));
				double[] box = MultiAspectSignalBlock.headBounds(type, offset);
				String what = type + " at " + offset + ": the box is " + java.util.Arrays.toString(box) + " but the model spans " + java.util.Arrays.toString(model);
				for (int i = 0; i < 6; i++)
					helper.assertTrue(Math.abs(box[i] - model[i]) <= WITHIN, what);
			}
		helper.succeed();
	}

	@GameTest(template = EMPTY)
	public static void theMastIsAsTallAsThePole(GameTestHelper helper) {
		for (SignalType type : SignalType.values())
			for (HorizontalOffset offset : HorizontalOffset.values()) {
				String name = offset == HorizontalOffset.CENTER ? "housing_post.json" : "housing_" + offset.getSerializedName() + "_post.json";
				double[] model = bounds(json(helper, "assets", "models/block/" + type.modelDir(), name)
					.getAsJsonArray("elements"));
				helper.assertTrue(Math.abs(model[1]) <= WITHIN && Math.abs(model[4] - type.bodyTop()) <= WITHIN,
					type + " at " + offset + ": the mast should stand from the ground to " + type.bodyTop() + ", but its model runs from " + model[1] + " to " + model[4]);
			}
		helper.succeed();
	}

	/**
	 * A shape cannot leave its block, so what is drawn above 16 pixels is not part of it, and what is in it
	 * reaches as high as the type does, up to the top of the block.
	 */
	@GameTest(template = EMPTY)
	public static void aShapeStaysInsideItsBlockAndReachesAsHighAsItsType(GameTestHelper helper) {
		BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
		for (SignalType type : SignalType.values()) {
			MultiAspectSignalBlock block = block(type);
			double top = Math.min(16, type.bodyTop()) / 16;
			for (HorizontalOffset offset : HorizontalOffset.values())
				for (int rotation = 0; rotation < 16; rotation++) {
					BlockState state = block.defaultBlockState()
						.setValue(MultiAspectSignalBlock.ROTATION, rotation)
						.setValue(MultiAspectSignalBlock.OFFSET, offset);
					VoxelShape shape = state.getShape(helper.getLevel(), pos);
					helper.assertTrue(shape.min(Direction.Axis.Y) >= 0 && Math.abs(shape.max(Direction.Axis.Y) - top) < 1.0E-6,
						type + " at " + offset + " turned " + rotation + " should have a shape up to " + top + ", but it spans "
							+ shape.min(Direction.Axis.Y) + " to " + shape.max(Direction.Axis.Y));
					helper.assertTrue(shape.min(Direction.Axis.X) >= 0 && shape.max(Direction.Axis.X) <= 1 && shape.min(Direction.Axis.Z) >= 0
						&& shape.max(Direction.Axis.Z) <= 1, type + " at " + offset + " turned " + rotation + " should have a shape inside its block");
				}
		}
		helper.succeed();
	}

	/**
	 * The six-aspect is taller than a block and it is still one block: the one above is air, and something
	 * placed against the top of the signal goes there, with the signal still standing.
	 */
	@GameTest(template = EMPTY)
	public static void aBlockCanBePlacedAboveTheTallSignal(GameTestHelper helper) {
		BlockPos pos = new BlockPos(4, 2, 4);
		helper.setBlock(pos, CTNRegistry.MULTI_ASPECT_SIGNAL_6.get()
			.defaultBlockState());
		helper.assertTrue(helper.getBlockState(pos.above())
			.isAir(), "the block above the six-aspect signal should be empty, not a second half of it");

		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		BlockPos absolute = helper.absolutePos(pos);
		BlockHitResult top = new BlockHitResult(Vec3.atCenterOf(absolute)
			.add(0, 0.5, 0), Direction.UP, absolute, false);
		InteractionResult placed = Items.STONE.useOn(
			new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, new ItemStack(Items.STONE), top));
		helper.assertTrue(placed.consumesAction(), "placing a block against the top of the signal should work, but gave " + placed);
		helper.assertTrue(helper.getBlockState(pos.above())
			.is(Blocks.STONE), "the block should have gone above the signal");
		helper.assertTrue(helper.getBlockState(pos)
			.is(CTNRegistry.MULTI_ASPECT_SIGNAL_6.get()) && helper.getBlockEntity(pos) instanceof MultiAspectSignalBlockEntity,
			"and the signal should still be standing");
		helper.succeed();
	}

	private static MultiAspectSignalBlock block(SignalType type) {
		return switch (type) {
			case TWO -> CTNRegistry.MULTI_ASPECT_SIGNAL_2.get();
			case THREE -> CTNRegistry.MULTI_ASPECT_SIGNAL_3.get();
			case FOUR -> CTNRegistry.MULTI_ASPECT_SIGNAL_4.get();
			case FIVE -> CTNRegistry.MULTI_ASPECT_SIGNAL.get();
			case SIX -> CTNRegistry.MULTI_ASPECT_SIGNAL_6.get();
		};
	}

	// --------------------------------------------------------------- reading models

	/** The group with this name, looked for through the groups and the groups in them. */
	private static JsonObject group(JsonArray groups, String name) {
		for (JsonElement element : groups) {
			if (!element.isJsonObject())
				continue;
			JsonObject group = element.getAsJsonObject();
			if (group.has("name") && group.get("name")
				.getAsString()
				.equals(name))
				return group;
			if (group.has("children")) {
				JsonObject inner = group(group.getAsJsonArray("children"), name);
				if (inner != null)
					return inner;
			}
		}
		return null;
	}

	/**
	 * The middle of the ring round a socket: the pieces of a lamp's group that are 0.05 of a pixel deep,
	 * which are the small ones that make up the bezel, averaged. Null if there are none.
	 */
	private static double[] ringMiddle(JsonArray elements, JsonArray children) {
		double x = 0;
		double y = 0;
		int count = 0;
		for (JsonElement child : children) {
			if (!child.isJsonPrimitive())
				continue;
			JsonObject e = elements.get(child.getAsInt())
				.getAsJsonObject();
			double depth = e.getAsJsonArray("to")
				.get(2)
				.getAsDouble()
				- e.getAsJsonArray("from")
					.get(2)
					.getAsDouble();
			if (Math.abs(depth - 0.05) > 0.01)
				continue;
			for (double[] corner : corners(e)) {
				x += corner[0];
				y += corner[1];
				count++;
			}
		}
		return count == 0 ? null : new double[] { x / count, y / count };
	}

	/** The smallest box round the elements, with their rotations applied: x0, y0, z0, x1, y1, z1. */
	private static double[] bounds(JsonArray elements) {
		double[] box = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		for (JsonElement element : elements)
			for (double[] corner : corners(element.getAsJsonObject()))
				for (int axis = 0; axis < 3; axis++) {
					box[axis] = Math.min(box[axis], corner[axis]);
					box[axis + 3] = Math.max(box[axis + 3], corner[axis]);
				}
		return box;
	}

	/** The eight corners of an element in model space, turned about its rotation's axis if it has one. */
	private static double[][] corners(JsonObject element) {
		JsonArray from = element.getAsJsonArray("from");
		JsonArray to = element.getAsJsonArray("to");
		double[][] corners = new double[8][3];
		for (int i = 0; i < 8; i++) {
			corners[i][0] = (i & 1) == 0 ? from.get(0)
				.getAsDouble()
				: to.get(0)
					.getAsDouble();
			corners[i][1] = (i & 2) == 0 ? from.get(1)
				.getAsDouble()
				: to.get(1)
					.getAsDouble();
			corners[i][2] = (i & 4) == 0 ? from.get(2)
				.getAsDouble()
				: to.get(2)
					.getAsDouble();
		}

		JsonObject rotation = element.has("rotation") ? element.getAsJsonObject("rotation") : null;
		if (rotation == null || rotation.get("angle")
			.getAsDouble() == 0)
			return corners;

		double angle = Math.toRadians(rotation.get("angle")
			.getAsDouble());
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		JsonArray origin = rotation.getAsJsonArray("origin");
		String axis = rotation.get("axis")
			.getAsString();
		for (double[] c : corners) {
			double x = c[0] - origin.get(0)
				.getAsDouble();
			double y = c[1] - origin.get(1)
				.getAsDouble();
			double z = c[2] - origin.get(2)
				.getAsDouble();
			double turnedX = x;
			double turnedY = y;
			double turnedZ = z;
			switch (axis) {
				case "x" -> {
					turnedY = y * cos - z * sin;
					turnedZ = y * sin + z * cos;
				}
				case "y" -> {
					turnedX = x * cos + z * sin;
					turnedZ = -x * sin + z * cos;
				}
				default -> {
					turnedX = x * cos - y * sin;
					turnedY = x * sin + y * cos;
				}
			}
			c[0] = turnedX + origin.get(0)
				.getAsDouble();
			c[1] = turnedY + origin.get(1)
				.getAsDouble();
			c[2] = turnedZ + origin.get(2)
				.getAsDouble();
		}
		return corners;
	}
}
