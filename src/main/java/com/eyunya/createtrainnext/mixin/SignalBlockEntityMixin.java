package com.eyunya.createtrainnext.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.eyunya.createtrainnext.signal.SignalHeads;
import com.simibubi.create.content.trains.signal.SignalBlockEntity;

import net.minecraft.server.level.ServerLevel;

/**
 * Tells {@link SignalHeads} where Create's own signals stand, so that one in a chunk that is not loaded is
 * known to say nothing to a passing train, as it does when loaded, and is not taken for a head of this mod's
 * that the server has not seen.
 *
 * <p>Once per block entity, the first time it ticks on the server: the table is kept with the world, so
 * there is nothing to repeat, and Create has no event for a signal being placed or loaded.
 */
@Mixin(value = SignalBlockEntity.class, remap = false)
public class SignalBlockEntityMixin {

	@Unique
	private boolean createtrainnext$recorded;

	@Inject(method = "tick", at = @At("HEAD"))
	private void createtrainnext$recordAsNotAHead(CallbackInfo ci) {
		if (createtrainnext$recorded)
			return;
		SignalBlockEntity self = (SignalBlockEntity) (Object) this;
		if (!(self.getLevel() instanceof ServerLevel level))
			return;
		createtrainnext$recorded = true;
		SignalHeads.recordOther(level, self.getBlockPos());
	}
}
