package com.eyunya.createtrainnext.mixin;

import java.util.Map.Entry;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.eyunya.createtrainnext.track.TurnoutSteering;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.entity.TravellingPoint.ITrackSelector;
import com.simibubi.create.content.trains.entity.TravellingPoint.SteerDirection;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackNode;

import net.minecraft.world.phys.Vec3;

/**
 * Steers a driven train through this mod's turnouts by the turnout's line, not by the bend of the curve it
 * arrives on: see {@link TurnoutSteering}. Create's own selector is kept for everywhere else, and is asked
 * whenever the turnout's answer is none.
 */
@Mixin(value = TravellingPoint.class, remap = false)
public abstract class TravellingPointMixin {

	@Inject(method = "steer", at = @At("RETURN"), cancellable = true)
	private void createtrainnext$steerThroughTurnouts(SteerDirection direction, Vec3 upNormal,
		CallbackInfoReturnable<ITrackSelector> cir) {
		ITrackSelector create = cir.getReturnValue();
		TravellingPoint self = (TravellingPoint) (Object) this;
		cir.setReturnValue((graph, pair) -> {
			// The node being passed is the end of the edge the point is on when the selector is asked.
			Entry<TrackNode, TrackEdge> chosen = TurnoutSteering.choose(self.node2, direction, upNormal,
				pair.getSecond());
			return chosen != null ? chosen : create.apply(graph, pair);
		});
	}
}
