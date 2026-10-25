package com.eyunya.createtrainnext;

import java.util.function.Supplier;

import com.eyunya.createtrainnext.pole.SignalPoleBlock;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlock;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlockEntity;
import com.eyunya.createtrainnext.signal.RelaySignalBlock;
import com.eyunya.createtrainnext.signal.PlateKind;
import com.eyunya.createtrainnext.signal.RelaySignalBlockEntity;
import com.eyunya.createtrainnext.signal.SignalPlateBlock;
import com.eyunya.createtrainnext.signal.SignalPlateBlockEntity;
import com.eyunya.createtrainnext.signal.SignalType;
import com.eyunya.createtrainnext.speed.CTNEdgePointTypes;
import com.eyunya.createtrainnext.speed.SpeedLimitBlock;
import com.eyunya.createtrainnext.speed.SpeedLimitBlockEntity;
import com.eyunya.createtrainnext.speed.SpeedReleaseBlock;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlock;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.eyunya.createtrainnext.track.CurveFillerBlock;
import com.eyunya.createtrainnext.track.CurveFillerBlockEntity;
import com.eyunya.createtrainnext.track.CurveWandItem;
import com.eyunya.createtrainnext.track.SurveyMarkerBlock;
import com.eyunya.createtrainnext.track.SurveyMarkerBlockEntity;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CTNRegistry {

	public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateTrainNext.ID);
	public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateTrainNext.ID);
	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
		DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateTrainNext.ID);
	public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
		DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateTrainNext.ID);

	public static final DeferredBlock<AngledTrackAnchorBlock> ANGLED_TRACK_ANCHOR = BLOCKS.registerBlock(
		"angled_track_anchor",
		AngledTrackAnchorBlock::new,
		// Mirrors the properties Create gives its own track block. noOcclusion is the important
		// one: without it the game treats this as a full opaque cube and culls the faces of
		// neighbouring blocks, so you see straight through the ground around the anchor.
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.METAL)
			.strength(0.8F)
			.sound(SoundType.METAL)
			.noOcclusion()
			.forceSolidOn());

	public static final DeferredItem<BlockItem> ANGLED_TRACK_ANCHOR_ITEM =
		ITEMS.registerSimpleBlockItem("angled_track_anchor", ANGLED_TRACK_ANCHOR);

	/**
	 * The tool the whole feature is actually used through.
	 *
	 * <p>The anchor above is still an item, and still placeable by hand, but it is no longer how a
	 * route is built — the wand places one wherever a curve needs an end. Keeping it around is for
	 * the case the wand is bad at: putting an anchor on an exact block, before deciding where the
	 * line goes.
	 */
	public static final DeferredItem<CurveWandItem> CURVE_WAND = ITEMS.registerItem("curve_wand", CurveWandItem::new);

	/**
	 * The invisible floor laid along a curve, so a curve is something to walk on rather than
	 * something to fall through. Never in the creative tab: it is placed by the anchors, and an
	 * item that put one down by hand would only be a way to leave one somewhere nothing renews it.
	 */
	public static final DeferredBlock<CurveFillerBlock> CURVE_FILLER = BLOCKS.registerBlock(
		"curve_filler",
		CurveFillerBlock::new,
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.NONE)
			.strength(0.1F)
			.sound(SoundType.METAL));

	public static final Supplier<BlockEntityType<CurveFillerBlockEntity>> CURVE_FILLER_ENTITY =
		BLOCK_ENTITIES.register("curve_filler", () -> BlockEntityType.Builder
			.of(CurveFillerBlockEntity::new, CURVE_FILLER.get())
			.build(null));

	// The BlockEntityType is what carries our renderer, so the block itself can stay a plain cube.
	public static final Supplier<BlockEntityType<AngledTrackAnchorBlockEntity>> ANGLED_TRACK_ANCHOR_ENTITY =
		BLOCK_ENTITIES.register("angled_track_anchor", () -> BlockEntityType.Builder
			.of(AngledTrackAnchorBlockEntity::new, ANGLED_TRACK_ANCHOR.get())
			.build(null));

	/**
	 * The post that lays a straight run at a stated bearing, for the half of railway building the
	 * wand is bad at: a line that has to leave at an angle rather than towards a place.
	 */
	public static final DeferredBlock<SurveyMarkerBlock> SURVEY_MARKER = BLOCKS.registerBlock(
		"survey_marker",
		SurveyMarkerBlock::new,
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.METAL)
			.strength(0.8F)
			.sound(SoundType.NETHERITE_BLOCK)
			.noOcclusion());

	public static final DeferredItem<BlockItem> SURVEY_MARKER_ITEM =
		ITEMS.registerSimpleBlockItem("survey_marker", SURVEY_MARKER);

	public static final Supplier<BlockEntityType<SurveyMarkerBlockEntity>> SURVEY_MARKER_ENTITY =
		BLOCK_ENTITIES.register("survey_marker", () -> BlockEntityType.Builder
			.of(SurveyMarkerBlockEntity::new, SURVEY_MARKER.get())
			.build(null));

	public static final DeferredBlock<SpeedLimitBlock> TRACK_SPEED_LIMIT = BLOCKS.registerBlock(
		"track_speed_limit",
		SpeedLimitBlock::new,
		// Matched to Create's own trackside blocks, so a speed limit breaks and sounds like the
		// signal standing next to it. noOcclusion because the sign is a post, not a full cube.
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.PODZOL)
			.strength(0.8F)
			.sound(SoundType.NETHERITE_BLOCK)
			.noOcclusion());

	/**
	 * Not a plain BlockItem: {@link TrackTargetingBlockItem} is what turns placement into Create's
	 * two-step "click the track, then click where the block goes", and it is the item rather than
	 * the block that decides which kind of point gets created.
	 */
	public static final DeferredItem<TrackTargetingBlockItem> TRACK_SPEED_LIMIT_ITEM =
		ITEMS.registerItem("track_speed_limit", properties -> new TrackTargetingBlockItem(TRACK_SPEED_LIMIT.get(),
			properties, CTNEdgePointTypes.SPEED_LIMIT));

	/**
	 * The sign that ends a limit. The same block entity and the same kind of point as the limit sign:
	 * to a train it is a limit as high as any train goes, which is how a limit is lifted.
	 */
	public static final DeferredBlock<SpeedReleaseBlock> TRACK_SPEED_RELEASE = BLOCKS.registerBlock(
		"track_speed_release",
		SpeedReleaseBlock::new,
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.PODZOL)
			.strength(0.8F)
			.sound(SoundType.NETHERITE_BLOCK)
			.noOcclusion());

	public static final DeferredItem<TrackTargetingBlockItem> TRACK_SPEED_RELEASE_ITEM =
		ITEMS.registerItem("track_speed_release", properties -> new TrackTargetingBlockItem(TRACK_SPEED_RELEASE.get(),
			properties, CTNEdgePointTypes.SPEED_LIMIT));

	public static final Supplier<BlockEntityType<SpeedLimitBlockEntity>> TRACK_SPEED_LIMIT_ENTITY =
		BLOCK_ENTITIES.register("track_speed_limit", () -> BlockEntityType.Builder
			.of(SpeedLimitBlockEntity::new, TRACK_SPEED_LIMIT.get(), TRACK_SPEED_RELEASE.get())
			.build(null));

	public static final DeferredBlock<SignalPoleBlock> SIGNAL_POLE = BLOCKS.registerBlock(
		"signal_pole",
		SignalPoleBlock::new,
		// noOcclusion because a two pixel mast is nowhere near a full cube: without it the game
		// culls the faces of everything it touches and the ground around a planted pole goes
		// see-through.
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.METAL)
			.strength(0.8F)
			.sound(SoundType.NETHERITE_BLOCK)
			.noOcclusion());

	public static final DeferredItem<BlockItem> SIGNAL_POLE_ITEM =
		ITEMS.registerSimpleBlockItem("signal_pole", SIGNAL_POLE);

	/**
	 * The signal heads, one block and one item for each type of {@link SignalType}. The five-aspect
	 * is the first and keeps the name it had when it was the only one.
	 *
	 * <p>The names are written out rather than taken from {@link SignalType#blockName()} in a loop,
	 * because {@code check_readmes.py} finds the items by their literal names here. A test holds the
	 * two together.
	 */
	public static final DeferredBlock<MultiAspectSignalBlock> MULTI_ASPECT_SIGNAL_2 =
		signalBlock("multi_aspect_signal_2", SignalType.TWO);

	public static final DeferredBlock<MultiAspectSignalBlock> MULTI_ASPECT_SIGNAL_3 =
		signalBlock("multi_aspect_signal_3", SignalType.THREE);

	public static final DeferredBlock<MultiAspectSignalBlock> MULTI_ASPECT_SIGNAL_4 =
		signalBlock("multi_aspect_signal_4", SignalType.FOUR);

	public static final DeferredBlock<MultiAspectSignalBlock> MULTI_ASPECT_SIGNAL =
		signalBlock("multi_aspect_signal", SignalType.FIVE);

	public static final DeferredBlock<MultiAspectSignalBlock> MULTI_ASPECT_SIGNAL_6 =
		signalBlock("multi_aspect_signal_6", SignalType.SIX);

	/**
	 * Built on Create's own SIGNAL point type rather than one of this mod's. That is what makes
	 * trains obey it: Create's navigation looks for {@code SignalBoundary} by its concrete class,
	 * so a signal of any other type would be invisible to braking and block reservation alike.
	 */
	public static final DeferredItem<TrackTargetingBlockItem> MULTI_ASPECT_SIGNAL_2_ITEM =
		ITEMS.registerItem("multi_aspect_signal_2", properties -> new TrackTargetingBlockItem(
			MULTI_ASPECT_SIGNAL_2.get(), properties, EdgePointType.SIGNAL));

	public static final DeferredItem<TrackTargetingBlockItem> MULTI_ASPECT_SIGNAL_3_ITEM =
		ITEMS.registerItem("multi_aspect_signal_3", properties -> new TrackTargetingBlockItem(
			MULTI_ASPECT_SIGNAL_3.get(), properties, EdgePointType.SIGNAL));

	public static final DeferredItem<TrackTargetingBlockItem> MULTI_ASPECT_SIGNAL_4_ITEM =
		ITEMS.registerItem("multi_aspect_signal_4", properties -> new TrackTargetingBlockItem(
			MULTI_ASPECT_SIGNAL_4.get(), properties, EdgePointType.SIGNAL));

	public static final DeferredItem<TrackTargetingBlockItem> MULTI_ASPECT_SIGNAL_ITEM =
		ITEMS.registerItem("multi_aspect_signal", properties -> new TrackTargetingBlockItem(
			MULTI_ASPECT_SIGNAL.get(), properties, EdgePointType.SIGNAL));

	public static final DeferredItem<TrackTargetingBlockItem> MULTI_ASPECT_SIGNAL_6_ITEM =
		ITEMS.registerItem("multi_aspect_signal_6", properties -> new TrackTargetingBlockItem(
			MULTI_ASPECT_SIGNAL_6.get(), properties, EdgePointType.SIGNAL));

	/** One block entity type for every head: they differ in their block, and the block entity asks it. */
	public static final Supplier<BlockEntityType<MultiAspectSignalBlockEntity>> MULTI_ASPECT_SIGNAL_ENTITY =
		BLOCK_ENTITIES.register("multi_aspect_signal", () -> BlockEntityType.Builder
			.of(MultiAspectSignalBlockEntity::new, MULTI_ASPECT_SIGNAL_2.get(), MULTI_ASPECT_SIGNAL_3.get(),
				MULTI_ASPECT_SIGNAL_4.get(), MULTI_ASPECT_SIGNAL.get(), MULTI_ASPECT_SIGNAL_6.get())
			.build(null));

	public static final DeferredBlock<RelaySignalBlock> RELAY_SIGNAL = BLOCKS.registerBlock(
		"relay_signal",
		RelaySignalBlock::new,
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.PODZOL)
			.strength(0.8F)
			.sound(SoundType.NETHERITE_BLOCK)
			.noOcclusion());

	/**
	 * Placed like a signal, and pointedly not one.
	 *
	 * <p>{@link TrackTargetingBlockItem} is used for the placement it gives — click the track, then
	 * click where the block goes — and the point type handed to it decides nothing about the block:
	 * {@code RelaySignalBlockEntity} never creates a point of any type, which is what keeps a relay
	 * out of the track graph and therefore out of every train's way. SIGNAL is passed because it is
	 * the type the item's own placement check should use, so a relay cannot be dropped on top of an
	 * existing signal's spot.
	 */
	public static final DeferredItem<TrackTargetingBlockItem> RELAY_SIGNAL_ITEM =
		ITEMS.registerItem("relay_signal", properties -> new TrackTargetingBlockItem(RELAY_SIGNAL.get(), properties,
			EdgePointType.SIGNAL));

	public static final Supplier<BlockEntityType<RelaySignalBlockEntity>> RELAY_SIGNAL_ENTITY =
		BLOCK_ENTITIES.register("relay_signal", () -> BlockEntityType.Builder
			.of(RelaySignalBlockEntity::new, RELAY_SIGNAL.get())
			.build(null));

	/**
	 * The plates on a signal's pole, one block and item per {@link PlateKind}, with their names written out
	 * for {@code check_readmes.py} as the signals' are. Plain block items: a plate is put on a pole, not
	 * against the track.
	 */
	public static final DeferredBlock<SignalPlateBlock> SIGNAL_NUMBER_PLATE =
		plateBlock("signal_number_plate", PlateKind.NUMBER);

	public static final DeferredItem<BlockItem> SIGNAL_NUMBER_PLATE_ITEM =
		ITEMS.registerSimpleBlockItem("signal_number_plate", SIGNAL_NUMBER_PLATE);

	public static final DeferredBlock<SignalPlateBlock> SIGNAL_CROWN_PLATE =
		plateBlock("signal_crown_plate", PlateKind.CROWN);

	public static final DeferredItem<BlockItem> SIGNAL_CROWN_PLATE_ITEM =
		ITEMS.registerSimpleBlockItem("signal_crown_plate", SIGNAL_CROWN_PLATE);

	public static final Supplier<BlockEntityType<SignalPlateBlockEntity>> SIGNAL_PLATE_ENTITY =
		BLOCK_ENTITIES.register("signal_plate", () -> BlockEntityType.Builder
			.of(SignalPlateBlockEntity::new, SIGNAL_NUMBER_PLATE.get(), SIGNAL_CROWN_PLATE.get())
			.build(null));

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
		CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
			.title(Component.translatable("itemGroup." + CreateTrainNext.ID))
			.icon(() -> CURVE_WAND.get()
				.getDefaultInstance())
			.displayItems((params, output) -> {
				output.accept(CURVE_WAND.get());
				output.accept(ANGLED_TRACK_ANCHOR_ITEM.get());
				output.accept(SURVEY_MARKER_ITEM.get());
				output.accept(TRACK_SPEED_LIMIT_ITEM.get());
				output.accept(TRACK_SPEED_RELEASE_ITEM.get());
				output.accept(SIGNAL_POLE_ITEM.get());
				output.accept(MULTI_ASPECT_SIGNAL_2_ITEM.get());
				output.accept(MULTI_ASPECT_SIGNAL_3_ITEM.get());
				output.accept(MULTI_ASPECT_SIGNAL_4_ITEM.get());
				output.accept(MULTI_ASPECT_SIGNAL_ITEM.get());
				output.accept(MULTI_ASPECT_SIGNAL_6_ITEM.get());
				output.accept(RELAY_SIGNAL_ITEM.get());
				output.accept(SIGNAL_NUMBER_PLATE_ITEM.get());
				output.accept(SIGNAL_CROWN_PLATE_ITEM.get());
			})
			.build());

	private static DeferredBlock<MultiAspectSignalBlock> signalBlock(String name, SignalType type) {
		return BLOCKS.registerBlock(name, properties -> new MultiAspectSignalBlock(properties, type),
			BlockBehaviour.Properties.of()
				.mapColor(MapColor.PODZOL)
				.strength(0.8F)
				.sound(SoundType.NETHERITE_BLOCK)
				.noOcclusion());
	}

	private static DeferredBlock<SignalPlateBlock> plateBlock(String name, PlateKind kind) {
		return BLOCKS.registerBlock(name, properties -> new SignalPlateBlock(properties, kind),
			BlockBehaviour.Properties.of()
				.mapColor(MapColor.METAL)
				.strength(0.8F)
				.sound(SoundType.NETHERITE_BLOCK)
				.noOcclusion());
	}

	public static void register(IEventBus modEventBus) {
		BLOCKS.register(modEventBus);
		ITEMS.register(modEventBus);
		BLOCK_ENTITIES.register(modEventBus);
		CREATIVE_TABS.register(modEventBus);
	}
}
