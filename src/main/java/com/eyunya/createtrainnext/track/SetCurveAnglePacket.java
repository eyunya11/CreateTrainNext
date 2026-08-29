package com.eyunya.createtrainnext.track;

import com.eyunya.createtrainnext.CTNDataComponents;
import com.eyunya.createtrainnext.CreateTrainNext;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The player's hand adjustment to the far end of the curve they are drawing.
 *
 * <p>Sent because the angle is chosen with the mouse wheel, which only the client sees, and used
 * where the curve is actually built, which only the server does. Without it the server would place
 * the arc the tool picked on its own and the player would watch their adjustment disappear.
 *
 * <p>The whole angle is sent rather than the change to it, so that a packet arriving late, twice or
 * not at all leaves the two sides agreeing rather than a fixed distance apart for the rest of the
 * curve.
 */
public record SetCurveAnglePacket(float angle) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<SetCurveAnglePacket> TYPE =
		new CustomPacketPayload.Type<>(CreateTrainNext.asResource("set_curve_angle"));

	public static final StreamCodec<ByteBuf, SetCurveAnglePacket> STREAM_CODEC =
		StreamCodec.composite(ByteBufCodecs.FLOAT, SetCurveAnglePacket::angle, SetCurveAnglePacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(SetCurveAnglePacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			Player player = context.player();
			ItemStack stack = player.getMainHandItem();
			if (!(stack.getItem() instanceof CurveWandItem))
				return;

			CurveWandSelection selection = stack.get(CTNDataComponents.CURVE_SELECTION);
			if (selection == null)
				return;

			// Nothing here trusts the number: it turns one end of one curve, and the placement it
			// feeds is checked afresh when the curve is built. Clamping is only so that a value
			// from a broken client cannot make the geometry meaningless.
			stack.set(CTNDataComponents.CURVE_SELECTION,
				selection.withAngle(Mth.clamp(packet.angle(), -180.0F, 180.0F)));
		});
	}
}
