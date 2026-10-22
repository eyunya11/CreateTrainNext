package com.eyunya.createtrainnext.gametest;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eyunya.createtrainnext.CTNConfig;
import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlock;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlock.HorizontalOffset;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlockEntity;
import com.eyunya.createtrainnext.signal.RelayIndication;
import com.eyunya.createtrainnext.signal.SignalAspect;
import com.eyunya.createtrainnext.signal.SignalSpeeds;
import com.eyunya.createtrainnext.signal.SignalType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks the kinds of signal head: what each lights for each aspect, how far ahead each looks, what each
 * holds a train to, and that every one of them is wired up as a block.
 *
 * <p>The lamp tables are written out here a second time on purpose. They are the same facts as in
 * {@link SignalType}, but a table that is only ever compared with itself cannot say that it is wrong.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class SignalTypeTests {

	private static final String EMPTY = "empty";

	/** In the order of {@link SignalAspect#values()}: 停止, 警戒, 注意, 減速, 進行, 高速進行. */
	private static final Map<SignalType, String[]> LIT = Map.of(
		SignalType.TWO, new String[] { "1", "-", "-", "-", "2", "-" },
		SignalType.THREE, new String[] { "2", "-", "1", "-", "3", "-" },
		SignalType.FOUR, new String[] { "2", "-", "3", "1,4", "4", "-" },
		SignalType.FIVE, new String[] { "3", "1,4", "4", "2,5", "5", "-" },
		SignalType.SIX, new String[] { "3", "-", "5", "1,4", "4", "2,6" });

	/** Top to bottom: R red, Y yellow, G green. */
	private static final Map<SignalType, String> COLOURS = Map.of(
		SignalType.TWO, "RG",
		SignalType.THREE, "YRG",
		SignalType.FOUR, "YRYG",
		SignalType.FIVE, "YYRYG",
		SignalType.SIX, "YGRGYG");

	/** The aspect shown with 0, 1, 2, 3, 4 and 5 blocks clear. */
	private static final Map<SignalType, String> LADDER = Map.of(
		SignalType.TWO, "STOP PROCEED PROCEED PROCEED PROCEED PROCEED",
		SignalType.THREE, "STOP APPROACH PROCEED PROCEED PROCEED PROCEED",
		SignalType.FOUR, "STOP APPROACH REDUCED_SPEED PROCEED PROCEED PROCEED",
		SignalType.FIVE, "STOP RESTRICTED APPROACH REDUCED_SPEED PROCEED PROCEED",
		SignalType.SIX, "STOP APPROACH REDUCED_SPEED PROCEED HIGH_SPEED_PROCEED HIGH_SPEED_PROCEED");

	@GameTest(template = EMPTY)
	public static void eachAspectLightsTheLampsOfItsType(GameTestHelper helper) {
		for (SignalType type : SignalType.values()) {
			String[] expected = LIT.get(type);
			SignalAspect[] aspects = SignalAspect.values();
			helper.assertTrue(expected.length == aspects.length, "setup: the table should have a column for every aspect");
			for (int i = 0; i < aspects.length; i++) {
				String lit = litPositions(type, aspects[i]);
				helper.assertTrue(lit.equals(expected[i]),
					type + " should light lamps " + expected[i] + " for " + aspects[i] + ", but lights " + lit);
				helper.assertTrue(type.shows(aspects[i]) == !expected[i].equals("-"),
					type + (type.shows(aspects[i]) ? " should not show " : " should show ") + aspects[i]);
			}
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY)
	public static void eachTypeHasItsLampsInItsColours(GameTestHelper helper) {
		for (SignalType type : SignalType.values()) {
			StringBuilder colours = new StringBuilder();
			for (int position = 1; position <= type.lampCount(); position++)
				colours.append(type.colourAt(position)
					.name()
					.charAt(0));
			helper.assertTrue(colours.toString()
				.equals(COLOURS.get(type)), type + " should have its lamps " + COLOURS.get(type) + " from the top, but has " + colours);
		}
		helper.succeed();
	}

	/** Red is stop, in every type: a driver does not have to know which head it is to know that. */
	@GameTest(template = EMPTY)
	public static void stopIsAlwaysTheRedLampAlone(GameTestHelper helper) {
		for (SignalType type : SignalType.values())
			for (int position = 1; position <= type.lampCount(); position++) {
				boolean lit = type.isLit(SignalAspect.STOP, position);
				boolean red = type.colourAt(position) == SignalAspect.LampColour.RED;
				helper.assertTrue(lit == red, type + " at stop should light the red lamp and no other, but lamp " + position + (lit ? " is lit" : " is dark"));
			}
		helper.succeed();
	}

	@GameTest(template = EMPTY)
	public static void aTypeShowsEveryAspectOnItsLadderWithALightOfItsOwn(GameTestHelper helper) {
		for (SignalType type : SignalType.values()) {
			List<String> seen = new ArrayList<>();
			for (SignalAspect aspect : type.ladder()
				.aspects()) {
				helper.assertTrue(type.shows(aspect), type + " climbs through " + aspect + " but cannot show it");
				String lit = litPositions(type, aspect);
				helper.assertTrue(!lit.equals("-"), type + " shows " + aspect + " with every lamp dark");
				helper.assertTrue(!seen.contains(lit), type + " shows two aspects with the lamps " + lit);
				seen.add(lit);
			}
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY)
	public static void theLaddersStepUpAsTheLineClears(GameTestHelper helper) {
		for (SignalType type : SignalType.values()) {
			List<String> shown = new ArrayList<>();
			for (int clear = 0; clear <= 5; clear++)
				shown.add(type.ladder()
					.aspectFor(clear)
					.name());
			helper.assertTrue(String.join(" ", shown)
				.equals(LADDER.get(type)), type + " should show " + LADDER.get(type) + " with 0 to 5 clear, but shows " + shown);
		}
		helper.assertTrue(SignalType.TWO.ladder()
			.lookahead() == 1 && SignalType.THREE.ladder()
				.lookahead() == 2
			&& SignalType.FOUR.ladder()
				.lookahead() == 3
			&& SignalType.FIVE.ladder()
				.lookahead() == 4
			&& SignalType.SIX.ladder()
				.lookahead() == 4,
			"each ladder should look as far ahead as it has rungs above stop");
		helper.succeed();
	}

	/** The five-aspect's lamps are where its model has the sockets, and the others are spread about the middle of them. */
	@GameTest(template = EMPTY)
	public static void lampsSitWhereTheirModelHasThem(GameTestHelper helper) {
		float[] five = { 12.25F, 10F, 7.75F, 5.5F, 3.25F };
		for (int position = 1; position <= 5; position++)
			helper.assertTrue(SignalType.FIVE.socketY(position) == five[position - 1],
				"lamp " + position + " of the five-aspect should be at " + five[position - 1] + ", but is at " + SignalType.FIVE.socketY(position));

		for (SignalType type : SignalType.values()) {
			float sum = 0;
			for (int position = 1; position <= type.lampCount(); position++) {
				sum += type.socketY(position);
				if (position > 1)
					helper.assertTrue(Math.abs(type.socketY(position - 1) - type.socketY(position) - 2.25F) < 1.0E-4,
						type + " should space its lamps 2.25 pixels apart, top lamp first");
			}
			helper.assertTrue(Math.abs(sum / type.lampCount() - 7.75F) < 1.0E-4,
				type + " should centre its lamps on the middle of the housing, but they centre on " + sum / type.lampCount());
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY)
	public static void aRelayReadsHighSpeedProceedAsProceed(GameTestHelper helper) {
		helper.assertTrue(RelayIndication.of(SignalAspect.HIGH_SPEED_PROCEED) == RelayIndication.PROCEED,
			"a relay should show 進行 for 高速進行");
		helper.assertTrue(RelayIndication.of(SignalAspect.PROCEED) == RelayIndication.PROCEED, "and for 進行");
		helper.assertTrue(RelayIndication.of(SignalAspect.APPROACH) == RelayIndication.RESTRICTED, "and still 制限 for 注意");

		// A relay at a junction takes the lowest ordinal as the strictest, so the order is part of what they say.
		SignalAspect[] strictestFirst = { SignalAspect.STOP, SignalAspect.RESTRICTED, SignalAspect.APPROACH,
			SignalAspect.REDUCED_SPEED, SignalAspect.PROCEED, SignalAspect.HIGH_SPEED_PROCEED };
		for (int i = 0; i < strictestFirst.length; i++)
			helper.assertTrue(strictestFirst[i].ordinal() == i, "the aspects should run from 停止 to 高速進行, but " + strictestFirst[i] + " is at " + strictestFirst[i].ordinal());
		helper.succeed();
	}

	/**
	 * What a train far off is told by a six-aspect head is read off the head's own type: 進行 is 130 and
	 * 高速進行 lifts it, where the same lamps on any other head would hold a train to nothing.
	 */
	@GameTest(template = EMPTY)
	public static void aSixAspectHeadTellsATrainItsOwnProceedSpeed(GameTestHelper helper) {
		int proceed = CTNConfig.SERVER.sixAspectProceedSpeed.get();
		BlockPos pos = new BlockPos(4, 2, 4);
		helper.setBlock(pos, CTNRegistry.MULTI_ASPECT_SIGNAL_6.get()
			.defaultBlockState());
		BlockPos absolute = helper.absolutePos(pos);

		TrackNodeLocation hereIn = new TrackNodeLocation(new Vec3(0.5, 1, 0.5)).in(helper.getLevel()
			.dimension());
		TrackNodeLocation thereIn = new TrackNodeLocation(new Vec3(10.5, 1, 0.5)).in(helper.getLevel()
			.dimension());
		TrackNode toNode = new TrackNode(thereIn, 1, new Vec3(0, 1, 0));
		SignalBoundary facing = new SignalBoundary();
		facing.setLocation(Couple.create(hereIn, thereIn), 5.0);
		facing.blockEntities.get(true)
			.put(absolute, false);

		try {
			CTNConfig.SERVER.sixAspectProceedSpeed.set(130);
			MinecraftServer server = helper.getLevel()
				.getServer();
			MultiAspectSignalBlockEntity head = (MultiAspectSignalBlockEntity) helper.getBlockEntity(pos);
			setAspect(helper, head, SignalAspect.PROCEED);
			helper.assertTrue(SignalSpeeds.shownTo(server, facing, toNode) == 130, "a six-aspect head showing 進行 should hold a train far off to 130");
			CTNConfig.SERVER.sixAspectProceedSpeed.set(100);
			helper.assertTrue(SignalSpeeds.shownTo(server, facing, toNode) == 100, "and follow the config");
			setAspect(helper, head, SignalAspect.HIGH_SPEED_PROCEED);
			helper.assertTrue(SignalSpeeds.shownTo(server, facing, toNode) == SignalSpeeds.NONE, "showing 高速進行 it should hold a train to nothing");
			setAspect(helper, head, SignalAspect.REDUCED_SPEED);
			helper.assertTrue(SignalSpeeds.shownTo(server, facing, toNode) == CTNConfig.SERVER.reducedSpeed.get(), "and 減速 as ever");
		} finally {
			CTNConfig.SERVER.sixAspectProceedSpeed.set(proceed);
		}
		helper.succeed();
	}

	/**
	 * 進行 holds a train to nothing on every head but the six-aspect, which holds it to its own setting,
	 * and 高速進行 lifts that; the aspects that restrict are the same on every head.
	 */
	@GameTest(template = EMPTY)
	public static void theSixAspectHoldsATrainToItsOwnSpeedAtProceed(GameTestHelper helper) {
		int proceed = CTNConfig.SERVER.sixAspectProceedSpeed.get();
		int restricted = CTNConfig.SERVER.restrictedSpeed.get();
		try {
			helper.assertTrue(CTNConfig.SERVER.sixAspectProceedSpeed.getDefault() == 130, "the six-aspect's 進行 should default to 130");
			CTNConfig.SERVER.sixAspectProceedSpeed.set(130);
			CTNConfig.SERVER.restrictedSpeed.set(25);

			helper.assertTrue(SignalSpeeds.speedOf(SignalType.SIX, SignalAspect.PROCEED) == 130, "進行 on the six-aspect should hold a train to 130");
			helper.assertTrue(SignalSpeeds.speedOf(SignalType.SIX, SignalAspect.HIGH_SPEED_PROCEED) == SignalSpeeds.NONE,
				"高速進行 should hold a train to nothing");
			for (SignalType type : new SignalType[] { SignalType.TWO, SignalType.THREE, SignalType.FOUR, SignalType.FIVE })
				helper.assertTrue(SignalSpeeds.speedOf(type, SignalAspect.PROCEED) == SignalSpeeds.NONE,
					"進行 on " + type + " should hold a train to nothing");

			for (SignalType type : SignalType.values()) {
				helper.assertTrue(SignalSpeeds.speedOf(type, SignalAspect.REDUCED_SPEED) == CTNConfig.SERVER.reducedSpeed.get(),
					"減速 should be the same on " + type);
				helper.assertTrue(SignalSpeeds.speedOf(type, SignalAspect.STOP) == 25, "a train passing " + type + " at red is held to the restricted speed");
			}

			CTNConfig.SERVER.sixAspectProceedSpeed.set(110);
			helper.assertTrue(SignalSpeeds.speedOf(SignalType.SIX, SignalAspect.PROCEED) == 110, "the six-aspect's 進行 should follow the config");
		} finally {
			CTNConfig.SERVER.sixAspectProceedSpeed.set(proceed);
			CTNConfig.SERVER.restrictedSpeed.set(restricted);
		}
		helper.succeed();
	}

	/** Each type is a block and an item of its own, named as its type says, with the block entity on every one. */
	@GameTest(template = EMPTY)
	public static void eachTypeIsABlockAndAnItemOfItsOwn(GameTestHelper helper) {
		Map<SignalType, Block> blocks = Map.of(
			SignalType.TWO, CTNRegistry.MULTI_ASPECT_SIGNAL_2.get(),
			SignalType.THREE, CTNRegistry.MULTI_ASPECT_SIGNAL_3.get(),
			SignalType.FOUR, CTNRegistry.MULTI_ASPECT_SIGNAL_4.get(),
			SignalType.FIVE, CTNRegistry.MULTI_ASPECT_SIGNAL.get(),
			SignalType.SIX, CTNRegistry.MULTI_ASPECT_SIGNAL_6.get());

		int slot = 0;
		for (SignalType type : SignalType.values()) {
			Block block = blocks.get(type);
			helper.assertTrue(block instanceof MultiAspectSignalBlock signal && signal.signalType() == type,
				"the block for " + type + " should be a signal of that type");
			ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
			helper.assertTrue(id.equals(CreateTrainNext.asResource(type.blockName())),
				type + " should be registered as " + type.blockName() + ", but is " + id);
			helper.assertTrue(BuiltInRegistries.ITEM.get(id) instanceof TrackTargetingBlockItem item && item.getBlock() == block,
				type + " should have an item that places its own block");

			BlockPos pos = new BlockPos(2 + 4 * slot++, 2, 4);
			helper.setBlock(pos, block.defaultBlockState());
			BlockEntity entity = helper.getBlockEntity(pos);
			helper.assertTrue(entity instanceof MultiAspectSignalBlockEntity head && head.signalType() == type,
				"the block entity of " + type + " should know its type, but is " + entity);
			helper.assertTrue(((MultiAspectSignalBlockEntity) entity).getAspect() == SignalAspect.STOP, type + " should start at stop");
		}
		helper.succeed();
	}

	/** What a head shows is saved and read back, whatever the type, and the old setting for 警戒 is no longer read. */
	@GameTest(template = EMPTY)
	public static void aHeadKeepsItsAspectAndIgnoresTheOldSetting(GameTestHelper helper) {
		BlockPos pos = new BlockPos(4, 2, 4);
		for (boolean old : new boolean[] { false, true }) {
			helper.setBlock(pos, CTNRegistry.MULTI_ASPECT_SIGNAL.get()
				.defaultBlockState());
			MultiAspectSignalBlockEntity head = (MultiAspectSignalBlockEntity) helper.getBlockEntity(pos);
			CompoundTag tag = head.saveWithFullMetadata(helper.getLevel()
				.registryAccess());
			tag.putString("Aspect", "RESTRICTED");
			tag.putBoolean("Restricted", old);
			head.loadWithComponents(tag, helper.getLevel()
				.registryAccess());
			helper.assertTrue(head.getAspect() == SignalAspect.RESTRICTED, "a head should show what it was saved showing");
			helper.assertTrue(head.signalType() == SignalType.FIVE && head.signalType()
				.ladder()
				.aspects()
				.contains(SignalAspect.RESTRICTED), "a five-aspect saved with 警戒 " + (old ? "on" : "off") + " should climb through 警戒 all the same");
			helper.assertTrue(!head.saveWithFullMetadata(helper.getLevel()
				.registryAccess())
				.contains("Restricted"), "and should not save the setting again");
		}
		helper.succeed();
	}

	/**
	 * Every type has what the game looks for: a blockstate whose models exist, an item model, a model for
	 * the turning half of the housing at each offset, a loot table and a name in both languages.
	 *
	 * <p>The models of a type may be nothing but a parent, which is what they are until they are drawn,
	 * so a model is followed up its parents to be sure the chain ends in a file.
	 */
	@GameTest(template = EMPTY)
	public static void eachTypeHasItsFilesAndTheyPointAtFilesThatExist(GameTestHelper helper) {
		for (SignalType type : SignalType.values()) {
			String name = type.blockName();

			JsonObject blockstate = json(helper, "assets", "blockstates", name + ".json");
			JsonObject variants = blockstate.getAsJsonObject("variants");
			for (HorizontalOffset offset : HorizontalOffset.values()) {
				String model = variants.getAsJsonObject("offset=" + offset.getSerializedName())
					.get("model")
					.getAsString();
				helper.assertTrue(model.equals(CreateTrainNext.ID + ":block/" + type.modelDir() + "/" + offset.postModelName()),
					name + "'s blockstate should use the mast in its own folder for " + offset + ", but uses " + model);
				model(helper, model);
				model(helper, CreateTrainNext.ID + ":block/" + type.modelDir() + "/" + offset.headModelName());
			}

			JsonObject item = json(helper, "assets", "models/item", name + ".json");
			String itemModel = item.get("parent")
				.getAsString();
			helper.assertTrue(itemModel.equals(CreateTrainNext.ID + ":block/" + type.modelDir() + "/housing"),
				name + "'s item should show the housing in its own folder, but shows " + itemModel);
			model(helper, itemModel);

			JsonObject loot = json(helper, "data", "loot_table/blocks", name + ".json");
			helper.assertTrue(loot.toString()
				.contains("\"" + CreateTrainNext.ID + ":" + name + "\""), name + "'s loot table should drop " + name);

			for (String language : new String[] { "en_us.json", "ja_jp.json" })
				helper.assertTrue(json(helper, "assets", "lang", language).has("block." + CreateTrainNext.ID + "." + name),
					language + " should name " + name);
		}
		helper.succeed();
	}

	/** Makes a head show an aspect, by saving it, changing what it saved and reading it back as the world does. */
	private static void setAspect(GameTestHelper helper, MultiAspectSignalBlockEntity head, SignalAspect aspect) {
		CompoundTag tag = head.saveWithFullMetadata(helper.getLevel()
			.registryAccess());
		tag.putString("Aspect", aspect.name());
		head.loadWithComponents(tag, helper.getLevel()
			.registryAccess());
		helper.assertTrue(head.getAspect() == aspect, "setup: the head should show " + aspect + ", but shows " + head.getAspect());
	}

	private static String litPositions(SignalType type, SignalAspect aspect) {
		List<String> lit = new ArrayList<>();
		for (int position = 1; position <= type.lampCount(); position++)
			if (type.isLit(aspect, position))
				lit.add(Integer.toString(position));
		return lit.isEmpty() ? "-" : String.join(",", lit);
	}

	/** Reads a model by its id, and the models it names as its parent, until one has no parent of this mod's. */
	private static void model(GameTestHelper helper, String id) {
		String path = id.substring(id.indexOf(':') + 1);
		JsonObject model = json(helper, "assets", "models", path + ".json");
		JsonElement parent = model.get("parent");
		if (parent != null && parent.getAsString()
			.startsWith(CreateTrainNext.ID + ":"))
			model(helper, parent.getAsString());
	}

	private static JsonObject json(GameTestHelper helper, String root, String folder, String file) {
		String[] below = (folder + "/" + file).split("/");
		String[] path = new String[below.length + 1];
		path[0] = CreateTrainNext.ID;
		System.arraycopy(below, 0, path, 1, below.length);

		Path found = ModList.get()
			.getModFileById(CreateTrainNext.ID)
			.getFile()
			.findResource(prepend(root, path));
		helper.assertTrue(found != null && Files.exists(found), "the mod should have " + root + "/" + String.join("/", path));
		try (Reader reader = Files.newBufferedReader(found, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader)
				.getAsJsonObject();
		} catch (IOException e) {
			throw new IllegalStateException("cannot read " + found, e);
		}
	}

	private static String[] prepend(String first, String[] rest) {
		String[] all = new String[rest.length + 1];
		all[0] = first;
		System.arraycopy(rest, 0, all, 1, rest.length);
		return all;
	}
}
