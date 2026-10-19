package com.eyunya.createtrainnext.track;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNDataComponents;
import com.simibubi.create.AllSoundEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
 * <li>Right click a piece of track, or an anchor, to start a curve there. An anchor can be started
 * from while either of its sides is free, and the curve leaves by that side — so a route can be
 * carried on backwards out of its first anchor as well as forwards out of its last.</li>
 * <li>Right click again to build it. The selection carries on from the end just reached, so a whole
 * route is one click per curve.</li>
 * <li>Where the two ends point so differently that one bezier would be a different curve read the
 * other way round — joining two anchors that already point somewhere, or turning the far end by hand
 * — the curve is built as two arcs with an anchor where they meet. The ghost shows both, and
 * outlines the block the anchor will take.</li>
 * <li>Right click the end of another route to join the two. If they were laid away from each other
 * — two last anchors facing across the gap, or two first — the route at the far end is turned round
 * to match, which moves no track; the ghost says so. The selection is dropped afterwards, since the
 * anchor reached has nowhere left to carry on from.</li>
 * <li>Sneak and scroll to turn the far end of the curve by hand; right click the air to put it back
 * to the arc the tool picked.</li>
 * <li>Hold sprint and scroll to lay the route at a grade, five permille a notch. That turns on grade
 * mode: the far end is then put at the height the grade gives it, whatever height was clicked, and the
 * route carries on at that grade until a curve is asked to arrive level. See {@link GradeSpec}.</li>
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
			// In grade mode the air is where the grade screen is opened from, by the client before this is
			// reached; if it was reached anyway there is nothing to cancel by. Taking the selection back is a
			// sneak click on a block, which is the same gesture in either mode.
			if (selection.gradeMode())
				return InteractionResultHolder.pass(stack);
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

		CurvePlanner.Plan plan =
			CurvePlanner.plan(level, selection.pos(), pos, face, selection.angle(), selection.spec());

		if (!plan.valid()) {
			if (!level.isClientSide)
				player.displayClientMessage(refusal(plan), true);
			AllSoundEvents.DENY.playFrom(player, 1, 1);
			return InteractionResult.FAIL;
		}

		if (level.isClientSide)
			return InteractionResult.SUCCESS;

		return build(level, player, stack, selection, plan, false);
	}

	/**
	 * Lays a curve from the selection straight ahead for a distance, at the grade the player typed in.
	 * Server side only, from {@link ApplyGradePacket}.
	 *
	 * @return whether a curve was laid.
	 */
	static boolean layByDistance(Level level, Player player, ItemStack stack, CurveWandSelection selection,
		int horizontalLength) {
		CurvePlanner.Plan plan = CurvePlanner.planByDistance(level, selection.pos(), new GradeSpec(selection.grade()),
			horizontalLength, AngledTrackAnchorBlockEntity.directionFromYaw(player.getYRot()));

		if (!plan.valid()) {
			player.displayClientMessage(refusal(plan).withStyle(ChatFormatting.RED), true);
			AllSoundEvents.DENY.playFrom(player, 1, 1);
			return false;
		}
		return build(level, player, stack, selection, plan, true).consumesAction();
	}

	/**
	 * Builds a plan that has been checked, and moves the selection on from the end it reached. Server
	 * side only.
	 *
	 * @param byDistance whether the curve was laid from a distance rather than aimed at, so that nothing
	 *                   showed what it would be and the message has to say how it came out.
	 */
	private static InteractionResult build(Level level, Player player, ItemStack stack, CurveWandSelection selection,
		CurvePlanner.Plan plan, boolean byDistance) {
		String failure = CurvePlanner.apply(level, plan);
		if (failure != null) {
			message(player, failure);
			AllSoundEvents.DENY.playFrom(player, 1, 1);
			return InteractionResult.FAIL;
		}

		// An end that docked onto Create's own track is where the route stops, so the selection is
		// dropped rather than carried on. Extending through a docked end would quietly undo the
		// dock: an anchor with a curve on both sides moves its node to its block's centre, and the
		// whole point of the dock is that the node sits on the boundary the track also reports.
		if (plan.end()
			.docked()) {
			stack.remove(CTNDataComponents.CURVE_SELECTION);
			built(player, plan, "docked");
		} else if (level.getBlockEntity(plan.end()
			.anchorPos()) instanceof AngledTrackAnchorBlockEntity reached && !reached.hasFreeSide()) {
			// Joined up. The anchor just reached has a curve on each side now, so there is nowhere left
			// to carry on from, and a selection kept standing would be a ghost that is red wherever it
			// is aimed, because the place it starts from is taken.
			stack.remove(CTNDataComponents.CURVE_SELECTION);
			built(player, plan, "joined");
		} else {
			// Otherwise the selection moves to the end just reached, so a route is one click per
			// curve. It is safe to leave standing in a way the retired prototype's was not: that one
			// was a bare position on the server that any later click anywhere would join itself to,
			// whereas this lives on the stack, is drawn as a ghost the whole time it exists, and is
			// dropped with a sneak right click.
			//
			// The grade goes with it, so that the route carries on at the climb it was laid at; but a
			// curve that was asked to arrive level has done what the mode was for, and the route goes
			// on in the mode that follows the far end as it is clicked.
			CurveWandSelection carried = selection.carriedOnTo(plan.end()
				.anchorPos());
			if (plan.grade() != null && plan.grade()
				.permille() == 0)
				carried = carried.withGrade(0, false);
			stack.set(CTNDataComponents.CURVE_SELECTION, carried);
			if (byDistance)
				laid(player, plan);
			else
				built(player, plan, "built");
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
		for (String line : new String[] { "start", "adjust", "grade", "grade_screen", "cancel", "split" })
			tooltip.add(Component.translatable("tooltip.createtrainnext.curve_wand." + line)
				.withStyle(ChatFormatting.GRAY));
	}

	private static void playSound(Level level, Player player, SoundEvent sound) {
		if (level.isClientSide)
			level.playSound(player, player.blockPosition(), sound, SoundSource.BLOCKS, 0.75F, 1.0F);
	}

	/**
	 * What is said when a curve has been built: how long it is, and — when the wand put an anchor in
	 * the middle of it to make two arcs — that it did, since that block is one the player did not
	 * click.
	 */
	private static void built(Player player, CurvePlanner.Plan plan, String key) {
		MutableComponent text = Component.translatable("message.createtrainnext.wand." + key,
			String.format("%.1f", plan.length()));
		if (plan.junction() != null)
			text.append(Component.translatable("message.createtrainnext.wand.point_added"));
		player.displayClientMessage(text, true);
	}

	/**
	 * What is said when a curve has been laid from a distance. Nothing showed what it would be, so this
	 * is where the player is told how it came out: the grade it averages, how far it goes along the
	 * ground, and how high it gets.
	 */
	private static void laid(Player player, CurvePlanner.Plan plan) {
		double run = plan.horizontalLength();
		MutableComponent text = Component.translatable("message.createtrainnext.wand.laid",
			String.format("%+d", GradeText.permille(plan.rise() / run)), String.format("%.1f", run),
			String.format("%+d", Math.round(plan.rise())));
		if (plan.junction() != null)
			text.append(Component.translatable("message.createtrainnext.wand.point_added"));
		player.displayClientMessage(text, true);
	}

	/**
	 * The words for why a plan was refused, with the numbers that go with the reason where it has any.
	 *
	 * <p>Shared by the ghost and by the click, so that what the player is told while aiming is what
	 * they are told when it is refused. A climb that is too steep says how steep it is at its steepest
	 * and what the limit is: the limit can be changed, and the figure it is held to is the steepest
	 * point of the curve, which is not the grade shown for the whole of it.
	 */
	public static MutableComponent refusal(CurvePlanner.Plan plan) {
		if ("too_steep".equals(plan.error()))
			return Component.translatable("message.createtrainnext.wand.too_steep_by",
				GradeText.permille(plan.steepestGrade()), CurvePlanner.maxGradePermille());
		return Component.translatable("message.createtrainnext.wand." + plan.error());
	}

	static void message(Player player, String key, Object... args) {
		player.displayClientMessage(Component.translatable("message.createtrainnext.wand." + key, args), true);
	}
}
