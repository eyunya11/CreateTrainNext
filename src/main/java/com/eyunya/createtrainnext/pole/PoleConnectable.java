package com.eyunya.createtrainnext.pole;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Implemented by blocks a {@link SignalPoleBlock} should grow into, rather than merely stand
 * next to.
 *
 * <p>Without this a pole only reaches into terrain along its own axis, so a signal head hung off
 * the side of a mast would sit a gap away from it. Implementing this lets the head say "grow into
 * me from that side", and the pole draws the short arm that closes the gap.
 *
 * <p>Note which way round the question is asked. Whether the pole can carry something is the
 * pole's own business, and that is just {@code instanceof SignalPoleBlock} - no interface needed.
 * What the pole cannot know is whether the block beside it wants to be joined to, because that
 * depends on which way that block is facing: a signal head turned east should be joined by the
 * pole to its west and by no other. So the neighbour is the one that answers.
 */
public interface PoleConnectable {

	/**
	 * @param state       this block's own state.
	 * @param towardsPole the direction from this block to the pole that is asking - so the side of
	 *                    this block the arm would arrive on.
	 * @return whether the pole should reach out and join up with this block.
	 */
	boolean connectsToPole(BlockState state, Direction towardsPole);
}
