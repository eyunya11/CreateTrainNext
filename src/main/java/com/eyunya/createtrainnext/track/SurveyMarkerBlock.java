package com.eyunya.createtrainnext.track;

import com.eyunya.createtrainnext.CTNRegistry;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The block half of {@link SurveyMarkerBlockEntity}: a post you stand where a line should start.
 *
 * <p>Two ways in, matching the two ways anyone actually works. The bearing is on a scroll box, so
 * it can be nudged a degree at a time with a wrench while looking at where the line should go. All
 * three numbers — the length and the grade as well — are also in a screen, opened by right clicking with
 * an empty hand, for when the bearing is already known and typing it beats scrolling to it.
 *
 * <p>Laying is the curve wand, which is the tool that builds track everywhere else in this mod. The
 * marker is consumed by it: the line starts where the marker stood, so the marker becomes the
 * anchor at that end. That is the honest arrangement — a marker left behind would be a second thing
 * claiming to describe a line that already exists, and free to disagree with it.
 */
public class SurveyMarkerBlock extends Block implements IBE<SurveyMarkerBlockEntity>, IWrenchable {

	/** A slim post with a head, so it reads as a survey peg rather than a piece of track. */
	private static final VoxelShape SHAPE =
		Shapes.or(Block.box(6, 0, 6, 10, 12, 10), Block.box(4, 12, 4, 12, 15, 12));

	public SurveyMarkerBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hit) {

		if (!(stack.getItem() instanceof CurveWandItem))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!(level.getBlockEntity(pos) instanceof SurveyMarkerBlockEntity marker))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		CurvePlanner.StraightPlan plan =
			CurvePlanner.planStraight(level, pos, marker.getBearing(), marker.getLength(), marker.getGrade());

		if (!plan.valid()) {
			if (!level.isClientSide)
				player.displayClientMessage(CurveWandItem.refusal(plan.error(), Math.abs(plan.slope()))
					.withStyle(ChatFormatting.RED), true);
			AllSoundEvents.DENY.playFrom(player, 1, 1);
			return ItemInteractionResult.FAIL;
		}

		if (level.isClientSide)
			return ItemInteractionResult.SUCCESS;

		if (!CurvePlanner.applyStraight(level, pos, plan)) {
			player.displayClientMessage(Component.translatable("message.createtrainnext.wand.no_room")
				.withStyle(ChatFormatting.RED), true);
			return ItemInteractionResult.FAIL;
		}

		// The bearing that came out, not the one asked for, and for a line that climbs the grade that came
		// out: a whole number of blocks over the run it has. See SurveyMarkerBlockEntity.
		String bearing = String.format("%.1f", (plan.bearing() + 360) % 360);
		String run = String.format("%.1f", plan.length());
		player.displayClientMessage((plan.rise() == 0
			? Component.translatable("message.createtrainnext.survey.laid", bearing, run)
			: Component.translatable("message.createtrainnext.survey.laid_climbing", bearing, run,
				String.format("%+d", GradeText.permille(plan.slope())), String.format("%+d", plan.rise())))
			.withStyle(ChatFormatting.GREEN), true);
		level.playSound(null, pos, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0F, 0.9F);
		return ItemInteractionResult.SUCCESS;
	}

	// --------------------------------------------------------------------- IBE

	@Override
	public Class<SurveyMarkerBlockEntity> getBlockEntityClass() {
		return SurveyMarkerBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends SurveyMarkerBlockEntity> getBlockEntityType() {
		return CTNRegistry.SURVEY_MARKER_ENTITY.get();
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		// Not super.onRemove: a SmartBlockEntity has to be told it is going away before it is
		// taken out from under its behaviours.
		IBE.onRemove(state, level, pos, newState);
	}
}
