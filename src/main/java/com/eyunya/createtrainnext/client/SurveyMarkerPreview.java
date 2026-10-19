package com.eyunya.createtrainnext.client;

import java.util.ArrayList;
import java.util.List;

import com.eyunya.createtrainnext.track.CurvePlanner;
import com.eyunya.createtrainnext.track.CurveWandItem;
import com.eyunya.createtrainnext.track.GradeText;
import com.eyunya.createtrainnext.track.SurveyMarkerBlockEntity;

import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.createmod.catnip.theme.Color;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The line a survey marker would lay, drawn while it is being looked at.
 *
 * <p>Shown for anyone looking at a marker rather than only for someone holding the wand, because
 * the moment the preview earns its keep is while the bearing is being scrolled — a wrench in hand,
 * watching the line swing to where it should go. Waiting for the tool that commits it would be
 * showing it after the decision instead of during.
 *
 * <p>Computed from {@link CurvePlanner#planStraight} and drawn from
 * {@link CurvePlanner#straightCurve}, which are the same two functions the marker itself uses when
 * the wand lands on it. The ghost is the line, not a likeness of it.
 *
 * <p>What it says goes to {@link WandHud}, the way the wand's does, because a line that climbs has more
 * to say than a line of the action bar holds. It only ever says something there and never clears it,
 * so that it cannot take down what the wand is saying; the wand's preview is registered first and
 * clears first, which is why this one can show over it when both are looking at a marker.
 */
public class SurveyMarkerPreview {

	private static final GhostTrack GHOST = new GhostTrack("createtrainnext:survey_marker");

	private static final int VALID_COLOUR = 0x95CD41;

	private static final int INVALID_COLOUR = 0xEA5C2B;

	private static final LerpedFloat validity = LerpedFloat.linear()
		.startWithValue(0);

	public static void init() {
		NeoForge.EVENT_BUS.addListener(SurveyMarkerPreview::onClientTick);
	}

	private static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			GHOST.clear();
			return;
		}

		HitResult hitResult = mc.hitResult;
		if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
			GHOST.clear();
			return;
		}

		BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
		if (!(mc.level.getBlockEntity(pos) instanceof SurveyMarkerBlockEntity marker)) {
			GHOST.clear();
			return;
		}

		CurvePlanner.StraightPlan plan =
			CurvePlanner.planStraight(mc.level, pos, marker.getBearing(), marker.getLength());

		validity.chase(plan.valid() ? 1 : 0, 0.25, Chaser.EXP);
		validity.tickChaser();

		WandHud.show(describe(plan, marker.getGrade()));

		// A refusal with nowhere to draw — a length past what the server allows, say — has no
		// geometry to show. One that found its block and cannot have it does, and showing that in
		// red says far more than the message alone: it is the difference between "somewhere" and
		// "there, and something is in the way".
		if (!plan.hasGeometry()) {
			GHOST.clear();
			return;
		}

		GHOST.draw(CurvePlanner.straightCurve(pos, plan),
			Color.mixColors(INVALID_COLOUR, VALID_COLOUR, validity.getValue()), validity.getValue());
	}

	/**
	 * What the line is, or why it cannot be: its bearing and length, and for a line that climbs the grade
	 * it really has on a line of its own. A line that is level although a grade was set says so, since the
	 * grade asked for is not the one that came out.
	 */
	private static List<Component> describe(CurvePlanner.StraightPlan plan, int grade) {
		List<Component> lines = new ArrayList<>();
		if (!plan.valid()) {
			lines.add(CurveWandItem.refusal(plan.error(), Math.abs(plan.slope()))
				.withStyle(ChatFormatting.RED));
			return lines;
		}

		MutableComponent ready = Component.translatable("message.createtrainnext.survey.ready",
			String.format("%.1f", (plan.bearing() + 360) % 360), String.format("%.1f", plan.length()))
			.withStyle(ChatFormatting.GREEN);
		if (plan.rise() == 0 && grade != 0)
			ready.append(GradeText.level());
		lines.add(ready);
		if (plan.rise() != 0)
			lines.add(GradeText.climb(plan.rise(), plan.length()));
		return lines;
	}

	private SurveyMarkerPreview() {}
}
