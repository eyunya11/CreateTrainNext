package com.eyunya.createtrainnext.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.eyunya.createtrainnext.track.CurveWandItem;
import com.eyunya.createtrainnext.track.CurveWandSelection;
import com.eyunya.createtrainnext.track.GradeSpec;
import com.eyunya.createtrainnext.track.GradeText;
import com.eyunya.createtrainnext.track.SetCurveAnglePacket;
import com.eyunya.createtrainnext.track.SetCurveGradePacket;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The ghost curve the wand draws while a curve is being aimed, and the wheel that adjusts it.
 *
 * <p>All of it runs from the client tick, and all of it is thrown away and recomputed each tick
 * from {@link CurvePlanner}. That is deliberate: the ghost is the same function the server will run
 * when the click lands, so what is drawn is the curve that gets built rather than an impression of
 * it. Nothing is cached between ticks except the angle and the grade the player has dialled in.
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

	/** The second arc, when a curve is built as two. See {@link CurvePlanner.Plan#junction}. */
	private static final GhostTrack GHOST_SECOND = new GhostTrack("createtrainnext:curve_wand_second");

	private static final String JUNCTION_KEY = "createtrainnext:curve_wand_junction";

	/** The block the far end's anchor will take, outlined in grade mode, where it is not the one aimed at. */
	private static final String END_KEY = "createtrainnext:curve_wand_end";

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

	/**
	 * The grade the player has dialled in, in permille, and whether grade mode is on, ahead of the server
	 * agreeing to them for the same reason {@link #angle} is. A grade is kept while the mode is on.
	 */
	private static int grade;

	private static boolean gradeMode;

	/** Ticks left before the stack's own grade is believed again. See {@link #syncGrade}. */
	private static int gradeGraceTicks;

	private static final LerpedFloat validity = LerpedFloat.linear()
		.startWithValue(0);

	public static void init() {
		NeoForge.EVENT_BUS.addListener(CurveWandPreview::onClientTick);
		NeoForge.EVENT_BUS.addListener(CurveWandPreview::onMouseScrolled);
		NeoForge.EVENT_BUS.addListener(CurveWandPreview::onRightClickItem);
	}

	// ---------------------------------------------------------------- input

	/**
	 * Sneak plus the wheel turns the free end of the curve; sprint plus the wheel, standing, sets the
	 * grade the route is laid at.
	 *
	 * <p>Sneak is what separates the first from changing hotbar slot, and the sprint key what separates
	 * the second. With both held the wheel is still the angle, in the coarse step it has always had.
	 * The event is cancelled only once every other condition has been met — a wand with nothing selected
	 * has neither an angle nor a grade to adjust, so the wheel should still change slots.
	 */
	private static void onMouseScrolled(InputEvent.MouseScrollingEvent event) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (mc.screen != null || player == null)
			return;

		boolean sneaking = player.isShiftKeyDown();
		boolean sprinting = mc.options.keySprint.isDown();
		if (!sneaking && !sprinting)
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

		if (sneaking)
			turnEnd(selection, delta, sprinting);
		else
			setGrade(player.level(), selection, delta);
		event.setCanceled(true);
	}

	/**
	 * Sneak and right click the air, in grade mode, opens the screen to type a grade and a distance in.
	 *
	 * <p>Done from an event, and cancelled, for the reason the survey marker's screen is: no client class is
	 * named anywhere the server can reach, and the server is never sent a click it has nothing to do with.
	 * Outside grade mode the same gesture drops the selection, and that is left to the item. A click on a
	 * block never gets here — with a selection, the wand answers it by dropping the selection — so taking
	 * the selection back while in grade mode is a sneak click on a block.
	 */
	private static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		if (!event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND
			|| !event.getEntity()
				.isShiftKeyDown())
			return;

		ItemStack stack = event.getItemStack();
		if (!(stack.getItem() instanceof CurveWandItem))
			return;
		CurveWandSelection selection = CurveWandItem.selectionIn(stack, event.getLevel());
		if (selection == null || !selection.gradeMode())
			return;

		Minecraft.getInstance()
			.setScreen(new CurveGradeScreen(selection.grade(), CurvePlanner.maxGradePermille()));
		event.setCanceled(true);
	}

	private static void turnEnd(CurveWandSelection selection, double delta, boolean coarse) {
		float step = coarse ? COARSE_STEP : FINE_STEP;
		angle = Mth.clamp(angle + (float) Math.signum(delta) * step, -MAX_ADJUST, MAX_ADJUST);
		trackedSelection = selection.pos();
		graceTicks = 20;

		PacketDistributor.sendToServer(new SetCurveAnglePacket(angle));
	}

	/**
	 * Moves the grade one notch, and turns the mode on by leaving zero and off by coming back to it
	 * where there is no climb to level off from.
	 *
	 * <p>The same rule the server applies to what it is sent: asking for level from level is asking for
	 * nothing, and a mode left on that did nothing would be a ghost the player could not account for.
	 * From a climb, zero is a grade to arrive at like any other, and the mode stays on to level the route
	 * off. The server is sent the whole state, and gets the same answer.
	 */
	private static void setGrade(Level level, CurveWandSelection selection, double delta) {
		int limit = CurvePlanner.maxGradePermille();
		int next = Mth.clamp(grade + (int) Math.signum(delta) * GradeSpec.STEP, -limit, limit);

		boolean active = gradeMode || next != 0;
		if (next == 0 && CurvePlanner.startsLevel(level, selection.pos()))
			active = false;

		grade = active ? next : 0;
		gradeMode = active;
		gradeGraceTicks = 20;

		PacketDistributor.sendToServer(new SetCurveGradePacket(grade, gradeMode));
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

	/**
	 * Brings the local grade back in line with the stack's, on the terms {@link #syncAngle} does the angle.
	 * The change the server makes unasked is the mode being dropped when a curve that levels the route off
	 * has been laid.
	 */
	private static void syncGrade(CurveWandSelection selection) {
		if (gradeGraceTicks > 0) {
			gradeGraceTicks--;
			if (selection.grade() == grade && selection.gradeMode() == gradeMode)
				gradeGraceTicks = 0;
			return;
		}
		grade = selection.grade();
		gradeMode = selection.gradeMode();
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
			gradeGraceTicks = 0;
			clearGhost();
			WandHud.clear();
			Outliner.getInstance()
				.remove(ANCHOR_KEY);
			showSplitCandidate(mc, player);
			return;
		}

		Outliner.getInstance()
			.remove(SPLIT_KEY);
		syncAngle(selection);
		syncGrade(selection);
		GradeSpec spec = gradeMode ? new GradeSpec(grade) : null;

		// Where the curve is coming from, drawn whether or not there is anywhere to take it. Being
		// able to see the selection from across the build is most of what stops a wand left part
		// way through a curve from being a mystery.
		Outliner.getInstance()
			.showAABB(ANCHOR_KEY, new AABB(selection.pos()).inflate(0.05))
			.colored(0xFFE082)
			.lineWidth(1 / 24f);

		HitResult hitResult = mc.hitResult;
		if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
			clearGhost();
			// Nothing to aim at, but the wheel may have just done something, and the grade it set is
			// worth being able to read off while looking at the sky.
			if (spec == null)
				WandHud.clear();
			else
				WandHud.show(List.of(GradeText.modeSet(spec.permille())
					.withStyle(ChatFormatting.AQUA)));
			return;
		}

		BlockHitResult hit = (BlockHitResult) hitResult;
		CurvePlanner.Plan plan =
			CurvePlanner.plan(mc.level, selection.pos(), hit.getBlockPos(), hit.getDirection(), angle, spec);

		showStatus(plan, spec);

		validity.chase(plan.valid() ? 1 : 0, 0.25, Chaser.EXP);
		validity.tickChaser();

		List<BezierConnection> curves = plan.curves();
		if (curves.isEmpty()) {
			clearGhost();
			return;
		}

		int colour = Color.mixColors(INVALID_COLOUR, VALID_COLOUR, validity.getValue());
		GHOST.draw(curves.get(0), colour, validity.getValue());
		if (curves.size() > 1)
			GHOST_SECOND.draw(curves.get(1), colour, validity.getValue());
		else
			GHOST_SECOND.clear();

		// In grade mode the far end is not at the height that was aimed at, so the block it will take
		// is outlined: it is where the curve stops, and where the block is that is in the way when the
		// curve is red for want of room.
		if (plan.grade() == null)
			Outliner.getInstance()
				.remove(END_KEY);
		else
			Outliner.getInstance()
				.showAABB(END_KEY, new AABB(plan.end()
					.anchorPos()).inflate(0.02))
				.colored(colour)
				.lineWidth(1 / 16f);

		// The block the anchor between the two arcs would go in, so that a click which adds
		// something to the world says where.
		if (plan.junction() == null)
			Outliner.getInstance()
				.remove(JUNCTION_KEY);
		else
			Outliner.getInstance()
				.showAABB(JUNCTION_KEY, new AABB(plan.junction()
					.pos()).inflate(0.02))
				.colored(colour)
				.lineWidth(1 / 16f);
	}

	/** Takes the whole ghost down: both arcs, and the markers for the anchors at the end and between them. */
	private static void clearGhost() {
		GHOST.clear();
		GHOST_SECOND.clear();
		Outliner.getInstance()
			.remove(JUNCTION_KEY);
		Outliner.getInstance()
			.remove(END_KEY);
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

	/**
	 * Says what the curve being aimed at is, or why it cannot be.
	 *
	 * <p>Up to three lines. The first is what it has always been, the curve's length and how far it
	 * bends; then, in grade mode, the grade that was set against the one the curve arrives at; then how
	 * it climbs, which is more than a line of the action bar holds, and is left out for a curve that does not.
	 */
	private static void showStatus(CurvePlanner.Plan plan, @Nullable GradeSpec spec) {
		List<Component> lines = new ArrayList<>();
		if (!plan.valid()) {
			lines.add(CurveWandItem.refusal(plan)
				.withStyle(ChatFormatting.RED));
			addGradeMode(lines, plan, spec);
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
			// A curve that stays level says so, and one that does not gets a line to itself: four numbers
			// that all describe the same slope, which the player reads as one thing.
			boolean level = GradeText.isLevel(plan.rise());
			if (level)
				detail = detail.copy()
					.append(GradeText.level());
			// Joining two routes that were laid away from each other turns the far one round. No
			// track moves, but which way that route runs does change, and a click that does more than
			// draw the curve it showed should not be a surprise.
			if (plan.reverseEnd())
				detail = detail.copy()
					.append(Component.translatable("message.createtrainnext.wand.reverses")
						.withStyle(ChatFormatting.YELLOW));
			// A curve between ends that point different ways is two arcs, and an anchor goes where
			// they meet. That is a block put in the world which the player did not click, so it is
			// said as well as shown.
			if (plan.junction() != null)
				detail = detail.copy()
					.append(Component.translatable("message.createtrainnext.wand.junction")
						.withStyle(ChatFormatting.YELLOW));
			// The dialled-in angle is only shown once it is not zero, so the common case — two
			// clicks and no adjustment at all — says nothing about a control the player never used.
			lines.add(angle == 0 ? detail
				: detail.copy()
					.append(Component.translatable("message.createtrainnext.wand.adjusted",
						String.format("%+.1f", angle))
						.withStyle(ChatFormatting.YELLOW)));
			addGradeMode(lines, plan, spec);
			if (!level)
				lines.add(GradeText.climb(plan.rise(), plan.horizontalLength()));
		}
		WandHud.show(lines);
	}

	/**
	 * Says what grade mode is doing to this curve: the grade that was set against the one the curve
	 * arrives with, or that there was nothing for it to act on. Said for a refused curve as well, as long
	 * as there is a curve, since the grade is often what the refusal is about.
	 */
	private static void addGradeMode(List<Component> lines, CurvePlanner.Plan plan, @Nullable GradeSpec spec) {
		if (spec == null || plan.curve() == null)
			return;
		lines.add(plan.grade() != null
			? GradeText.mode(spec.permille(), plan.endSlope())
				.withStyle(ChatFormatting.AQUA)
			: GradeText.modeFixed(spec.permille())
				.withStyle(ChatFormatting.YELLOW));
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
		gradeGraceTicks = 0;
		clearGhost();
		WandHud.clear();
		Outliner outliner = Outliner.getInstance();
		outliner.remove(ANCHOR_KEY);
		outliner.remove(SPLIT_KEY);
	}

	private CurveWandPreview() {}
}
