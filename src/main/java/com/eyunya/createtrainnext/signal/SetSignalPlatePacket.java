package com.eyunya.createtrainnext.signal;

import com.eyunya.createtrainnext.CreateTrainNext;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/** What a player typed into a {@link SignalPlateBlockEntity}'s screen. */
public record SetSignalPlatePacket(BlockPos pos, String text) implements CustomPacketPayload {

	/**
	 * The longest text the packet carries, in UTF-8 bytes. Far more than any plate holds, so that the plate
	 * and not the packet decides how much is kept.
	 */
	private static final int MAX_BYTES = 256;

	/**
	 * How far past the reach of their hand a player may be. A screen stays open while its player is
	 * nudged about, and is answered all the same.
	 */
	private static final double REACH_SLACK = 2;

	public static final CustomPacketPayload.Type<SetSignalPlatePacket> TYPE =
		new CustomPacketPayload.Type<>(CreateTrainNext.asResource("set_signal_plate"));

	public static final StreamCodec<ByteBuf, SetSignalPlatePacket> STREAM_CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, SetSignalPlatePacket::pos,
		ByteBufCodecs.stringUtf8(MAX_BYTES), SetSignalPlatePacket::text,
		SetSignalPlatePacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(SetSignalPlatePacket packet, IPayloadContext context) {
		context.enqueueWork(() -> apply(context.player(), packet));
	}

	/**
	 * Writes on the plate the packet is about, if the player could right click it now and may change
	 * blocks. Split from {@link #handle} so that what the server decides can be asked of it without a
	 * connection.
	 *
	 * @return whether a plate was written on.
	 */
	public static boolean apply(Player player, SetSignalPlatePacket packet) {
		// The same questions the server asks of any other right click on a block: is it there, is it in
		// reach, and is this player allowed to change things.
		if (!player.level()
			.isLoaded(packet.pos()) || !player.mayBuild() || !player.canInteractWithBlock(packet.pos(), REACH_SLACK))
			return false;

		if (!(player.level()
			.getBlockEntity(packet.pos()) instanceof SignalPlateBlockEntity plate))
			return false;

		plate.setText(packet.text());
		return true;
	}
}
