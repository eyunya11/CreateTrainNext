package com.eyunya.createtrainnext.track;

import java.util.List;

import com.eyunya.createtrainnext.CTNRegistry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A post that lays one dead straight run of track at a bearing you give it.
 *
 * <p>The curve wand aims by eye, which is right for following terrain and wrong for the other half
 * of railway building: a line that has to leave at a stated angle, because it is meeting something
 * a long way off. This holds two numbers — a bearing and a length — and turns them into track.
 *
 * <p>The bearing is Minecraft's own yaw, the convention the anchors store and the wand reports, so
 * a bearing read off one can be typed into the other. Zero is south and it grows clockwise.
 *
 * <p>What comes out is <em>exactly</em> straight and <em>approximately</em> the bearing asked for,
 * which is the way round that matters. An anchor puts its node at its block's centre, so a line can
 * only run between two of those, and one rarely sits exactly on a given bearing. Aiming both ends
 * along the line between the two centres is what makes the track straight rather than a very
 * shallow S; the bearing that produces is reported back, so the difference is known rather than
 * hidden. Longer lines land closer — see {@link CurvePlanner#planStraight}.
 */
public class SurveyMarkerBlockEntity extends SmartBlockEntity {

	/** Whole degrees. Finer than that is below what the block grid can express anyway. */
	public static final int MIN_BEARING = 0;

	public static final int MAX_BEARING = 359;

	public static final int MIN_LENGTH = 3;

	public static final int MAX_LENGTH = 128;

	public static final int DEFAULT_LENGTH = 16;

	private static final String TAG_LENGTH = "Length";

	public ScrollValueBehaviour bearing;

	/**
	 * Not a second {@link ScrollValueBehaviour}, though it reads like one.
	 *
	 * <p>{@code SmartBlockEntity} keeps its behaviours in a map keyed by {@code BehaviourType}, and
	 * every {@code ScrollValueBehaviour} shares one type — so a second would quietly displace the
	 * first rather than sit beside it. The bearing is the value that gets nudged over and over
	 * while a line is being aimed, so it is the one that earns the scroll box; the length is set
	 * once per line and lives in the screen.
	 */
	private int length = DEFAULT_LENGTH;

	public SurveyMarkerBlockEntity(BlockPos pos, BlockState state) {
		super(CTNRegistry.SURVEY_MARKER_ENTITY.get(), pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		bearing = new ScrollValueBehaviour(Component.translatable("createtrainnext.survey_marker.bearing"), this,
			new BearingValueBox());
		bearing.between(MIN_BEARING, MAX_BEARING);
		bearing.value = 0;
		behaviours.add(bearing);
	}

	public int getBearing() {
		return bearing == null ? 0 : bearing.getValue();
	}

	public int getLength() {
		return length;
	}

	/** Applies both numbers at once, which is what the screen sends when it is done with. */
	public void configure(int newBearing, int newLength) {
		if (bearing != null)
			bearing.setValue(Mth.clamp(newBearing, MIN_BEARING, MAX_BEARING));
		length = Mth.clamp(newLength, MIN_LENGTH, MAX_LENGTH);
		setChanged();
		if (level != null)
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		tag.putInt(TAG_LENGTH, length);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		// Guarded rather than read unconditionally: a marker saved before the length existed would
		// read zero, come out under the minimum, and be refused for a reason nothing on the block
		// explains. Absent means never set, which is the default rather than nothing.
		if (tag.contains(TAG_LENGTH))
			length = Mth.clamp(tag.getInt(TAG_LENGTH), MIN_LENGTH, MAX_LENGTH);
	}

	/**
	 * The scroll box, floating just above the post.
	 *
	 * <p>Not a {@code Sided} transform. A marker has no facing, and the bearing it holds is a
	 * number about the world rather than about any of the block's own faces — so there is one box,
	 * above the post, rather than four saying the same thing.
	 */
	private static class BearingValueBox extends ValueBoxTransform {

		@Override
		public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
			return new Vec3(0.5, 15 / 16.0, 0.5);
		}

		@Override
		public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
			// Left where it lies. Turning it would only pick a side for a value that has nothing to
			// do with any of them.
		}
	}
}
