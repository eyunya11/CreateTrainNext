package com.eyunya.createtrainnext.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.simibubi.create.content.trains.track.CurvedTrackDestroyPacket;
import com.simibubi.create.content.trains.track.TrackBlockEntity;

import net.minecraft.server.level.ServerPlayer;

/**
 * Stops Create's packet for breaking a curve by hand from acting on an anchor's curve.
 *
 * <p>An anchor is a {@code TrackBlockEntity} so that its curves can be picked, which also makes it
 * something the packet will act on, for whoever sends it. Around its one call to
 * {@code removeConnection} the packet takes the block's nodes out of the graph, hands the curve's
 * track to the player or drops it, and draws the curve breaking, and none of that is the call's
 * to refuse. A curve here is its links, and the way to undo one is the wand.
 *
 * <p>Checked on the server because nothing a client does can be relied on to keep it from being
 * sent: the client stops itself from starting to break an anchor's curve, but a packet is a packet.
 */
@Mixin(value = CurvedTrackDestroyPacket.class, remap = false)
public class CurvedTrackDestroyPacketMixin {

	@Inject(
		method = "applySettings(Lnet/minecraft/server/level/ServerPlayer;Lcom/simibubi/create/content/trains/track/TrackBlockEntity;)V",
		at = @At("HEAD"), cancellable = true)
	private void createtrainnext$leaveAnchorsAlone(ServerPlayer player, TrackBlockEntity be, CallbackInfo ci) {
		if (be instanceof AngledTrackAnchorBlockEntity)
			ci.cancel();
	}
}
