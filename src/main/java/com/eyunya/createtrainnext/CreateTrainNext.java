package com.eyunya.createtrainnext;

import org.slf4j.Logger;

import com.eyunya.createtrainnext.client.CreateTrainNextClient;
import com.eyunya.createtrainnext.dispatch.CTNSchedule;
import com.eyunya.createtrainnext.speed.CTNEdgePointTypes;
import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Entry point for Create Train Next.
 *
 * <p>This is currently a rendering-only experiment: it proves that Create's own curve
 * geometry can be driven at arbitrary end angles, rather than the 45/90 degree steps
 * the vanilla track placement tool allows. Nothing here touches Create's track graph,
 * signals or train logic yet.
 */
@Mod(CreateTrainNext.ID)
public class CreateTrainNext {

	public static final String ID = "createtrainnext";

	public static final Logger LOGGER = LogUtils.getLogger();

	public CreateTrainNext(IEventBus modEventBus, ModContainer modContainer) {
		CTNRegistry.register(modEventBus);
		// Create's edge point types are a static map with no registry event behind them, so this
		// has to be poked by hand rather than deferred like the blocks above.
		CTNEdgePointTypes.register();
		// Same story for schedule conditions: a public static list, not a registry, and both
		// sides need the entry or the client drops the condition when it parses a schedule.
		CTNSchedule.register();
		CTNCommands.init(modEventBus);

		// The config is registered from the client branch because everything in it is client side.
		if (FMLEnvironment.dist == Dist.CLIENT)
			CreateTrainNextClient.init(modEventBus, modContainer);
	}

	public static ResourceLocation asResource(String path) {
		return ResourceLocation.fromNamespaceAndPath(ID, path);
	}
}
