package com.eyunya.createtrainnext.track;

import org.jetbrains.annotations.Nullable;

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
 * Where the curve a player is drawing starts, how far its far end has been turned by hand, and the
 * grade the player has asked it to climb at.
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
 * <p>{@link #grade} and {@link #gradeMode} are the opposite: they are what a route is laid <em>at</em>,
 * so they are carried from one curve to the next. {@code grade} is in permille, a minus sign being a
 * descent, and it only means anything while {@code gradeMode} is on; a selection that has not been
 * given one is in the mode every curve was drawn in before there was another, where the far end is
 * wherever it was clicked. See {@link GradeSpec}.
 *
 * <p>The dimension is stored alongside the position and checked before use. A bare BlockPos is
 * resolved against whatever level the next click happens in, so a selection made in one dimension
 * would otherwise happily draw a curve to whatever sat at the same coordinates in another.
 */
public record CurveWandSelection(ResourceKey<Level> dimension, BlockPos pos, float angle, int grade,
	boolean gradeMode) {

	/**
	 * The two fields a wand saved before there was a grade does not have are optional, so that what
	 * is in a player's hand when the mod is updated is read as a selection in the old mode rather
	 * than dropped.
	 */
	public static final Codec<CurveWandSelection> CODEC = RecordCodecBuilder.create(instance -> instance
		.group(ResourceKey.codec(Registries.DIMENSION)
			.fieldOf("dimension")
			.forGetter(CurveWandSelection::dimension),
			BlockPos.CODEC.fieldOf("pos")
				.forGetter(CurveWandSelection::pos),
			Codec.FLOAT.fieldOf("angle")
				.forGetter(CurveWandSelection::angle),
			Codec.INT.optionalFieldOf("grade", 0)
				.forGetter(CurveWandSelection::grade),
			Codec.BOOL.optionalFieldOf("grade_mode", false)
				.forGetter(CurveWandSelection::gradeMode))
		.apply(instance, CurveWandSelection::new));

	public static final StreamCodec<ByteBuf, CurveWandSelection> STREAM_CODEC = StreamCodec.composite(
		ResourceKey.streamCodec(Registries.DIMENSION), CurveWandSelection::dimension,
		BlockPos.STREAM_CODEC, CurveWandSelection::pos,
		ByteBufCodecs.FLOAT, CurveWandSelection::angle,
		ByteBufCodecs.VAR_INT, CurveWandSelection::grade,
		ByteBufCodecs.BOOL, CurveWandSelection::gradeMode,
		CurveWandSelection::new);

	public static CurveWandSelection of(Level level, BlockPos pos) {
		return new CurveWandSelection(level.dimension(), pos, 0.0F, 0, false);
	}

	public CurveWandSelection withAngle(float newAngle) {
		return new CurveWandSelection(dimension, pos, newAngle, grade, gradeMode);
	}

	/**
	 * The same selection laying at a different grade. A selection that is not in grade mode has no
	 * grade to speak of, and is stored as having none so that two that mean the same thing are equal.
	 */
	public CurveWandSelection withGrade(int newGrade, boolean active) {
		return new CurveWandSelection(dimension, pos, angle, active ? newGrade : 0, active);
	}

	/**
	 * The selection that carries a route on from {@code newPos}, the end of the curve this one just
	 * built: the grade goes with it, and the angle, which belonged to the curve, does not.
	 */
	public CurveWandSelection carriedOnTo(BlockPos newPos) {
		return new CurveWandSelection(dimension, newPos, 0.0F, grade, gradeMode);
	}

	/** What the planner is asked to lay this selection at, or null when it is not in grade mode. */
	@Nullable
	public GradeSpec spec() {
		return gradeMode ? new GradeSpec(grade) : null;
	}

	public boolean isIn(Level level) {
		return dimension.equals(level.dimension());
	}
}
