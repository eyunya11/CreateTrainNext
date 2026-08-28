package com.eyunya.createtrainnext;

import org.slf4j.Logger;

import com.eyunya.createtrainnext.client.CreateTrainNextClient;
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
 * <p>The mod gives Create's track curves that leave at any angle, rather than the 45 and 90
 * degree steps its own placement tool allows, and a wand to draw them with. The geometry runs
 * through Create's own bezier and track graph, so trains drive the result as ordinary track.
 */
@Mod(CreateTrainNext.ID)
public class CreateTrainNext {

	public static final String ID = "createtrainnext";

	public static final Logger LOGGER = LogUtils.getLogger();

	public CreateTrainNext(IEventBus modEventBus, ModContainer modContainer) {
		CTNRegistry.register(modEventBus);
		CTNDataComponents.register(modEventBus);
		CTNNetwork.init(modEventBus);
		// Create's edge point types are a static map with no registry event behind them, so this
		// has to be poked by hand rather than deferred like the blocks above.
		CTNEdgePointTypes.register();
		CTNCommands.init(modEventBus);

		// The config is registered from the client branch because everything in it is client side.
		if (FMLEnvironment.dist == Dist.CLIENT)
			CreateTrainNextClient.init(modEventBus, modContainer);
	}

	public static ResourceLocation asResource(String path) {
		return ResourceLocation.fromNamespaceAndPath(ID, path);
	}
}
