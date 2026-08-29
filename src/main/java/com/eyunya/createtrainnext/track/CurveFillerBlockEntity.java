package com.eyunya.createtrainnext.track;

import com.eyunya.createtrainnext.CTNRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The countdown that lets a {@link CurveFillerBlock} clean up after itself.
 *
 * <p>Nothing is saved: a filler that survives a reload starts its count again, and the anchor that
 * owns the curve renews it within a few seconds if the curve is still there. That is the whole
 * point — the count exists to outlive a change made while this chunk was not loaded, and a value
 * carried across a reload would only make it outlive more.
 */
public class CurveFillerBlockEntity extends BlockEntity {

	/**
	 * Random ticks, not game ticks. A block is picked for one rarely enough that three of them is
	 * several minutes, while the anchor renews the filler every five seconds — so a filler under a
	 * live curve never gets close to running out.
	 */
	private static final int LIVES = 3;

	private int remaining = LIVES;

	public CurveFillerBlockEntity(BlockPos pos, BlockState state) {
		super(CTNRegistry.CURVE_FILLER_ENTITY.get(), pos, state);
	}

	public void keepAlive() {
		remaining = LIVES;
	}

	public void expire() {
		remaining--;
		if (remaining <= 0 && level != null)
			level.removeBlock(worldPosition, false);
	}
}
