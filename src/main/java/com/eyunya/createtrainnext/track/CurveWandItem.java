package com.eyunya.createtrainnext.track;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNDataComponents;
import com.simibubi.create.AllSoundEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The tool a free-angle route is built with.
 *
 * <p>Two clicks make a curve: one on the track or anchor it should leave from, one on where it
 * should arrive. Between them the curve is drawn as a ghost that follows the block being looked at,
 * and the second click builds exactly what the ghost showed. The anchors the curve needs are placed
 * as a consequence of that rather than being something to put down first — a player laying track
 * should be thinking about where the line goes, not about the blocks that hold its ends.
 *
 * <p>Controls:
 * <ul>
 * <li>Right click a piece of track, or an anchor, to start a curve there.</li>
 * <li>Right click again to build it. The selection carries on from the end just reached, so a whole
 * route is one click per curve.</li>
 * <li>Sneak and scroll to turn the far end of the curve by hand; right click the air to put it back
 * to the arc the tool picked.</li>
 * <li>Sneak and right click the air to drop the selection, or an anchor to take its curves apart.</li>
 * <li>With nothing selected, sneak and right click beside a curve to put a point on it. That point
 * is an anchor, and an anchor is a track block, so it is somewhere a signal or a station can go —
 * which the stretch of curve between two anchors is not.</li>
 * </ul>
 *
 * @see CurvePlanner for how the curve itself is worked out.
 */
public class CurveWandItem extends Item {

	/**
	 * How far from a click a curve may be and still be the one meant, in blocks.
	 *
	 * <p>A click lands on a block face, and the curve it is aimed at floats above the ground rather
	 * than on it — the invisible floor under a curve has no outline of its own to click, on purpose.
	 * Two blocks covers the gap between the ground and the rails without reaching a curve on the
	 * level above.
	 */
	public static final double SPLIT_REACH = 2.0;

	public CurveWandItem(Properties properties) {
		super(properties.stacksTo(1));
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		Player player = context.getPlayer();
		ItemStack stack = context.getItemInHand();
		BlockPos pos = context.getClickedPos();

		if (player == null || context.getHand() != InteractionHand.MAIN_HAND)
			return InteractionResult.PASS;

		CurveWandSelection selection = selectionIn(stack, level);

		if (player.isShiftKeyDown())
			return sneakUseOn(level, player, stack, pos, context.getClickLocation(), selection);
		if (selection == null)
			return startCurve(level, player, stack, pos);
		return finishCurve(level, player, stack, selection, pos, context.getClickedFace());
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (hand != InteractionHand.MAIN_HAND)
			return InteractionResultHolder.pass(stack);

		CurveWandSelection selection = selectionIn(stack, level);
		if (selection == null)
			return InteractionResultHolder.pass(stack);

		if (player.isShiftKeyDown()) {
			if (!level.isClientSide) {
				stack.remove(CTNDataComponents.CURVE_SELECTION);
				message(player, "cleared");
			}
			playSound(level, player, SoundEvents.ITEM_FRAME_REMOVE_ITEM);
			return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
		}

		// Putting the hand adjustment back rather than clearing the selection. Reaching for it is
		// what a player does after over-turning a curve, and having to start the curve again to
		// get back to the arc the tool would have chosen is a poor answer to that.
		if (selection.angle() == 0.0F)
			return InteractionResultHolder.pass(stack);
		if (!level.isClientSide) {
			stack.set(CTNDataComponents.CURVE_SELECTION, selection.withAngle(0.0F));
			message(player, "angle_reset");
		}
		playSound(level, player, SoundEvents.ITEM_FRAME_ROTATE_ITEM);
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
	}

	// ------------------------------------------------------------- the clicks

	private InteractionResult startCurve(Level level, Player player, ItemStack stack, BlockPos pos) {
		// An anchor with a curve on both sides is a refusal worth saying out loud, because the
		// player pointed at track and got nothing. Clicking scenery is not: they are as likely to
		// have been reaching past it as asking for anything. One free side is enough to start
		// from, and which way the curve leaves by is the planner's to work out from what is free.
		if (level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor && !anchor.hasFreeSide()) {
			if (!level.isClientSide)
				message(player, "start_taken");
			AllSoundEvents.DENY.playFrom(player, 1, 1);
			return InteractionResult.FAIL;
		}
		if (!CurvePlanner.canStartFrom(level, pos))
			return InteractionResult.PASS;

		if (!level.isClientSide) {
			stack.set(CTNDataComponents.CURVE_SELECTION, CurveWandSelection.of(level, pos));
			message(player, "started");
		}
		playSound(level, player, SoundEvents.ITEM_FRAME_ADD_ITEM);
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	private InteractionResult finishCurve(Level level, Player player, ItemStack stack,
		CurveWandSelection selection, BlockPos pos, Direction face) {

		CurvePlanner.Plan plan = CurvePlanner.plan(level, selection.pos(), pos, face, selection.angle());

		if (!plan.valid()) {
			if (!level.isClientSide)
				message(player, plan.error());
			AllSoundEvents.DENY.playFrom(player, 1, 1);
			return InteractionResult.FAIL;
		}

		if (level.isClientSide)
			return InteractionResult.SUCCESS;

		CurvePlanner.apply(level, plan);

		// An end that docked onto Create's own track is where the route stops, so the selection is
		// dropped rather than carried on. Extending through a docked end would quietly undo the
		// dock: an anchor with a curve on both sides moves its node to its block's centre, and the
		// whole point of the dock is that the node sits on the boundary the track also reports.
		if (plan.end()
			.docked()) {
			stack.remove(CTNDataComponents.CURVE_SELECTION);
			message(player, "docked", String.format("%.1f", plan.length()));
		} else {
			// Otherwise the selection moves to the end just reached, so a route is one click per
			// curve. It is safe to leave standing in a way the retired prototype's was not: that one
			// was a bare position on the server that any later click anywhere would join itself to,
			// whereas this lives on the stack, is drawn as a ghost the whole time it exists, and is
			// dropped with a sneak right click.
			stack.set(CTNDataComponents.CURVE_SELECTION, CurveWandSelection.of(level, plan.end()
				.anchorPos()));
			message(player, "built", String.format("%.1f", plan.length()));
		}

		level.playSound(null, plan.end()
			.anchorPos(), SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
		return InteractionResult.SUCCESS;
	}

	/**
	 * Sneak plus a click on a block: undo something, or put a point on a curve.
	 *
	 * <p>Three things in one gesture, in a fixed order so that which one happens is predictable.
	 * An anchor clicked directly is the most specific thing the player can be pointing at, so
	 * taking its curves apart wins. Failing that, a curve being drawn is dropped — while a curve is
	 * in progress, sneak always means cancel, which is worth more than the alternative reading. Only
	 * with nothing in progress does a sneak click on empty ground mean "put a point on the curve
	 * running past here".
	 */
	private InteractionResult sneakUseOn(Level level, Player player, ItemStack stack, BlockPos pos, Vec3 hit,
		@Nullable CurveWandSelection selection) {

		if (level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor && anchor.isLinked()) {
			if (!level.isClientSide) {
				anchor.unlink();
				message(player, "unlinked");
			}
			playSound(level, player, SoundEvents.ITEM_FRAME_REMOVE_ITEM);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}

		if (selection != null) {
			if (!level.isClientSide) {
				stack.remove(CTNDataComponents.CURVE_SELECTION);
				message(player, "cleared");
			}
			playSound(level, player, SoundEvents.ITEM_FRAME_REMOVE_ITEM);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}

		return splitCurve(level, player, hit);
	}

	/**
	 * Puts an anchor part way along the curve running past where the player clicked.
	 *
	 * <p>The point of it is signals and stations. Those are placed against a track block, an anchor
	 * is one, and the stretch of curve between two anchors is not — so before this, a signal could
	 * only go where a route happened to already have a joint, and moving one meant laying the route
	 * again. Now the joint goes where the signal is wanted.
	 */
	private InteractionResult splitCurve(Level level, Player player, Vec3 hit) {
		CurvePlanner.SplitTarget target = CurvePlanner.findCurveNear(level, hit, SPLIT_REACH);
		if (target == null)
			return InteractionResult.PASS;

		if (!target.valid()) {
			if (!level.isClientSide)
				message(player, target.error());
			AllSoundEvents.DENY.playFrom(player, 1, 1);
			return InteractionResult.FAIL;
		}

		if (level.isClientSide)
			return InteractionResult.SUCCESS;

		if (!CurvePlanner.split(level, target)) {
			message(player, "no_room");
			return InteractionResult.FAIL;
		}

		message(player, "split_done");
		level.playSound(null, target.anchorPos(), SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0F, 1.1F);
		return InteractionResult.SUCCESS;
	}

	// ------------------------------------------------------------------ misc

	/** The selection on a stack, treating one made in another dimension as no selection at all. */
	@Nullable
	public static CurveWandSelection selectionIn(ItemStack stack, Level level) {
		CurveWandSelection selection = stack.get(CTNDataComponents.CURVE_SELECTION);
		return selection != null && selection.isIn(level) ? selection : null;
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		// The one piece of feedback that survives looking away from the world, so it is worth the
		// enchantment glint: a wand part way through a curve looks different in the hotbar.
		return stack.has(CTNDataComponents.CURVE_SELECTION);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		for (String line : new String[] { "start", "adjust", "cancel", "split" })
			tooltip.add(Component.translatable("tooltip.createtrainnext.curve_wand." + line)
				.withStyle(ChatFormatting.GRAY));
	}

	private static void playSound(Level level, Player player, SoundEvent sound) {
		if (level.isClientSide)
			level.playSound(player, player.blockPosition(), sound, SoundSource.BLOCKS, 0.75F, 1.0F);
	}

	static void message(Player player, String key, Object... args) {
		player.displayClientMessage(Component.translatable("message.createtrainnext.wand." + key, args), true);
	}
}
