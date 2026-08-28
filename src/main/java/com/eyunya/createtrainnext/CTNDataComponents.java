package com.eyunya.createtrainnext;

import java.util.function.UnaryOperator;

import com.eyunya.createtrainnext.track.CurveWandSelection;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponentType.Builder;
import net.minecraft.core.registries.Registries;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The data this mod hangs off item stacks.
 *
 * <p>Stack data rather than a per-player map on the server, which is what the retired prototype
 * used. Three things come free with a component that a map has to be written for: it survives a
 * relog and a restart, it is per stack so two wands can be part way through two different curves,
 * and it is sent to the client automatically — which is what lets the curve be drawn as a preview
 * at all, since the client has to know where the curve starts to draw it.
 */
public class CTNDataComponents {

	private static final DeferredRegister.DataComponents DATA_COMPONENTS =
		DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, CreateTrainNext.ID);

	/** The curve a wand is part way through drawing, if it is. Absent means nothing is selected. */
	public static final DataComponentType<CurveWandSelection> CURVE_SELECTION = register("curve_selection",
		builder -> builder.persistent(CurveWandSelection.CODEC)
			.networkSynchronized(CurveWandSelection.STREAM_CODEC));

	private static <T> DataComponentType<T> register(String name, UnaryOperator<Builder<T>> builder) {
		DataComponentType<T> type = builder.apply(DataComponentType.builder())
			.build();
		DATA_COMPONENTS.register(name, () -> type);
		return type;
	}

	public static void register(IEventBus modEventBus) {
		DATA_COMPONENTS.register(modEventBus);
	}

	private CTNDataComponents() {}
}
