package com.eyunya.createtrainnext.gametest;

import java.util.Arrays;
import java.util.Map;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.pole.SignalPoleBlock;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlock;
import com.eyunya.createtrainnext.signal.PlateKind;
import com.eyunya.createtrainnext.signal.SetSignalPlatePacket;
import com.eyunya.createtrainnext.signal.SignalPlateBlock;
import com.eyunya.createtrainnext.signal.SignalPlateBlockEntity;
import com.eyunya.createtrainnext.signal.SignalRotation;
import com.eyunya.createtrainnext.speed.SpeedLimitBlock;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks the plates on a signal's pole: that one goes on turned the way the signal is, that poles and the
 * signal under a crown join it, that what is written on it is kept and only by someone who could reach it,
 * and that its model is where the code says.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class SignalPlateTests {

	private static final String EMPTY = "empty";

	/** Where each plate is in its model, written out a second time as {@code SignalModelTests} does. */
	private static final Map<PlateKind, double[]> PLATES = Map.ofEntries(
		Map.entry(PlateKind.NUMBER, new double[] { 5, 5, 9, 11, 11, 10 }),
		Map.entry(PlateKind.CROWN, new double[] { 4, 3, 9, 12, 8, 10 }),
		Map.entry(PlateKind.STOP_POSITION, new double[] { 4, 4, 9, 12, 12, 10 }),
		Map.entry(PlateKind.TRAIN_STOP, new double[] { 4, 4, 9, 12, 12, 10 }),
		Map.entry(PlateKind.CAR_STOP, new double[] { 4, 4, 9, 12, 12, 10 }),
		Map.entry(PlateKind.BUFFER_STOP, new double[] { 3, 3, 9, 13, 13, 10 }),
		Map.entry(PlateKind.SIGNAL_CALL, new double[] { 4, 4, 9, 12, 11, 10 }),
		Map.entry(PlateKind.KILOMETRE_POST, new double[] { 6, 2, 9, 10, 14, 10 }),
		Map.entry(PlateKind.CLEARANCE_POST, new double[] { 6, 0, 9, 10, 4, 10 }),
		Map.entry(PlateKind.SIGNAL_WARNING, new double[] { 5, 2, 9, 11, 14, 10 }),
		Map.entry(PlateKind.WHISTLE, new double[] { 4, 4, 9, 12, 12, 10 }),
		Map.entry(PlateKind.BRAKE, new double[] { 5, 5, 9, 11, 11, 10 }),
		Map.entry(PlateKind.STATION_APPROACH, new double[] { 5, 2, 9, 11, 14, 10 }));

	/** The kinds that are written on, and how many characters each holds; the rest are their picture alone. */
	private static final Map<PlateKind, Integer> TEXT = Map.of(PlateKind.NUMBER, 4, PlateKind.CROWN, 6,
		PlateKind.STOP_POSITION, 3, PlateKind.SIGNAL_CALL, 3, PlateKind.KILOMETRE_POST, 4);

	/** A crown over a signal is turned as the signal is, and the signal's mast runs up to it. */
	@GameTest(template = EMPTY)
	public static void aCrownTakesTheTurnOfTheSignalUnderItAndTheMastRunsUpToIt(GameTestHelper helper) {
		BlockPos signal = new BlockPos(2, 1, 2);
		helper.setBlock(signal, CTNRegistry.MULTI_ASPECT_SIGNAL_3.get()
			.defaultBlockState()
			.setValue(MultiAspectSignalBlock.ROTATION, 5));
		helper.assertTrue(!helper.getBlockState(signal)
			.getValue(MultiAspectSignalBlock.UP), "setup: nothing should stand over the signal yet");

		BlockState crown = place(helper, CTNRegistry.SIGNAL_CROWN_PLATE.get(), signal.above(), 0);
		helper.assertTrue(crown.getValue(SignalPlateBlock.ROTATION) == 5,
			"a crown should be turned as the signal under it is, 5, but is " + crown.getValue(SignalPlateBlock.ROTATION));
		helper.setBlock(signal.above(), crown);
		helper.assertTrue(helper.getBlockState(signal)
			.getValue(MultiAspectSignalBlock.UP), "the signal's mast should run up to the crown");
		helper.succeed();
	}

	/** A number plate under a signal, and another under that one, are turned as the signal is. */
	@GameTest(template = EMPTY)
	public static void aNumberPlateTakesTheTurnOfWhatIsOverIt(GameTestHelper helper) {
		BlockPos signal = new BlockPos(5, 3, 5);
		helper.setBlock(signal, CTNRegistry.MULTI_ASPECT_SIGNAL.get()
			.defaultBlockState()
			.setValue(MultiAspectSignalBlock.ROTATION, 11));

		BlockState plate = place(helper, CTNRegistry.SIGNAL_NUMBER_PLATE.get(), signal.below(), 0);
		helper.assertTrue(plate.getValue(SignalPlateBlock.ROTATION) == 11,
			"a number plate should be turned as the signal over it is, 11, but is " + plate.getValue(SignalPlateBlock.ROTATION));
		helper.setBlock(signal.below(), plate);

		BlockState second = place(helper, CTNRegistry.SIGNAL_NUMBER_PLATE.get(), signal.below(2), 0);
		helper.assertTrue(second.getValue(SignalPlateBlock.ROTATION) == 11,
			"a plate under a plate should be turned as that one is, 11, but is " + second.getValue(SignalPlateBlock.ROTATION));
		helper.succeed();
	}

	/**
	 * A plate with nothing turned above or below it faces whoever puts it down, as a signal does; and a speed
	 * limit sign, which has a rotation of the same name measured from north, does not count as something turned.
	 */
	@GameTest(template = EMPTY)
	public static void aPlateOnItsOwnFacesWhoeverPutsItDown(GameTestHelper helper) {
		BlockPos pos = new BlockPos(8, 2, 8);
		// Looking west, yaw 90: a quarter turn and a half turn round, segment 12.
		int expected = Math.floorMod(Math.round(90 / SignalRotation.DEGREES_PER_SEGMENT) + SignalRotation.SEGMENTS / 2,
			SignalRotation.SEGMENTS);
		BlockState alone = place(helper, CTNRegistry.SIGNAL_NUMBER_PLATE.get(), pos, 90);
		helper.assertTrue(alone.getValue(SignalPlateBlock.ROTATION) == expected,
			"a plate on its own should face whoever puts it down, " + expected + ", but is " + alone.getValue(SignalPlateBlock.ROTATION));

		helper.setBlock(pos.above(), CTNRegistry.TRACK_SPEED_LIMIT.get()
			.defaultBlockState()
			.setValue(SpeedLimitBlock.ROTATION, 3));
		BlockState underSign = place(helper, CTNRegistry.SIGNAL_NUMBER_PLATE.get(), pos, 90);
		helper.assertTrue(underSign.getValue(SignalPlateBlock.ROTATION) == expected,
			"a speed limit sign over a plate should not turn it, but it is " + underSign.getValue(SignalPlateBlock.ROTATION));
		helper.succeed();
	}

	/** Poles over and under a plate grow an arm into it; one beside it does not. */
	@GameTest(template = EMPTY)
	public static void polesJoinAPlateFromAboveAndBelow(GameTestHelper helper) {
		BlockPos pos = new BlockPos(11, 2, 11);
		BlockState pole = CTNRegistry.SIGNAL_POLE.get()
			.defaultBlockState();
		helper.setBlock(pos.above(), pole);
		helper.setBlock(pos.below(), pole);
		helper.setBlock(pos.east(), pole);
		helper.setBlock(pos, CTNRegistry.SIGNAL_NUMBER_PLATE.get()
			.defaultBlockState());

		helper.assertTrue(helper.getBlockState(pos.above())
			.getValue(SignalPoleBlock.CONNECTIONS.get(Direction.DOWN)), "the pole over the plate should reach down to it");
		helper.assertTrue(helper.getBlockState(pos.below())
			.getValue(SignalPoleBlock.CONNECTIONS.get(Direction.UP)), "the pole under the plate should reach up to it");
		helper.assertTrue(!helper.getBlockState(pos.east())
			.getValue(SignalPoleBlock.CONNECTIONS.get(Direction.WEST)), "a pole beside the plate should not reach into it");
		helper.succeed();
	}

	/**
	 * What is written comes from a player who could right click the plate and may change blocks, is cut to
	 * what the plate holds counting characters as they are read, and is saved.
	 */
	@GameTest(template = EMPTY)
	public static void whatIsWrittenIsKeptOnlyFromSomeoneInReach(GameTestHelper helper) {
		BlockPos pos = new BlockPos(14, 2, 14);
		helper.setBlock(pos, CTNRegistry.SIGNAL_NUMBER_PLATE.get()
			.defaultBlockState());
		BlockPos absolute = helper.absolutePos(pos);
		SignalPlateBlockEntity plate = (SignalPlateBlockEntity) helper.getBlockEntity(pos);
		Player player = helper.makeMockPlayer(GameType.CREATIVE);

		player.setPos(Vec3.atCenterOf(absolute)
			.add(0, 0, 20));
		helper.assertTrue(!SetSignalPlatePacket.apply(player, new SetSignalPlatePacket(absolute, "9")) && plate.getText()
			.isEmpty(), "a player twenty blocks off should not be able to write on the plate");

		player.setPos(Vec3.atCenterOf(absolute)
			.add(0, -1, 2));
		helper.assertTrue(SetSignalPlatePacket.apply(player, new SetSignalPlatePacket(absolute, " 12 ")) && plate.getText()
			.equals("12"), "a player beside the plate should write 12 on it, but it says '" + plate.getText() + "'");

		SetSignalPlatePacket.apply(player, new SetSignalPlatePacket(absolute, "下り123456"));
		helper.assertTrue(plate.getText()
			.equals("下り12"), "a number plate holds four characters, a kana being one, so '下り12', but it says '" + plate.getText() + "'");

		// What adventure mode takes away. The mock player is not in a game mode the way a server player is.
		player.getAbilities().mayBuild = false;
		helper.assertTrue(!SetSignalPlatePacket.apply(player, new SetSignalPlatePacket(absolute, "3")) && plate.getText()
			.equals("下り12"), "a player who may not change blocks should not write on the plate");

		CompoundTag saved = plate.saveWithFullMetadata(helper.getLevel()
			.registryAccess());
		helper.setBlock(pos, Blocks.AIR);
		helper.setBlock(pos, CTNRegistry.SIGNAL_NUMBER_PLATE.get()
			.defaultBlockState());
		SignalPlateBlockEntity again = (SignalPlateBlockEntity) helper.getBlockEntity(pos);
		helper.assertTrue(again.getText()
			.isEmpty(), "setup: a new plate should say nothing");
		again.loadWithComponents(saved, helper.getLevel()
			.registryAccess());
		helper.assertTrue(again.getText()
			.equals("下り12"), "a plate should say what it was saved saying, but says '" + again.getText() + "'");
		helper.succeed();
	}

	/**
	 * Each kind is a block and an item, with the files the game looks for, and its plate model is where
	 * {@link PlateKind} says, which is also where the block's shape and the writing come from.
	 */
	@GameTest(template = EMPTY)
	public static void eachPlateHasItsFilesAndItsPlateIsWhereTheCodeSays(GameTestHelper helper) {
		for (PlateKind kind : PlateKind.values()) {
			String name = kind.blockName();
			Block block = BuiltInRegistries.BLOCK.get(CreateTrainNext.asResource(name));
			helper.assertTrue(block instanceof SignalPlateBlock plate && plate.kind() == kind,
				"the block for " + kind + " should be a plate of that kind");
			helper.assertTrue(BuiltInRegistries.BLOCK.getKey(block)
				.equals(CreateTrainNext.asResource(name)), kind + " should be registered as " + name);
			helper.assertTrue(BuiltInRegistries.ITEM.get(CreateTrainNext.asResource(name)) instanceof BlockItem item
				&& item.getBlock() == block, kind + " should have an item that places it");

			double[] expected = PLATES.get(kind);
			double[] told = kind.plateBounds();
			helper.assertTrue(Arrays.equals(expected, told),
				kind + " should be told its plate is " + Arrays.toString(expected) + ", but is told " + Arrays.toString(told));

			JsonArray elements = SignalTypeTests.json(helper, "assets", "models/block/" + name, "plate.json")
				.getAsJsonArray("elements");
			double[] drawn = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
				-Double.MAX_VALUE, -Double.MAX_VALUE };
			for (JsonElement element : elements) {
				JsonArray from = element.getAsJsonObject()
					.getAsJsonArray("from");
				JsonArray to = element.getAsJsonObject()
					.getAsJsonArray("to");
				for (int axis = 0; axis < 3; axis++) {
					drawn[axis] = Math.min(drawn[axis], from.get(axis)
						.getAsDouble());
					drawn[axis + 3] = Math.max(drawn[axis + 3], to.get(axis)
						.getAsDouble());
				}
			}
			helper.assertTrue(Arrays.equals(drawn, told),
				name + "/plate.json spans " + Arrays.toString(drawn) + ", but the code says " + Arrays.toString(told));

			// The shape follows the plate: its front at z1 facing south, and at north once turned half round.
			AABB south = block.defaultBlockState()
				.getShape(helper.getLevel(), BlockPos.ZERO)
				.bounds();
			AABB north = block.defaultBlockState()
				.setValue(SignalPlateBlock.ROTATION, SignalRotation.SEGMENTS / 2)
				.getShape(helper.getLevel(), BlockPos.ZERO)
				.bounds();
			helper.assertTrue(Math.abs(south.maxZ - told[5] / 16) < 1e-6 && Math.abs(north.minZ - (16 - told[5]) / 16) < 1e-6
				&& south.minY == 0 && south.maxY == 1, kind + "'s shape should be its pole and its plate, but is " + south + " and turned " + north);

			JsonObject blockstate = SignalTypeTests.json(helper, "assets", "blockstates", name + ".json");
			SignalTypeTests.model(helper, blockstate.getAsJsonObject("variants")
				.getAsJsonObject("")
				.get("model")
				.getAsString());
			SignalTypeTests.model(helper, SignalTypeTests.json(helper, "assets", "models/item", name + ".json")
				.get("parent")
				.getAsString());
			SignalTypeTests.model(helper, CreateTrainNext.ID + ":block/" + name + "/plate");
			helper.assertTrue(SignalTypeTests.json(helper, "data", "loot_table/blocks", name + ".json")
				.toString()
				.contains("\"" + CreateTrainNext.ID + ":" + name + "\""), name + "'s loot table should drop it");
			for (String language : new String[] { "en_us.json", "ja_jp.json" })
				helper.assertTrue(SignalTypeTests.json(helper, "assets", "lang", language)
					.has("block." + CreateTrainNext.ID + "." + name), language + " should name " + name);
		}
		helper.succeed();
	}

	/**
	 * The signs with a picture and nothing to write take no text, from the screen or from anything else; the
	 * kilometre post alone is written down its plate.
	 */
	@GameTest(template = EMPTY)
	public static void onlyThePlatesWithWritingTakeText(GameTestHelper helper) {
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		int slot = 0;
		for (PlateKind kind : PlateKind.values()) {
			int holds = TEXT.getOrDefault(kind, 0);
			helper.assertTrue(kind.maxLength() == holds && kind.takesText() == (holds > 0),
				kind + " should hold " + holds + " characters, but holds " + kind.maxLength());
			helper.assertTrue(kind.vertical() == (kind == PlateKind.KILOMETRE_POST),
				kind + (kind == PlateKind.KILOMETRE_POST ? " should" : " should not") + " be written downwards");

			BlockPos pos = new BlockPos(2 + 3 * (slot % 13), 2, 20 + 3 * (slot / 13));
			slot++;
			helper.setBlock(pos, BuiltInRegistries.BLOCK.get(CreateTrainNext.asResource(kind.blockName()))
				.defaultBlockState());
			BlockPos absolute = helper.absolutePos(pos);
			player.setPos(Vec3.atCenterOf(absolute)
				.add(0, -1, 2));
			boolean written = SetSignalPlatePacket.apply(player, new SetSignalPlatePacket(absolute, "7"));
			helper.assertTrue(written == (holds > 0), kind + (holds > 0 ? " should" : " should not") + " take writing");
		}
		helper.succeed();
	}

	/**
	 * What placing {@code block} at {@code pos} would give, for a player looking {@code yaw} degrees round
	 * from south. Clicked at the spot itself, which is empty, so the click lands there.
	 */
	private static BlockState place(GameTestHelper helper, Block block, BlockPos pos, float yaw) {
		Player player = helper.makeMockPlayer(GameType.CREATIVE);
		player.setYRot(yaw);
		BlockPos absolute = helper.absolutePos(pos);
		ItemStack stack = new ItemStack(block);
		player.setItemInHand(InteractionHand.MAIN_HAND, stack);
		BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack,
			new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false));
		helper.assertTrue(context.getClickedPos()
			.equals(absolute), "setup: the click should land at " + absolute + ", but lands at " + context.getClickedPos());
		BlockState state = block.getStateForPlacement(context);
		helper.assertTrue(state != null, "setup: " + block + " should be placeable at " + pos);
		return state;
	}
}
