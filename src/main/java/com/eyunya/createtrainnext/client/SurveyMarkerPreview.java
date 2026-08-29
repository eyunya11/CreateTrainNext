package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.track.CurvePlanner;
import com.eyunya.createtrainnext.track.SurveyMarkerBlockEntity;

import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.createmod.catnip.theme.Color;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

		player.displayClientMessage(plan.valid()
			? Component.translatable("message.createtrainnext.survey.ready",
				String.format("%.1f", (plan.bearing() + 360) % 360), String.format("%.1f", plan.length()))
				.withStyle(ChatFormatting.GREEN)
			: Component.translatable("message.createtrainnext.wand." + plan.error())
				.withStyle(ChatFormatting.RED),
			true);

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

	private SurveyMarkerPreview() {}
}
