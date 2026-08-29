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
 * The two numbers a player typed into a {@link SurveyMarkerBlockEntity}'s screen.
 *
 * <p>Both are sent together because they are set together — the screen is a single act of
 * configuration, not two, and sending one without the other would leave a moment where the marker
 * describes a line nobody asked for.
 */
public record SetSurveyMarkerPacket(BlockPos pos, int bearing, int length) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<SetSurveyMarkerPacket> TYPE =
		new CustomPacketPayload.Type<>(CreateTrainNext.asResource("set_survey_marker"));

	public static final StreamCodec<ByteBuf, SetSurveyMarkerPacket> STREAM_CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, SetSurveyMarkerPacket::pos,
		ByteBufCodecs.VAR_INT, SetSurveyMarkerPacket::bearing,
		ByteBufCodecs.VAR_INT, SetSurveyMarkerPacket::length,
		SetSurveyMarkerPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(SetSurveyMarkerPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			Player player = context.player();

			// A screen can be left open while its player walks away, or be sent by something that
			// never had one. Both are answered by checking the marker is still in reach, which is
			// the same thing the server asks before any other block interaction.
			if (!player.level()
				.isLoaded(packet.pos()) || player.blockPosition()
					.distSqr(packet.pos()) > 64 * 64)
				return;

			if (player.level()
				.getBlockEntity(packet.pos()) instanceof SurveyMarkerBlockEntity marker)
				// Clamped inside configure, so a value typed past the limits lands on the limit
				// rather than being thrown away with nothing said.
				marker.configure(packet.bearing(), packet.length());
		});
	}
}
