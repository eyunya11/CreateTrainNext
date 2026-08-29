package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.eyunya.createtrainnext.track.CurveWandItem;
import com.eyunya.createtrainnext.track.CurveWandSelection;
import com.eyunya.createtrainnext.track.SetCurveAnglePacket;
import com.simibubi.create.content.trains.track.BezierConnection;

import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.createmod.catnip.outliner.Outliner;
import net.createmod.catnip.theme.Color;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The ghost curve the wand draws while a curve is being aimed, and the wheel that adjusts it.
 *
 * <p>All of it runs from the client tick, and all of it is thrown away and recomputed each tick
 * from {@link CurvePlanner}. That is deliberate: the ghost is the same function the server will run
 * when the click lands, so what is drawn is the curve that gets built rather than an impression of
 * it. Nothing is cached between ticks except the angle the player has dialled in.
 *
 * <p>Drawn with catnip's outliner rather than as real track geometry. A preview that looked exactly
 * like finished track would be worse, not better — the useful thing about it is that it reads as
 * not-yet-real, and colouring it by whether the curve is buildable turns validity into something
 * seen rather than something read.
 */
public class CurveWandPreview {

	/** One notch of the wheel, and one notch with the sprint key held. */
	private static final float FINE_STEP = 2.5F;

	private static final float COARSE_STEP = 15.0F;

	/**
	 * How far the free end may be turned away from the arc the planner picked.
	 *
	 * <p>Past about this the curve is refused anyway — the arc is already the shape that gets the
	 * most out of the distance available, so turning either end away from it only makes the bend
	 * sharper. The cap is here so that the wheel stops somewhere meaningful instead of winding the
	 * curve round to point backwards.
	 */
	private static final float MAX_ADJUST = 85.0F;

	private static final int VALID_COLOUR = 0x95CD41;

	private static final int INVALID_COLOUR = 0xEA5C2B;

	private static final GhostTrack GHOST = new GhostTrack("createtrainnext:curve_wand");

	private static final String ANCHOR_KEY = "createtrainnext:curve_wand_anchor";

	private static final String SPLIT_KEY = "createtrainnext:curve_wand_split";

	/**
	 * The angle the player has dialled in, ahead of the server agreeing to it.
	 *
	 * <p>The authoritative copy lives on the item stack, so a round trip stands between turning the
	 * wheel and the stack saying so. Waiting for it would make the ghost lag the wheel by a tick on
	 * a local world and a great deal more on a server, which is exactly the feedback the wheel is
	 * there to give — so the client draws its own value and reconciles afterwards.
	 */
	private static float angle;

	/** Which selection {@link #angle} belongs to, so a new curve starts from the arc again. */
	private static BlockPos trackedSelection;

	/** Ticks left before the stack's own angle is believed again. See {@link #syncAngle}. */
	private static int graceTicks;

	private static final LerpedFloat validity = LerpedFloat.linear()
		.startWithValue(0);

	public static void init() {
		NeoForge.EVENT_BUS.addListener(CurveWandPreview::onClientTick);
		NeoForge.EVENT_BUS.addListener(CurveWandPreview::onMouseScrolled);
	}

	// ---------------------------------------------------------------- input

	/**
	 * Sneak plus the wheel turns the free end of the curve.
	 *
	 * <p>Sneak is what separates this from changing hotbar slot, and the event is cancelled only
	 * once every other condition has been met — a wand with nothing selected has no angle to
	 * adjust, so the wheel should still change slots.
	 */
	private static void onMouseScrolled(InputEvent.MouseScrollingEvent event) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (mc.screen != null || player == null || !player.isShiftKeyDown())
			return;

		double delta = event.getScrollDeltaY();
		if (delta == 0)
			return;

		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof CurveWandItem))
			return;
		CurveWandSelection selection = CurveWandItem.selectionIn(stack, player.level());
		if (selection == null)
			return;

		float step = mc.options.keySprint.isDown() ? COARSE_STEP : FINE_STEP;
		angle = Mth.clamp(angle + (float) Math.signum(delta) * step, -MAX_ADJUST, MAX_ADJUST);
		trackedSelection = selection.pos();
		graceTicks = 20;

		PacketDistributor.sendToServer(new SetCurveAnglePacket(angle));
		event.setCanceled(true);
	}

	/**
	 * Brings the local angle back in line with the stack's.
	 *
	 * <p>The stack is authoritative, but it is also behind whenever the wheel has just been turned,
	 * so believing it immediately would undo every notch. The grace window is what separates "the
	 * server has not caught up yet" from "the server changed this without being asked", which is
	 * what happens when the selection moves on to the next curve or is reset by hand.
	 */
	private static void syncAngle(CurveWandSelection selection) {
		if (graceTicks > 0) {
			graceTicks--;
			if (selection.angle() == angle)
				graceTicks = 0;
			return;
		}
		if (selection.pos()
			.equals(trackedSelection) && selection.angle() == angle)
			return;
		angle = selection.angle();
		trackedSelection = selection.pos();
	}

	// ----------------------------------------------------------------- tick

	private static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			clear();
			return;
		}

		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof CurveWandItem)) {
			clear();
			return;
		}

		CurveWandSelection selection = CurveWandItem.selectionIn(stack, mc.level);
		if (selection == null) {
			GHOST.clear();
			Outliner.getInstance()
				.remove(ANCHOR_KEY);
			showSplitCandidate(mc, player);
			return;
		}

		Outliner.getInstance()
			.remove(SPLIT_KEY);
		syncAngle(selection);

		// Where the curve is coming from, drawn whether or not there is anywhere to take it. Being
		// able to see the selection from across the build is most of what stops a wand left part
		// way through a curve from being a mystery.
		Outliner.getInstance()
			.showAABB(ANCHOR_KEY, new AABB(selection.pos()).inflate(0.05))
			.colored(0xFFE082)
			.lineWidth(1 / 24f);

		HitResult hitResult = mc.hitResult;
		if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
			GHOST.clear();
			return;
		}

		BlockHitResult hit = (BlockHitResult) hitResult;
		CurvePlanner.Plan plan =
			CurvePlanner.plan(mc.level, selection.pos(), hit.getBlockPos(), hit.getDirection(), angle);

		showStatus(player, plan);

		validity.chase(plan.valid() ? 1 : 0, 0.25, Chaser.EXP);
		validity.tickChaser();

		BezierConnection curve = plan.curve();
		if (curve == null) {
			GHOST.clear();
			return;
		}
		GHOST.draw(curve, Color.mixColors(INVALID_COLOUR, VALID_COLOUR, validity.getValue()), validity.getValue());
	}

	/**
	 * Where a sneak click would put a point on an existing curve.
	 *
	 * <p>Shown only while sneaking, because that is the only time the click would do it, and a
	 * marker that appeared whenever a player walked past their own railway holding the wand would
	 * be noise. The block is outlined rather than the point on the curve, since the block is what
	 * the anchor will occupy and the route will bend to its centre.
	 */
	private static void showSplitCandidate(Minecraft mc, LocalPlayer player) {
		if (!player.isShiftKeyDown() || mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) {
			Outliner.getInstance()
				.remove(SPLIT_KEY);
			return;
		}

		CurvePlanner.SplitTarget target =
			CurvePlanner.findCurveNear(mc.level, mc.hitResult.getLocation(), CurveWandItem.SPLIT_REACH);
		if (target == null) {
			Outliner.getInstance()
				.remove(SPLIT_KEY);
			return;
		}

		Outliner.getInstance()
			.showAABB(SPLIT_KEY, new AABB(target.anchorPos()).inflate(0.02))
			.colored(target.valid() ? VALID_COLOUR : INVALID_COLOUR)
			.lineWidth(1 / 16f);

		player.displayClientMessage(target.valid()
			? Component.translatable("message.createtrainnext.wand.split_ready")
				.withStyle(ChatFormatting.GREEN)
			: Component.translatable("message.createtrainnext.wand." + target.error())
				.withStyle(ChatFormatting.RED),
			true);
	}

	/**
	 * The eight-point compass name for a heading.
	 *
	 * <p>Worth printing beside the number rather than leaving the number to speak for itself.
	 * Minecraft's yaw has zero pointing south and grows clockwise, which is the convention the
	 * anchors store and F3 reports, so it is the right one to show — but it is not the one a
	 * compass bearing usually means, and "0 degrees" with no other clue reads as north to most
	 * people. The name settles it without having to explain it.
	 */
	private static Component compass(float yaw) {
		String[] points = { "s", "sw", "w", "nw", "n", "ne", "e", "se" };
		return Component.translatable("createtrainnext.compass." + points[Math.floorMod(Math.round(yaw / 45f), 8)]);
	}

	private static void showStatus(LocalPlayer player, CurvePlanner.Plan plan) {
		Component message;
		if (!plan.valid()) {
			message = Component.translatable("message.createtrainnext.wand." + plan.error())
				.withStyle(ChatFormatting.RED);
		} else {
			// Two angles, because they answer different questions. The turn is how far this one
			// curve bends, which is what says whether it is a gentle sweep or a hairpin; the
			// bearing is which way the route will be pointing once it arrives, which is what says
			// whether it is going where the rest of the railway needs it to.
			float bearing = AngledTrackAnchorBlockEntity.yawOf(plan.endHeading());
			// Shown over a full turn rather than as Minecraft's signed yaw: a bearing that reads
			// -150 where the one beside it reads 150 is two numbers to compare, not one.
			float shown = (Mth.wrapDegrees(bearing) + 360) % 360;
			Component detail = Component.translatable("message.createtrainnext.wand.ready",
				String.format("%.1f", plan.length()), String.format("%.0f", plan.turnDegrees()),
				String.format("%.0f", shown), compass(bearing))
				.withStyle(ChatFormatting.GREEN);
			// The dialled-in angle is only shown once it is not zero, so the common case — two
			// clicks and no adjustment at all — says nothing about a control the player never used.
			message = angle == 0 ? detail
				: detail.copy()
					.append(Component.translatable("message.createtrainnext.wand.adjusted",
						String.format("%+.1f", angle))
						.withStyle(ChatFormatting.YELLOW));
		}
		player.displayClientMessage(message, true);
	}

	// -------------------------------------------------------------- drawing

	/**
	 * Draws the curve as its two rails.
	 *
	 * <p>Two lines rather than one down the middle, because the width is what makes a curve's
	 * direction readable at a glance — a single line through the same points is much harder to tell
	 * apart from the one beside it. The sampling follows the same bezier the finished track is cut
	 * from, so the ghost and the track occupy the same space.
	 */
	private static void clear() {
		GHOST.clear();
		Outliner outliner = Outliner.getInstance();
		outliner.remove(ANCHOR_KEY);
		outliner.remove(SPLIT_KEY);
	}

	private CurveWandPreview() {}
}
