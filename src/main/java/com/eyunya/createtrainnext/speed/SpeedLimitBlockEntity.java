package com.eyunya.createtrainnext.speed;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNRegistry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.api.contraption.transformable.TransformableBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Holds which point on the track graph a {@link SpeedLimitBlock} governs, and what limit to apply
 * there.
 *
 * <p>The target is {@link TrackTargetingBehaviour}'s doing. It reads what the item wrote into the
 * block entity's NBT at placement, resolves it to a graph location once the surrounding track has
 * been walked, and creates the {@link SpeedLimitEdgePoint} there — which can take a few ticks after
 * placement, so {@link #getSpeedLimitPoint()} returns null in the meantime and callers have to cope
 * with that.
 *
 * <p>The limit itself is a {@link ScrollValueBehaviour}, which is Create's standard value box:
 * looking at the sign draws the number on its face, and holding right click on it opens the slider.
 * All of that input handling is Create's and works on any {@code SmartBlockEntity}, so there is
 * nothing to register for it.
 */
public class SpeedLimitBlockEntity extends SmartBlockEntity implements TransformableBlockEntity {

	public TrackTargetingBehaviour<SpeedLimitEdgePoint> edgePoint;
	public ScrollValueBehaviour limit;

	public SpeedLimitBlockEntity(BlockPos pos, BlockState state) {
		super(CTNRegistry.TRACK_SPEED_LIMIT_ENTITY.get(), pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		behaviours.add(edgePoint = new TrackTargetingBehaviour<>(this, CTNEdgePointTypes.SPEED_LIMIT));

		limit = new SpeedLimitScrollBehaviour(Component.translatable("createtrainnext.speed_limit.limit"), this,
			new SpeedLimitValueBox());
		limit.between(SpeedLimitEdgePoint.MIN_LIMIT, SpeedLimitEdgePoint.maxLimit());
		limit.withFormatter(SpeedLimitBlockEntity::format);
		limit.value = SpeedLimitEdgePoint.DEFAULT_LIMIT;
		limit.withCallback(this::onLimitChanged);
		behaviours.add(limit);
	}

	/** The limit in km/h. */
	public int getLimit() {
		return limit.getValue();
	}

	/** A limit as it is written wherever it is shown. */
	static String format(int kmh) {
		return kmh + " km/h";
	}

	/** The point this sign governs, or null while it has yet to resolve one. */
	@Nullable
	public SpeedLimitEdgePoint getSpeedLimitPoint() {
		return edgePoint.getEdgePoint();
	}

	private void onLimitChanged(int newLimit) {
		if (level == null || level.isClientSide())
			return;
		// Only pushes to a point that already exists. One created later reads the value back off
		// this block entity instead, in SpeedLimitEdgePoint#blockEntityAdded.
		SpeedLimitEdgePoint point = getSpeedLimitPoint();
		if (point != null)
			point.setLimit(newLimit);
	}

	@Override
	protected AABB createRenderBoundingBox() {
		// The marker drawn on the targeted track belongs to this block entity, and that track can
		// be several blocks away, so the box has to reach it or the marker vanishes at some angles.
		return new AABB(Vec3.atLowerCornerOf(worldPosition), Vec3.atLowerCornerOf(edgePoint.getGlobalPosition()))
			.inflate(2);
	}

	@Override
	public void transform(BlockEntity be, StructureTransform transform) {
		edgePoint.transform(be, transform);
	}

	/**
	 * A scroll value that survives being placed.
	 *
	 * <p>{@code ScrollValueBehaviour#read} reads the value unconditionally, and missing NBT reads
	 * back as zero. That is harmless for Create's own blocks, whose items carry no block entity
	 * data so nothing is read on a fresh placement — but this block's item writes the targeted
	 * track into the placement NBT, so read <em>does</em> run on a brand new sign and would wipe the
	 * default down to zero. Zero being a legal limit here, that would look like a working setting
	 * rather than a bug.
	 */
	private static class SpeedLimitScrollBehaviour extends ScrollValueBehaviour {

		public SpeedLimitScrollBehaviour(Component label, SmartBlockEntity be, ValueBoxTransform slot) {
			super(label, be, slot);
		}

		@Override
		public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
			int fallback = value;
			super.read(nbt, registries, clientPacket);
			if (!nbt.contains("ScrollValue"))
				value = fallback;
		}

		/**
		 * The top of the range follows Create's config, which can change under a sign that is
		 * already standing, so it is looked at each time rather than fixed when the sign is made.
		 */
		@Override
		public void setValue(int newValue) {
			max = SpeedLimitEdgePoint.maxLimit();
			super.setValue(newValue);
		}

		/** Never above the range as it stands now, whatever was saved when it was wider. */
		@Override
		public int getValue() {
			return Math.min(super.getValue(), SpeedLimitEdgePoint.maxLimit());
		}

		@Override
		public ValueSettings getValueSettings() {
			return new ValueSettings(0, getValue());
		}

		@Override
		public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
			// Same board the base class builds, only with a translated row label instead of the
			// hardcoded "Value" — this mod ships a Japanese locale and that string is on screen —
			// and the unit written after the number.
			return new ValueSettingsBoard(label, SpeedLimitEdgePoint.maxLimit(), 10,
				List.of(Component.translatable("createtrainnext.speed_limit.row")),
				new ValueSettingsFormatter(settings -> Component.literal(format(settings.value()))));
		}
	}

	/**
	 * Puts the value box on the face of the sign, just proud of the plate.
	 *
	 * <p>Not a {@code ValueBoxTransform.Sided}: that one places a box on whichever of the block's
	 * six faces the player is looking at, and this sign has one face worth reading, pointing
	 * somewhere between them. Both the offset and the angle come from {@link SpeedLimitBlock}'s own
	 * measurements so the number stays stuck to the plate at all sixteen positions.
	 */
	private static class SpeedLimitValueBox extends ValueBoxTransform {

		@Override
		public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
			// Down on the post rather than on the plate: the plate is where the limit itself is
			// drawn now, and a value box over the top of it would be two numbers in one place.
			// Pushed out along the facing far enough to clear the post, which is 1/16 thick.
			return new Vec3(0.5, 3 / 16.0, 0.5).add(SpeedLimitBlock.facing(state)
				.scale(2 / 16.0));
		}

		@Override
		public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
			// Negated because a value box turns the opposite way round the y axis from a blockstate
			// variant, which is what modelYaw measures in. Worked out from Sided, which spells the
			// same thing as horizontalAngle(side) + 180 for the four directions it can reach.
			TransformStack.of(ms)
				.rotateYDegrees(-SpeedLimitBlock.modelYaw(state));
		}
	}
}
