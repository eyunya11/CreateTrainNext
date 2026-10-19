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
 * The grade the player has dialled in for the curves they are laying, and whether grade mode is on.
 *
 * <p>Sent for the same reason {@link SetCurveAnglePacket} is: the grade is chosen with the mouse wheel,
 * which only the client sees, and used where the curve is built, which only the server does. The whole
 * state is sent rather than the change to it, so a packet that arrives late, twice or not at all leaves
 * the two sides agreeing rather than a notch apart for the rest of the route.
 */
public record SetCurveGradePacket(int grade, boolean active) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<SetCurveGradePacket> TYPE =
		new CustomPacketPayload.Type<>(CreateTrainNext.asResource("set_curve_grade"));

	public static final StreamCodec<ByteBuf, SetCurveGradePacket> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, SetCurveGradePacket::grade,
		ByteBufCodecs.BOOL, SetCurveGradePacket::active,
		SetCurveGradePacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(SetCurveGradePacket packet, IPayloadContext context) {
		context.enqueueWork(() -> apply(context.player(), packet.grade(), packet.active()));
	}

	/**
	 * Puts the grade on the selection in the player's hand. Split from {@link #handle} so that what
	 * the server decides can be asked of it without a connection.
	 *
	 * @return whether there was a selection to put it on.
	 */
	public static boolean apply(Player player, int grade, boolean active) {
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof CurveWandItem))
			return false;

		CurveWandSelection selection = CurveWandItem.selectionIn(stack, player.level());
		if (selection == null)
			return false;

		// Nothing here trusts the number, as with the angle: it only says how the next curve is aimed,
		// and the curve is checked afresh when it is built. It is held to the limit so that a value
		// from a broken client cannot ask for a grade the planner would never be able to meet.
		int limit = CurvePlanner.maxGradePermille();
		int held = Mth.clamp(grade, -limit, limit);

		// Asking for level from level is the mode being asked to do nothing, so it is taken as the
		// mode being turned off. The client reaches the same answer by itself and the two agree.
		if (held == 0 && CurvePlanner.startsLevel(player.level(), selection.pos()))
			active = false;

		stack.set(CTNDataComponents.CURVE_SELECTION, selection.withGrade(held, active));
		return true;
	}
}
