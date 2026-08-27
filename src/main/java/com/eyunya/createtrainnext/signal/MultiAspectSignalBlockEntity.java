package com.eyunya.createtrainnext.signal;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNRegistry;
import com.simibubi.create.Create;
import com.simibubi.create.api.contraption.transformable.TransformableBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A five-aspect signal head, standing on one of Create's own signal boundaries.
 *
 * <p>The targeting behaviour is built with {@link EdgePointType#SIGNAL} rather than a type of this
 * mod's own, and that is the load-bearing decision. Create's train control reaches for
 * {@code SignalBoundary} by its concrete class and for {@code EdgePointType.SIGNAL} by name, in
 * {@code Navigation} and {@code Train}; a new point type would be invisible to all of it and
 * trains would run straight past this signal without braking or reserving anything. Being the same
 * kind of point also means these can be mixed freely with Create's own signals on one line.
 *
 * <p>So nothing here holds a train back. Create does that. All this adds is which lamps to light,
 * and {@link AspectResolver} works that out by counting Create's blocks.
 */
public class MultiAspectSignalBlockEntity extends SmartBlockEntity implements TransformableBlockEntity {

	private static final String TAG_ASPECT = "Aspect";
	private static final String TAG_RESTRICTED = "Restricted";

	/**
	 * Slower than the game ticks, and safe to be: the aspect is only what the lamps show. Braking
	 * and block reservation are Create's, run every tick on its own {@code SignalBoundary}, and are
	 * unaffected by how often this recomputes. Half a second of lag on a lamp is not worth a graph
	 * walk twenty times a second.
	 */
	private static final int ASPECT_TICK_RATE = 10;

	public TrackTargetingBehaviour<SignalBoundary> edgePoint;

	/** Starts at the most restrictive: a signal that has not worked itself out yet shows 停止. */
	private SignalAspect aspect = SignalAspect.STOP;

	private boolean restricted;

	/** Cached because finding it means scanning every network; revalidated before each use. */
	@Nullable
	private TrackGraph cachedGraph;

	public MultiAspectSignalBlockEntity(BlockPos pos, BlockState state) {
		super(CTNRegistry.MULTI_ASPECT_SIGNAL_ENTITY.get(), pos, state);
		setLazyTickRate(ASPECT_TICK_RATE);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		behaviours.add(edgePoint = new TrackTargetingBehaviour<>(this, EdgePointType.SIGNAL));
	}

	public SignalAspect getAspect() {
		return aspect;
	}

	/** Whether one clear block ahead shows 警戒 rather than 注意. */
	public boolean isRestricted() {
		return restricted;
	}

	public void setRestricted(boolean restricted) {
		this.restricted = restricted;
		notifyUpdate();
	}

	@Override
	public void lazyTick() {
		super.lazyTick();
		if (level == null || level.isClientSide())
			return;

		SignalAspect resolved = resolveAspect();
		if (resolved == aspect)
			return;

		aspect = resolved;
		// notifyUpdate rather than setChanged: the lamps live on the client, so a changed aspect is
		// only visible once it has been sent there.
		notifyUpdate();
	}

	private SignalAspect resolveAspect() {
		SignalBoundary signal = edgePoint.getEdgePoint();
		if (signal == null)
			return SignalAspect.STOP;

		Boolean side = sideFacedByThisHead(signal);
		if (side == null)
			return SignalAspect.STOP;

		TrackGraph graph = findGraph(signal);
		if (graph == null)
			return SignalAspect.STOP;

		return AspectResolver.resolve(graph, signal, side, restricted);
	}

	/**
	 * Which of the boundary's two sides this head reads.
	 *
	 * <p>A boundary is shared: two heads facing opposite ways along the same point each show their
	 * own side of it, so the head has to find itself in the boundary's records rather than assume.
	 */
	@Nullable
	private Boolean sideFacedByThisHead(SignalBoundary signal) {
		for (boolean first : Iterate.trueAndFalse)
			if (signal.blockEntities.get(first)
				.containsKey(worldPosition))
				return first;
		return null;
	}

	@Nullable
	private TrackGraph findGraph(SignalBoundary signal) {
		if (cachedGraph != null && cachedGraph.getPoint(EdgePointType.SIGNAL, signal.getId()) == signal)
			return cachedGraph;

		cachedGraph = null;
		for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values())
			if (graph.getPoint(EdgePointType.SIGNAL, signal.getId()) == signal) {
				cachedGraph = graph;
				break;
			}
		return cachedGraph;
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		NBTHelper.writeEnum(tag, TAG_ASPECT, aspect);
		tag.putBoolean(TAG_RESTRICTED, restricted);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		if (tag.contains(TAG_ASPECT))
			aspect = NBTHelper.readEnum(tag, TAG_ASPECT, SignalAspect.class);
		restricted = tag.getBoolean(TAG_RESTRICTED);
	}

	@Override
	protected AABB createRenderBoundingBox() {
		// Reaches the targeted track, which can be several blocks away, because the placement
		// marker drawn on it belongs to this block entity.
		return new AABB(Vec3.atLowerCornerOf(worldPosition), Vec3.atLowerCornerOf(edgePoint.getGlobalPosition()))
			.inflate(2);
	}

	@Override
	public void transform(BlockEntity be, StructureTransform transform) {
		edgePoint.transform(be, transform);
	}
}
