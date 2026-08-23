package com.eyunya.createtrainnext.client;

import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackRenderer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Draws the free-angle curve using Create's own track renderer.
 *
 * <p>{@code TrackRenderer#renderBezierTurn} is public and static, and its segment
 * transforms are already expressed relative to the first anchor's block position,
 * which is exactly where a block entity renderer's pose stack sits. That makes the
 * whole visual side of this experiment a straight reuse of Create's geometry.
 */
public class AngledTrackAnchorRenderer implements BlockEntityRenderer<AngledTrackAnchorBlockEntity> {

	public AngledTrackAnchorRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(AngledTrackAnchorBlockEntity anchor, float partialTicks, PoseStack ms, MultiBufferSource buffer,
		int light, int overlay) {
		Level level = anchor.getLevel();
		if (level == null)
			return;

		BezierConnection curve = anchor.getCurveForRender();
		if (curve == null)
			return;

		VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());
		TrackRenderer.renderBezierTurn(level, curve, ms, vb);
	}

	/**
	 * Required in addition to {@link #getRenderBoundingBox}, not instead of it.
	 *
	 * <p>{@code LevelRenderer} only walks block entities belonging to chunk sections that are
	 * currently visible. Returning true here puts this anchor in {@code globalBlockEntities},
	 * which is drawn regardless of whether its own section made the cut — otherwise the whole
	 * curve vanishes as soon as the anchor block's section falls outside the frustum, which is
	 * easy to trigger by standing at the far end and looking along the track. Create's own
	 * TrackRenderer does exactly the same thing.
	 */
	@Override
	public boolean shouldRenderOffScreen(AngledTrackAnchorBlockEntity anchor) {
		return true;
	}

	@Override
	public AABB getRenderBoundingBox(AngledTrackAnchorBlockEntity anchor) {
		// Feeds the frustum test in ClientHooks#isBlockEntityRendererVisible, which gates both
		// the per-section and the global path.
		return anchor.computeRenderBoundingBox();
	}

	@Override
	public int getViewDistance() {
		// Culling is measured from the anchor block, but the curve can end far away from it.
		return 256;
	}
}
