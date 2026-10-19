package com.eyunya.createtrainnext.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Render types of this mod's own.
 */
public class CTNRenderTypes {

	/**
	 * Additive, and deliberately not Create's {@code RenderTypes.additive()}.
	 *
	 * <p>The difference is one line: {@link RenderStateShard#COLOR_WRITE} instead of the default
	 * colour-and-depth. Create's additive writes depth, which is right for the nixie tube it was
	 * written for — there the lit shape and its halo are drawn into the same pass, so nothing can be
	 * caught behind it — and wrong here. A halo drawn in front of a lamp and writing depth is a
	 * 5-pixel square that everything drawn after it, in the same frame, fails the depth test
	 * against; block entity render types are flushed in no particular order, so whether the lamps
	 * survive that comes down to which pass happens to go first. A glow has no business occluding
	 * anything: it is light on top of a scene, not an object in it.
	 *
	 * <p>Sorting on upload is off for the same reason it can be: adding colour is commutative, so
	 * the order two halos are drawn in cannot change the result.
	 *
	 * <p>The blend is {@link RenderStateShard#LIGHTNING_TRANSPARENCY}, {@code SRC_ALPHA, ONE}, and not
	 * {@code ADDITIVE_TRANSPARENCY}, which is {@code ONE, ONE} and ignores alpha. With that one the
	 * shape of a halo has to live in the texture's colour, and Sodium overwrites the colour of every
	 * fully transparent texel with the sprite's average before the atlas is built, so a halo whose
	 * corners are transparent black came out with bright corners, a square, under Sodium. Here the
	 * shape lives in alpha and the colour is white in every texel, and a texel with no alpha adds
	 * nothing whatever colour a mod gives it.
	 */
	private static final RenderType GLOW = RenderType.create("createtrainnext:glow",
		DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 256, false, false, RenderType.CompositeState.builder()
			.setShaderState(RenderStateShard.RENDERTYPE_SOLID_SHADER)
			.setTextureState(RenderStateShard.BLOCK_SHEET)
			.setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
			.setCullState(RenderStateShard.NO_CULL)
			.setLightmapState(RenderStateShard.LIGHTMAP)
			.setWriteMaskState(RenderStateShard.COLOR_WRITE)
			.createCompositeState(false));

	public static RenderType glow() {
		return GLOW;
	}

	private CTNRenderTypes() {}
}
