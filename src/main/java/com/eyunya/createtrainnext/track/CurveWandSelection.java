package com.eyunya.createtrainnext.track;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Where the curve a player is drawing starts, and how far its far end has been turned by hand.
 *
 * <p>{@link #pos} is the block that was clicked, not the anchor the curve will actually leave from.
 * The difference matters when the click landed on Create's own track: the anchor then goes in the
 * block <em>beside</em> it, and which side that is depends on where the curve is going — which is
 * not known until the second click. Storing the click and working the rest out afresh each frame is
 * what lets the same selection serve a curve heading north and one heading south.
 *
 * <p>{@link #angle} is an offset from the tangent {@link CurvePlanner} works out on its own, in
 * degrees, and it is carried here rather than kept on the client so that the server places the
 * curve the player was actually looking at. It resets to zero with every new selection, because an
 * adjustment made to one curve means nothing to the next.
 *
 * <p>The dimension is stored alongside the position and checked before use. A bare BlockPos is
 * resolved against whatever level the next click happens in, so a selection made in one dimension
 * would otherwise happily draw a curve to whatever sat at the same coordinates in another.
 */
public record CurveWandSelection(ResourceKey<Level> dimension, BlockPos pos, float angle) {

	public static final Codec<CurveWandSelection> CODEC = RecordCodecBuilder.create(instance -> instance
		.group(ResourceKey.codec(Registries.DIMENSION)
			.fieldOf("dimension")
			.forGetter(CurveWandSelection::dimension),
			BlockPos.CODEC.fieldOf("pos")
				.forGetter(CurveWandSelection::pos),
			Codec.FLOAT.fieldOf("angle")
				.forGetter(CurveWandSelection::angle))
		.apply(instance, CurveWandSelection::new));

	public static final StreamCodec<ByteBuf, CurveWandSelection> STREAM_CODEC = StreamCodec.composite(
		ResourceKey.streamCodec(Registries.DIMENSION), CurveWandSelection::dimension,
		BlockPos.STREAM_CODEC, CurveWandSelection::pos,
		ByteBufCodecs.FLOAT, CurveWandSelection::angle,
		CurveWandSelection::new);

	public static CurveWandSelection of(Level level, BlockPos pos) {
		return new CurveWandSelection(level.dimension(), pos, 0.0F);
	}

	public CurveWandSelection withAngle(float newAngle) {
		return new CurveWandSelection(dimension, pos, newAngle);
	}

	public boolean isIn(Level level) {
		return dimension.equals(level.dimension());
	}
}
