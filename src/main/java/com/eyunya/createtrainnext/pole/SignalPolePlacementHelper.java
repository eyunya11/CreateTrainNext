package com.eyunya.createtrainnext.pole;

import java.util.function.Predicate;

import com.eyunya.createtrainnext.CTNRegistry;
import com.simibubi.create.foundation.placement.PoleHelper;

import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Create's placement assist for {@link SignalPoleBlock}: holding a pole and looking at one that is
 * already standing shows a ghost on the far end of the run, and clicking puts a pole there instead
 * of against the face being looked at.
 *
 * <p>This is what makes a tall mast bearable to build. Without it every block of a fifteen block
 * signal gantry has to be climbed to and placed by hand.
 *
 * <p>{@link PoleHelper} already walks the run and honours the server's assist range, so all that
 * is left to do is fix up the state that gets placed. Its own transform copies the axis across and
 * stops there, which is not enough here: a pole placed this way never goes through
 * {@link SignalPoleBlock#getStateForPlacement}, so without this its six connection booleans would
 * all arrive false and the new pole would be drawn as a stub floating clear of the run it was
 * meant to extend.
 */
public class SignalPolePlacementHelper extends PoleHelper<Axis> {

	public SignalPolePlacementHelper() {
		super(state -> state.getBlock() instanceof SignalPoleBlock, state -> state.getValue(SignalPoleBlock.AXIS),
			SignalPoleBlock.AXIS);
	}

	@Override
	public Predicate<ItemStack> getItemPredicate() {
		// Resolved on each call rather than captured: this helper is constructed while the block
		// class is loading, which is before the item it names has been registered.
		return stack -> stack.getItem() == CTNRegistry.SIGNAL_POLE_ITEM.get();
	}

	@Override
	public PlacementOffset getOffset(Player player, Level world, BlockState state, BlockPos pos, BlockHitResult ray) {
		PlacementOffset offset = super.getOffset(player, world, state, pos, ray);
		if (!offset.isSuccessful())
			return offset;

		BlockPos target = offset.getBlockPos();
		Axis axis = state.getValue(SignalPoleBlock.AXIS);
		// updateFromNeighbourShapes is what fills the connections in, by calling updateShape once
		// for each side of the new position. The axis has to be set first, because that is what
		// updateShape reads to decide whether an end is allowed to bury itself in terrain.
		//
		// Waterlogging is not handled here on purpose: PlacementOffset sets it itself, from the
		// fluid actually at the target, after this transform has run.
		return offset.withTransform(
			placed -> Block.updateFromNeighbourShapes(placed.setValue(SignalPoleBlock.AXIS, axis), world, target));
	}
}
