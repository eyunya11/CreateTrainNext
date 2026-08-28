package com.eyunya.createtrainnext.track;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.createmod.catnip.data.WorldAttached;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

/**
 * Every loaded anchor in a level, so a curve can be found by where it is rather than by who owns it.
 *
 * <p>Needed because a curve is reported by the anchor at one end of it, and that anchor can be the
 * better part of thirty blocks from the point a player is standing next to. Finding it from the
 * point means either sweeping tens of thousands of block positions or keeping a list, and a list of
 * loaded block entities is a handful of entries per chunk.
 *
 * <p>Kept on both sides. The client uses it to show which curve a click would land on, the server
 * to act on that click, and them both reading the same index from the same block entities is what
 * makes those two the same curve. Create keeps an equivalent one for its own curves, but only on
 * the client and only holding its own block entity type.
 */
public class AnchorIndex {

	private static final WorldAttached<Map<BlockPos, AngledTrackAnchorBlockEntity>> ANCHORS =
		new WorldAttached<>(level -> new HashMap<>());

	private AnchorIndex() {}

	public static void add(LevelAccessor level, AngledTrackAnchorBlockEntity anchor) {
		ANCHORS.get(level)
			.put(anchor.getBlockPos(), anchor);
	}

	public static void remove(LevelAccessor level, AngledTrackAnchorBlockEntity anchor) {
		// By identity, not just by position: a block entity that has been replaced at the same spot
		// will have registered the new one already, and removing by position alone would drop it.
		Map<BlockPos, AngledTrackAnchorBlockEntity> anchors = ANCHORS.get(level);
		if (anchors.get(anchor.getBlockPos()) == anchor)
			anchors.remove(anchor.getBlockPos());
	}

	/**
	 * Every anchor loaded in this level, as a snapshot.
	 *
	 * <p>A copy rather than the live view, because walking it reads curves, reading a curve asks
	 * the level for the anchor at its far end, and asking for a block entity that has not been
	 * instantiated yet creates one — which registers itself here. Iterating the live map would then
	 * fail partway through, and only on the routes long enough to reach into a chunk whose block
	 * entities had not been touched yet.
	 */
	public static Iterable<AngledTrackAnchorBlockEntity> in(Level level) {
		return List.copyOf(ANCHORS.get(level)
			.values());
	}
}
