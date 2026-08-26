package com.eyunya.createtrainnext.speed;

import com.eyunya.createtrainnext.CreateTrainNext;
import com.simibubi.create.content.trains.graph.EdgePointType;

/**
 * The kinds of point this mod adds to Create's track graph.
 *
 * <p>{@link EdgePointType} is a plain static map rather than a game registry, so this is not a
 * {@code DeferredRegister} and there is no registry event to hook. What matters instead is that the
 * registration has happened before anything reads it, and the two readers are both late: a saved
 * graph resolves its points through {@code EdgePointType.TYPES} on world load, and a placed block
 * only looks its type up while ticking. Touching this class from the mod constructor is early
 * enough for both.
 */
public class CTNEdgePointTypes {

	/**
	 * A cap on how fast trains may run over one stretch of track.
	 *
	 * <p>The id is written into the graph's NBT verbatim, so renaming it would orphan every limit
	 * already placed in an existing world.
	 */
	public static final EdgePointType<SpeedLimitEdgePoint> SPEED_LIMIT =
		EdgePointType.register(CreateTrainNext.asResource("speed_limit"), SpeedLimitEdgePoint::new);

	/** Loads this class, which is what performs the registration above. */
	public static void register() {}

	private CTNEdgePointTypes() {}
}
