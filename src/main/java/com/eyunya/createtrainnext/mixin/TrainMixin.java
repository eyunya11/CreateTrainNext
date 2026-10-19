package com.eyunya.createtrainnext.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.eyunya.createtrainnext.speed.SpeedLimitEdgePoint;
import com.eyunya.createtrainnext.speed.TrainSpeedLimits;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint.IEdgePointListener;

/**
 * The two places a train has to be told about speed limits, because Create has no way to be asked.
 *
 * <p>Edge points are Create's own list: what a train does on passing one is spelled out in
 * {@code Train#frontSignalListener}, for stations, observers and signals, and there is nowhere to
 * add another kind. And how fast a train may go is {@code Train#maxSpeed}, which is read from the
 * config every time it is asked. Both are wrapped here and nothing else of Create's is touched; the
 * rest lives in {@link TrainSpeedLimits}.
 */
@Mixin(value = Train.class, remap = false)
public class TrainMixin {

	/** The top speed, held to the limit of the last sign the train passed. */
	@Inject(method = "maxSpeed", at = @At("RETURN"), cancellable = true)
	private void createtrainnext$capSpeed(CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(TrainSpeedLimits.cap((Train) (Object) this, cir.getReturnValue()));
	}

	/** What the front of the train does on passing a point: its own business first, and a limit sign's as well. */
	@Inject(method = "frontSignalListener", at = @At("RETURN"), cancellable = true)
	private void createtrainnext$noteLimits(CallbackInfoReturnable<IEdgePointListener> cir) {
		Train train = (Train) (Object) this;
		IEdgePointListener create = cir.getReturnValue();
		cir.setReturnValue((distance, passed) -> {
			if (passed.getFirst()instanceof SpeedLimitEdgePoint limit)
				TrainSpeedLimits.passed(train, limit, passed.getSecond()
					.getSecond());
			return create.test(distance, passed);
		});
	}
}
