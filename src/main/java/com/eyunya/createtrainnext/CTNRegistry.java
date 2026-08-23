package com.eyunya.createtrainnext;

import java.util.function.Supplier;

import com.eyunya.createtrainnext.track.AngledTrackAnchorBlock;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;

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
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.STONE)
			.strength(1.5F)
			.sound(SoundType.STONE));

	public static final DeferredItem<BlockItem> ANGLED_TRACK_ANCHOR_ITEM =
		ITEMS.registerSimpleBlockItem("angled_track_anchor", ANGLED_TRACK_ANCHOR);

	// The BlockEntityType is what carries our renderer, so the block itself can stay a plain cube.
	public static final Supplier<BlockEntityType<AngledTrackAnchorBlockEntity>> ANGLED_TRACK_ANCHOR_ENTITY =
		BLOCK_ENTITIES.register("angled_track_anchor", () -> BlockEntityType.Builder
			.of(AngledTrackAnchorBlockEntity::new, ANGLED_TRACK_ANCHOR.get())
			.build(null));

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
		CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
			.title(Component.translatable("itemGroup." + CreateTrainNext.ID))
			.icon(() -> ANGLED_TRACK_ANCHOR_ITEM.get()
				.getDefaultInstance())
			.displayItems((params, output) -> output.accept(ANGLED_TRACK_ANCHOR_ITEM.get()))
			.build());

	public static void register(IEventBus modEventBus) {
		BLOCKS.register(modEventBus);
		ITEMS.register(modEventBus);
		BLOCK_ENTITIES.register(modEventBus);
		CREATIVE_TABS.register(modEventBus);
	}
}
