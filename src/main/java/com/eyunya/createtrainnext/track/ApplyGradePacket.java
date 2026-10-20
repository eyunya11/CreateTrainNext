package com.eyunya.createtrainnext.track;

import com.eyunya.createtrainnext.CreateTrainNext;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * What a player typed into the grade screen: a grade, and optionally how far to lay a curve at it.
 *
 * <p>The grade is set the way the wheel sets it, and there is one rule for what that does, in
 * {@link SetCurveGradePacket}. A distance, if there is one, is then a curve to lay from the end of the
 * route straight ahead, since there is no block to have aimed at: see {@link CurvePlanner#planByDistance}.
 * Both are sent as one packet because they are one act — a grade set and then a curve laid at some
 * other grade, because the first packet was late, is not what was typed.
 *
 * <p>The distance is zero when the box was left empty, which sets the grade and lays nothing.
 */
public record ApplyGradePacket(int grade, int length) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<ApplyGradePacket> TYPE =
		new CustomPacketPayload.Type<>(CreateTrainNext.asResource("apply_grade"));

	public static final StreamCodec<ByteBuf, ApplyGradePacket> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, ApplyGradePacket::grade,
		ByteBufCodecs.VAR_INT, ApplyGradePacket::length,
		ApplyGradePacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(ApplyGradePacket packet, IPayloadContext context) {
		context.enqueueWork(() -> apply(context.player(), packet.grade(), packet.length()));
	}

	/**
	 * Sets the grade on the selection in the player's hand, and lays a curve at it if a distance was given.
	 * Split from {@link #handle} so that what the server decides can be asked of it without a connection.
	 *
	 * @return whether there was a selection to act on. A curve that could not be laid is not a failure of
	 *         this: the grade was set, and the player is told why there is no curve.
	 */
	public static boolean apply(Player player, int grade, int length) {
		if (!SetCurveGradePacket.apply(player, grade, true))
			return false;

		// The selection as the grade has left it: held to the limit, and off if it asked for level from level.
		ItemStack stack = player.getMainHandItem();
		CurveWandSelection selection = CurveWandItem.selectionIn(stack, player.level());
		if (selection == null)
			return false;

		if (length <= 0) {
			if (selection.gradeMode())
				CurveWandItem.message(player, "grade_set", String.format("%+d", selection.grade()));
			else
				CurveWandItem.message(player, "grade_off");
			return true;
		}

		// How far is checked against the limit on track, by the planner, which refuses what is too long.
		CurveWandItem.layByDistance(player.level(), player, stack, selection, length);
		return true;
	}
}
