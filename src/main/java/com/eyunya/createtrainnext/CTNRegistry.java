package com.eyunya.createtrainnext;

import java.util.function.Supplier;

import com.eyunya.createtrainnext.speed.CTNEdgePointTypes;
import com.eyunya.createtrainnext.speed.SpeedLimitBlock;
import com.eyunya.createtrainnext.speed.SpeedLimitBlockEntity;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlock;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurveWandItem;
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

	// The BlockEntityType is what carries our renderer, so the block itself can stay a plain cube.
	public static final Supplier<BlockEntityType<AngledTrackAnchorBlockEntity>> ANGLED_TRACK_ANCHOR_ENTITY =
		BLOCK_ENTITIES.register("angled_track_anchor", () -> BlockEntityType.Builder
			.of(AngledTrackAnchorBlockEntity::new, ANGLED_TRACK_ANCHOR.get())
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

	public static final Supplier<BlockEntityType<SpeedLimitBlockEntity>> TRACK_SPEED_LIMIT_ENTITY =
		BLOCK_ENTITIES.register("track_speed_limit", () -> BlockEntityType.Builder
			.of(SpeedLimitBlockEntity::new, TRACK_SPEED_LIMIT.get())
			.build(null));

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
		CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
			.title(Component.translatable("itemGroup." + CreateTrainNext.ID))
			.icon(() -> CURVE_WAND.get()
				.getDefaultInstance())
			.displayItems((params, output) -> {
				output.accept(CURVE_WAND.get());
				output.accept(ANGLED_TRACK_ANCHOR_ITEM.get());
				output.accept(TRACK_SPEED_LIMIT_ITEM.get());
			})
			.build());

	public static void register(IEventBus modEventBus) {
		BLOCKS.register(modEventBus);
		ITEMS.register(modEventBus);
		BLOCK_ENTITIES.register(modEventBus);
		CREATIVE_TABS.register(modEventBus);
	}
}
