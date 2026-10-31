package com.eyunya.createtrainnext.track;

import java.util.List;
import java.util.Map.Entry;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.trains.entity.TravellingPoint.SteerDirection;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Which way a driven train goes at one of this mod's turnouts: straight on is the way that bends least, and left
 * and right the ways that bend furthest to either side.
 *
 * <p>Create steers by comparing each way out with the direction the train arrives in, and it measures that as
 * the chord across the last quarter of the edge it is on ({@code TravellingPoint#steer},
 * {@code TrackEdge#getDirection}). Off a curve that chord is turned by an eighth of the curve's bend, so at a
 * turnout, where every way out leaves along the same heading, straight on picks whichever way carries the
 * curve on, and left and right pick the two outermost. With a branch on each side of the line, coming off a
 * branch, that is the branch on the far side for straight on, and nothing at all ever picks the straight track
 * in the middle.
 *
 * <p>Here the ways out are measured against the turnout's own heading instead, which every one of them leaves
 * along, so that each lies to one side of it by as much as it bends, the same whichever way the train came in.
 * Which of them is the line and which the branches, as the anchor holds them, makes no difference: that
 * depends on the order they were drawn in, and a driver sees only the track. Anywhere that is not a turnout of
 * this mod, Create's own choice stands.
 */
public final class TurnoutSteering {

	private TurnoutSteering() {}

	/**
	 * The way out of {@code at} a train steered {@code direction} takes, or null to leave it to Create.
	 *
	 * @param forwards whether the point is moving along its edge or back along it. Create measures left and
	 *                 right against the edge either way, so for a train running backwards they are the other way
	 *                 round from the way it is going, as if seen from the end of the train that leads going
	 *                 forwards; this keeps to that, so that this mod's turnouts steer like Create's.
	 * @param targets  the ways out a train may take there, as Create lists them for its selector.
	 */
	@Nullable
	public static Entry<TrackNode, TrackEdge> choose(@Nullable TrackNode at, SteerDirection direction, Vec3 upNormal,
		boolean forwards, List<Entry<TrackNode, TrackEdge>> targets) {
		if (at == null || targets.size() < 2)
			return null;
		AngledTrackAnchorBlockEntity turnout = turnoutAt(at.getLocation());
		if (turnout == null)
			return null;

		// The heading on the side the ways out are on: the turnout's own all leave the node along it. Asked of
		// all of them together, since a node shared with Create's track can have a way out of Create's there
		// too, at any angle, and one across the heading would say nothing about which side it is.
		Vec3 heading = turnout.heading();
		double along = 0;
		for (Entry<TrackNode, TrackEdge> target : targets)
			along += target.getValue()
				.getDirection(true)
				.dot(heading);
		Vec3 out = along >= 0 ? heading : heading.scale(-1);
		Vec3 side = out.cross(upNormal);

		Entry<TrackNode, TrackEdge> best = null;
		double bestScore = 0;
		for (Entry<TrackNode, TrackEdge> target : targets) {
			double across = side.dot(target.getValue()
				.getDirection(true));
			double score;
			if (direction == SteerDirection.NONE)
				score = -Math.abs(across);
			else {
				// Left is the way most to the left, as Create's own LEFT is the one nearest -1; right the other
				// way. Backwards, both are seen from the other end of the train, as Create sees them.
				boolean left = (direction == SteerDirection.LEFT) == forwards;
				score = left ? -across : across;
			}
			if (best == null || score > bestScore) {
				best = target;
				bestScore = score;
			}
		}
		return best;
	}

	/**
	 * The anchor with branches whose node is at {@code location}, or null if there is none. Only on the server's
	 * own thread, which is where trains are driven: the level is read, and from anywhere else it is not safe to.
	 */
	@Nullable
	private static AngledTrackAnchorBlockEntity turnoutAt(TrackNodeLocation location) {
		MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
		if (server == null || !server.isSameThread())
			return null;
		ServerLevel level = server.getLevel(location.dimension);
		if (level == null)
			return null;
		for (BlockPos pos : location.allAdjacent()) {
			if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor)
				|| !anchor.hasBranch())
				continue;
			if (location.equals(new TrackNodeLocation(anchor.entryNode()).in(level))
				|| location.equals(new TrackNodeLocation(anchor.exitNode()).in(level)))
				return anchor;
		}
		return null;
	}
}
