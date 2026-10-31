package com.eyunya.createtrainnext.track;

import java.util.List;
import java.util.Map.Entry;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.trains.entity.TravellingPoint.SteerDirection;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Which way a driven train goes at one of this mod's turnouts: straight on is the line, and left and right are
 * the ways out on either side of it.
 *
 * <p>Create steers by comparing each way out with the direction the train arrives in, and it measures that as
 * the chord across the last quarter of the edge it is on ({@code TravellingPoint#steer},
 * {@code TrackEdge#getDirection}). Off a curve that chord is turned by an eighth of the curve's bend, so at a
 * turnout, where every way out leaves along the same heading, straight on picks whichever way carries the
 * curve on, and left and right pick the two outermost. With a branch on each side of the line, coming off a
 * branch, that is the branch on the far side for straight on, and nothing at all ever picks the line in the
 * middle.
 *
 * <p>Here the ways out are measured against the line's own direction instead, which they all leave along, so
 * that they fall either side of it by how they bend: straight on is the line, left the way furthest left,
 * right the way furthest right. Anywhere that is not a turnout of this mod, and wherever the line is not one
 * of the ways out, Create's own choice stands.
 */
public final class TurnoutSteering {

	private TurnoutSteering() {}

	/**
	 * The way out of {@code at} a train steered {@code direction} takes, or null to leave it to Create.
	 *
	 * @param targets the ways out a train may take there, as Create lists them for its selector.
	 */
	@Nullable
	public static Entry<TrackNode, TrackEdge> choose(@Nullable TrackNode at, SteerDirection direction, Vec3 upNormal,
		List<Entry<TrackNode, TrackEdge>> targets) {
		if (at == null || targets.size() < 2)
			return null;
		AngledTrackAnchorBlockEntity turnout = turnoutAt(at.getLocation());
		if (turnout == null)
			return null;

		Entry<TrackNode, TrackEdge> line = null;
		for (Entry<TrackNode, TrackEdge> target : targets)
			if (!leadsToBranch(turnout, target.getValue())) {
				// More than one that is not a branch is not a turnout this knows how to read.
				if (line != null)
					return null;
				line = target;
			}
		if (line == null)
			return null;
		if (direction == SteerDirection.NONE)
			return line;

		Vec3 side = line.getValue()
			.getDirection(true)
			.cross(upNormal);
		Entry<TrackNode, TrackEdge> best = null;
		double bestAcross = 0;
		for (Entry<TrackNode, TrackEdge> target : targets) {
			double across = side.dot(target.getValue()
				.getDirection(true));
			// Left is the way most to the left, as Create's own LEFT is the one nearest -1; right the other way.
			double score = direction == SteerDirection.LEFT ? -across : across;
			if (best == null || score > bestAcross) {
				best = target;
				bestAcross = score;
			}
		}
		return best;
	}

	/** Whether {@code edge} is the curve from {@code turnout} to one of its branches. */
	private static boolean leadsToBranch(AngledTrackAnchorBlockEntity turnout, TrackEdge edge) {
		if (!edge.isTurn())
			return false;
		BezierConnection curve = edge.getTurn();
		BlockPos here = turnout.getBlockPos();
		for (boolean first : new boolean[] { true, false }) {
			BlockPos end = first ? curve.bePositions.getFirst() : curve.bePositions.getSecond();
			if (!end.equals(here) && turnout.isBranch(end))
				return true;
		}
		return false;
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
