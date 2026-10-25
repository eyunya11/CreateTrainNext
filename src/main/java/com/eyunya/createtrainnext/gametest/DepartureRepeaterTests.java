package com.eyunya.createtrainnext.gametest;

import java.util.Arrays;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.signal.DepartureRepeaterBlock;
import com.eyunya.createtrainnext.signal.DepartureRepeaterBlockEntity;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlockEntity;
import com.eyunya.createtrainnext.signal.SignalAspect;
import com.eyunya.createtrainnext.signal.SignalLookahead;
import com.eyunya.createtrainnext.track.AnchorAxis;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlock;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks the departure repeater: lit while the signal ahead shows anything but 停止, dark at 停止 and with
 * no signal ahead, and its files where the game looks for them.
 *
 * <p>The routes are built as {@code CurveTargetingTests} builds them, and run in a batch of their own for
 * the reason given there.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class DepartureRepeaterTests {

	private static final String EMPTY = "empty";

	private static final String BATCH = "departureRepeater";

	private static final float EAST = 270.0F;

	/** How long Create takes to build a graph out of anchors that have just been linked. */
	private static final int BUILD_TICKS = 8;

	/** Long enough for a signal to find its edge and work out its aspect, and for the repeater to read it. */
	private static final int SETTLE_TICKS = 40;

	/** Lit for every aspect but 停止, and dark when there is nothing to read. */
	@GameTest(template = EMPTY)
	public static void aRepeaterIsLitForEveryAspectButStop(GameTestHelper helper) {
		for (SignalAspect aspect : SignalAspect.values())
			helper.assertTrue(DepartureRepeaterBlockEntity.litFor(aspect) == (aspect != SignalAspect.STOP),
				"a repeater should be " + (aspect == SignalAspect.STOP ? "dark" : "lit") + " for " + aspect);
		helper.assertTrue(!DepartureRepeaterBlockEntity.litFor(null), "a repeater with no signal to read should be dark");
		helper.succeed();
	}

	/**
	 * Where the line divides before the next signal, the repeater and the relay go by the strictest of the
	 * signals down the branches, so a repeater is lit only when every way on has cleared.
	 */
	@GameTest(template = EMPTY)
	public static void theStrictestSignalAheadIsTheOneReadAtAJunction(GameTestHelper helper) {
		helper.assertTrue(SignalLookahead.stricter(SignalAspect.PROCEED, SignalAspect.STOP) == SignalAspect.STOP
			&& SignalLookahead.stricter(SignalAspect.STOP, SignalAspect.PROCEED) == SignalAspect.STOP,
			"停止 should be stricter than 進行 either way round");
		helper.assertTrue(SignalLookahead.stricter(SignalAspect.HIGH_SPEED_PROCEED, SignalAspect.APPROACH) == SignalAspect.APPROACH,
			"注意 should be stricter than 高速進行");
		helper.assertTrue(SignalLookahead.stricter(null, SignalAspect.PROCEED) == SignalAspect.PROCEED
			&& SignalLookahead.stricter(SignalAspect.PROCEED, null) == SignalAspect.PROCEED && SignalLookahead.stricter(null, null) == null,
			"a branch with nothing to read should give way to one that has");
		helper.succeed();
	}

	/**
	 * A repeater on the track before a signal is lit while the signal has cleared, goes dark when it shows
	 * 停止, and keeps what it shows when it is saved. It reads the head itself: a repeater that worked the
	 * aspect out from the clear line would stay lit at 停止.
	 */
	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 120)
	public static void aRepeaterBeforeASignalIsLitWhileItHasCleared(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = route(helper, 6, 14, 22, 30);

		helper.runAfterDelay(BUILD_TICKS, () -> {
			MultiAspectSignalBlockEntity signal = signal(helper, new BlockPos(10, 2, 18), anchors[0], anchors[1]);
			DepartureRepeaterBlockEntity repeater = repeater(helper, new BlockPos(18, 2, 18), anchors[1], anchors[2]);

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				helper.assertTrue(signal.getAspect() == SignalAspect.PROCEED,
					"setup: the signal should show 進行 on an open line, but shows " + signal.getAspect());
				helper.assertTrue(repeater.isLit(), "the repeater should be lit while the signal ahead shows 進行");
				helper.assertTrue(repeater.target.getEdgePoint() == null, "the repeater should not have made itself a point on the track");

				setAspect(helper, signal, SignalAspect.STOP);
				helper.assertTrue(!repeater.resolveLit(), "the repeater should go dark when the signal ahead shows 停止");
				setAspect(helper, signal, SignalAspect.APPROACH);
				helper.assertTrue(repeater.resolveLit(), "the repeater should be lit when the signal ahead shows 注意");

				CompoundTag saved = repeater.saveWithFullMetadata(helper.getLevel()
					.registryAccess());
				DepartureRepeaterBlockEntity fresh = new DepartureRepeaterBlockEntity(repeater.getBlockPos(),
					repeater.getBlockState());
				fresh.loadWithComponents(saved, helper.getLevel()
					.registryAccess());
				helper.assertTrue(fresh.isLit(), "a repeater should be read back lit when it was saved lit");
				helper.succeed();
			});
		});
	}

	/** A repeater with no signal ahead of it, here past the last one on the line, is dark. */
	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 120)
	public static void aRepeaterWithNoSignalAheadIsDark(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = route(helper, 6, 14, 22, 30);

		helper.runAfterDelay(BUILD_TICKS, () -> {
			signal(helper, new BlockPos(26, 2, 18), anchors[2], anchors[3]);
			DepartureRepeaterBlockEntity repeater = repeater(helper, new BlockPos(10, 2, 18), anchors[0], anchors[1]);

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				helper.assertTrue(!repeater.isLit() && !repeater.resolveLit(),
					"a repeater with no signal ahead of it should be dark");
				helper.succeed();
			});
		});
	}

	/**
	 * Poles join the repeater from above, below and behind, and not from the front or the sides. Turned to
	 * face south, its back is north.
	 */
	@GameTest(template = EMPTY)
	public static void polesJoinTheRepeaterFromAboveBelowAndBehind(GameTestHelper helper) {
		BlockState south = CTNRegistry.DEPARTURE_REPEATER.get()
			.defaultBlockState();
		DepartureRepeaterBlock block = CTNRegistry.DEPARTURE_REPEATER.get();
		for (Direction side : Direction.values()) {
			boolean joins = side == Direction.UP || side == Direction.DOWN || side == Direction.NORTH;
			helper.assertTrue(block.connectsToPole(south, side) == joins,
				"a repeater facing south should " + (joins ? "" : "not ") + "be joined by a pole to its " + side);
		}
		helper.succeed();
	}

	/** The block, its item and its files, and its lamp where the code says it is. */
	@GameTest(template = EMPTY)
	public static void theRepeaterHasItsFilesAndItsLampIsWhereTheCodeSays(GameTestHelper helper) {
		String name = "departure_repeater";
		helper.assertTrue(BuiltInRegistries.BLOCK.getKey(CTNRegistry.DEPARTURE_REPEATER.get())
			.equals(CreateTrainNext.asResource(name)), "the repeater should be registered as " + name);
		helper.assertTrue(BuiltInRegistries.ITEM.get(CreateTrainNext.asResource(name)) instanceof TrackTargetingBlockItem item
			&& item.getBlock() == CTNRegistry.DEPARTURE_REPEATER.get(), "the repeater's item should be placed against the track");

		double[] drawn = span(SignalTypeTests.json(helper, "assets", "models/block/" + name, "head.json")
			.getAsJsonArray("elements"));
		helper.assertTrue(Arrays.equals(drawn, DepartureRepeaterBlock.HEAD_BOUNDS), name + "/head.json spans "
			+ Arrays.toString(drawn) + ", but the code says " + Arrays.toString(DepartureRepeaterBlock.HEAD_BOUNDS));
		AABB shape = CTNRegistry.DEPARTURE_REPEATER.get()
			.defaultBlockState()
			.getShape(helper.getLevel(), BlockPos.ZERO)
			.bounds();
		helper.assertTrue(Math.abs(shape.maxZ - DepartureRepeaterBlock.HEAD_BOUNDS[5] / 16) < 1e-6 && shape.minY == 0 && shape.maxY == 1,
			"the repeater's shape should be its pole and its lamp, but is " + shape);
		double[] lit = span(SignalTypeTests.json(helper, "assets", "models/block/" + name, "lit.json")
			.getAsJsonArray("elements"));
		helper.assertTrue(lit[2] >= DepartureRepeaterBlock.HEAD_BOUNDS[5], "the lit end should be in front of the lamp, at "
			+ DepartureRepeaterBlock.HEAD_BOUNDS[5] + " or more, but starts at " + lit[2]);

		SignalTypeTests.model(helper, SignalTypeTests.json(helper, "assets", "blockstates", name + ".json")
			.getAsJsonObject("variants")
			.getAsJsonObject("")
			.get("model")
			.getAsString());
		SignalTypeTests.model(helper, SignalTypeTests.json(helper, "assets", "models/item", name + ".json")
			.get("parent")
			.getAsString());
		helper.assertTrue(SignalTypeTests.json(helper, "data", "loot_table/blocks", name + ".json")
			.toString()
			.contains("\"" + CreateTrainNext.ID + ":" + name + "\""), name + "'s loot table should drop it");
		for (String language : new String[] { "en_us.json", "ja_jp.json" })
			helper.assertTrue(SignalTypeTests.json(helper, "assets", "lang", language)
				.has("block." + CreateTrainNext.ID + "." + name), language + " should name " + name);
		helper.succeed();
	}

	/** A route east through anchors at the given x, along z 16, every curve straight. */
	private static AngledTrackAnchorBlockEntity[] route(GameTestHelper helper, int... xs) {
		AngledTrackAnchorBlockEntity[] anchors = new AngledTrackAnchorBlockEntity[xs.length];
		for (int i = 0; i < xs.length; i++) {
			BlockPos pos = new BlockPos(xs[i], 2, 16);
			helper.setBlock(pos, CTNRegistry.ANGLED_TRACK_ANCHOR.get()
				.defaultBlockState()
				.setValue(AngledTrackAnchorBlock.AXIS, AnchorAxis.Z_ORTHO));
			anchors[i] = (AngledTrackAnchorBlockEntity) helper.getBlockEntity(pos);
		}
		Vec3 heading = AngledTrackAnchorBlockEntity.directionFromYaw(EAST);
		for (int i = 0; i + 1 < anchors.length; i++)
			anchors[i].linkTo(anchors[i + 1], i == 0 ? heading : null, heading);
		return anchors;
	}

	private static MultiAspectSignalBlockEntity signal(GameTestHelper helper, BlockPos relative,
		AngledTrackAnchorBlockEntity holder, AngledTrackAnchorBlockEntity farEnd) {
		helper.setBlock(relative, CTNRegistry.MULTI_ASPECT_SIGNAL.get()
			.defaultBlockState());
		MultiAspectSignalBlockEntity signal = (MultiAspectSignalBlockEntity) helper.getBlockEntity(relative);
		onCurve(helper, signal.edgePoint, signal.getBlockPos(), holder, farEnd.getBlockPos());
		return signal;
	}

	private static DepartureRepeaterBlockEntity repeater(GameTestHelper helper, BlockPos relative,
		AngledTrackAnchorBlockEntity holder, AngledTrackAnchorBlockEntity farEnd) {
		helper.setBlock(relative, Blocks.AIR);
		helper.setBlock(relative, CTNRegistry.DEPARTURE_REPEATER.get()
			.defaultBlockState());
		DepartureRepeaterBlockEntity repeater = (DepartureRepeaterBlockEntity) helper.getBlockEntity(relative);
		onCurve(helper, repeater.target, repeater.getBlockPos(), holder, farEnd.getBlockPos());
		return repeater;
	}

	/**
	 * Points a block's targeting at the middle of the curve held by {@code holder}, as the item that places
	 * it would. Every block here is pointed the same way, which on these routes faces trains running west: a
	 * signal governs them, and a repeater looks west for the signal they meet next. So a repeater reads the
	 * signal to the west of it.
	 */
	private static void onCurve(GameTestHelper helper, TrackTargetingBehaviour<?> target, BlockPos at,
		AngledTrackAnchorBlockEntity holder, BlockPos farEnd) {
		int segment = (int) (holder.getConnections()
			.get(farEnd)
			.getLength() * 2) / 2;
		CompoundTag bezier = new CompoundTag();
		bezier.putInt("Segment", segment);
		bezier.put("Key", NbtUtils.writeBlockPos(farEnd.subtract(at)));

		CompoundTag tag = new CompoundTag();
		tag.putBoolean("TargetDirection", true);
		tag.put("TargetTrack", NbtUtils.writeBlockPos(holder.getBlockPos()
			.subtract(at)));
		tag.put("Bezier", bezier);
		target.read(tag, helper.getLevel()
			.registryAccess(), false);
	}

	/** Makes a head show an aspect at once, by saving it, changing what it saved and reading it back. */
	private static void setAspect(GameTestHelper helper, MultiAspectSignalBlockEntity head, SignalAspect aspect) {
		CompoundTag tag = head.saveWithFullMetadata(helper.getLevel()
			.registryAccess());
		tag.putString("Aspect", aspect.name());
		head.loadWithComponents(tag, helper.getLevel()
			.registryAccess());
	}

	/** The box the elements of a model fill, x0, y0, z0, x1, y1, z1. */
	private static double[] span(JsonArray elements) {
		double[] span = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
			-Double.MAX_VALUE };
		for (JsonElement element : elements) {
			JsonArray from = element.getAsJsonObject()
				.getAsJsonArray("from");
			JsonArray to = element.getAsJsonObject()
				.getAsJsonArray("to");
			for (int axis = 0; axis < 3; axis++) {
				span[axis] = Math.min(span[axis], from.get(axis)
					.getAsDouble());
				span[axis + 3] = Math.max(span[axis + 3], to.get(axis)
					.getAsDouble());
			}
		}
		return span;
	}
}
