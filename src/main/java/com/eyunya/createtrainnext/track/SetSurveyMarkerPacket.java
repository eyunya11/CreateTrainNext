package com.eyunya.createtrainnext.track;

import com.eyunya.createtrainnext.CreateTrainNext;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The numbers a player typed into a {@link SurveyMarkerBlockEntity}'s screen.
 *
 * <p>All are sent together because they are set together — the screen is a single act of
 * configuration, not three, and sending one without the others would leave a moment where the marker
 * describes a line nobody asked for.
 */
public record SetSurveyMarkerPacket(BlockPos pos, int bearing, int length, int grade)
	implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<SetSurveyMarkerPacket> TYPE =
		new CustomPacketPayload.Type<>(CreateTrainNext.asResource("set_survey_marker"));

	public static final StreamCodec<ByteBuf, SetSurveyMarkerPacket> STREAM_CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, SetSurveyMarkerPacket::pos,
		ByteBufCodecs.VAR_INT, SetSurveyMarkerPacket::bearing,
		ByteBufCodecs.VAR_INT, SetSurveyMarkerPacket::length,
		ByteBufCodecs.VAR_INT, SetSurveyMarkerPacket::grade,
		SetSurveyMarkerPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(SetSurveyMarkerPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> apply(context.player(), packet));
	}

	/**
	 * Configures the marker the packet is about, if the player could be at it. Split from {@link #handle}
	 * so that what the server decides can be asked of it without a connection.
	 *
	 * @return whether a marker was configured.
	 */
	public static boolean apply(Player player, SetSurveyMarkerPacket packet) {
		// A screen can be left open while its player walks away, or be sent by something that
		// never had one. Both are answered by checking the marker is still in reach, which is
		// the same thing the server asks before any other block interaction.
		if (!player.level()
			.isLoaded(packet.pos()) || player.blockPosition()
				.distSqr(packet.pos()) > 64 * 64)
			return false;

		if (!(player.level()
			.getBlockEntity(packet.pos()) instanceof SurveyMarkerBlockEntity marker))
			return false;

		// Clamped inside configure, so a value typed past the limits lands on the limit
		// rather than being thrown away with nothing said.
		marker.configure(packet.bearing(), packet.length(), packet.grade());
		return true;
	}
}
