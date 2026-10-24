package com.eyunya.createtrainnext.signal;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.signal.NextSignalFinder.Ahead;
import com.simibubi.create.api.contraption.transformable.TransformableBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A 中継信号機 — a relay, which repeats the next signal and does nothing else.
 *
 * <p>It has no authority over anything. A train passing it neither reads it nor changes it; it
 * reserves no block, holds nothing back, and imposes no speed. That is not a simplification, it is
 * what a relay is: it exists so a driver who cannot yet see a signal — round a curve, over a crest,
 * behind a platform roof — learns what it is showing in time to act on it.
 *
 * <p>Which is why, unlike {@link MultiAspectSignalBlockEntity}, this puts <em>nothing</em> in the
 * track graph. It keeps a {@link TrackTargetingBehaviour} for the sake of the placement it gives —
 * Create's click-the-track-then-place, and the marker drawn on the track it points at — but
 * {@link RelayTargetingBehaviour} refuses to create the edge point that behaviour would normally
 * register. A point on an edge is the only way a trackside block can affect a train, and this does
 * not have one, so it cannot. The targeting is used for one thing only: to work out where on the
 * graph this stands, so {@link NextSignalFinder} knows where to start looking.
 */
public class RelaySignalBlockEntity extends SmartBlockEntity implements TransformableBlockEntity {

	private static final String TAG_INDICATION = "Indication";

	/**
	 * Slower than the game ticks. A relay is a repeater of something already a block or more away;
	 * half a second of lag on it cannot matter, and the graph walk behind it is not free.
	 */
	private static final int INDICATION_TICK_RATE = 10;

	public TrackTargetingBehaviour<SignalBoundary> target;

	private RelayIndication indication = RelayIndication.DARK;

	public RelaySignalBlockEntity(BlockPos pos, BlockState state) {
		super(CTNRegistry.RELAY_SIGNAL_ENTITY.get(), pos, state);
		setLazyTickRate(INDICATION_TICK_RATE);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		behaviours.add(target = new RelayTargetingBehaviour(this));
	}

	public RelayIndication getIndication() {
		return indication;
	}

	@Override
	public void lazyTick() {
		super.lazyTick();
		if (level == null || level.isClientSide())
			return;

		RelayIndication resolved = resolveIndication();
		if (resolved == indication)
			return;

		indication = resolved;
		// notifyUpdate rather than setChanged: the lamps are drawn on the client, so a changed
		// indication is only visible once it has been sent there.
		notifyUpdate();
	}

	private RelayIndication resolveIndication() {
		if (!target.hasValidTrack())
			return RelayIndication.DARK;

		TrackGraphLocation loc = target.determineGraphLocation();
		if (loc == null)
			return RelayIndication.DARK;

		List<Ahead> ahead = NextSignalFinder.find(loc.graph, loc);
		if (ahead.isEmpty())
			return RelayIndication.DARK;

		// At a junction there is a signal down each branch and no way to know which one a train
		// will meet. The most restrictive of them is the only one safe to promise, and since the
		// aspects run from 停止 upward, that is simply the lowest.
		SignalAspect worst = null;
		for (Ahead next : ahead) {
			SignalAspect aspect = aspectOf(loc.graph, next);
			if (aspect == null)
				continue;
			if (worst == null || aspect.ordinal() < worst.ordinal())
				worst = aspect;
		}
		return RelayIndication.of(worst);
	}

	/**
	 * What the signal ahead is showing.
	 *
	 * <p>Asked of the head itself where there is one, rather than worked out again from the track.
	 * A relay that recomputed would be a second opinion, and could disagree with the signal it is
	 * supposed to be repeating — most easily over which ladder the head steps up, which is a fact about
	 * the type of head and not something any amount of looking at the track could reveal.
	 *
	 * <p>Falls back to resolving it only when the head cannot be read: an ordinary Create signal
	 * with no aspect of its own, or one whose chunk is not loaded. The fallback assumes a short
	 * ladder, one that can only ever make the relay more restrictive than a signal of this mod's,
	 * never less.
	 */
	@Nullable
	private SignalAspect aspectOf(TrackGraph graph, Ahead ahead) {
		if (level != null)
			for (BlockPos pos : ahead.boundary().blockEntities.get(ahead.side())
				.keySet())
				if (level.isLoaded(pos)
					&& level.getBlockEntity(pos) instanceof MultiAspectSignalBlockEntity head)
					return head.getAspect();

		return AspectResolver.resolve(graph, ahead.boundary(), ahead.side(),
			SignalAspect.Ladder.FOUR);
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		NBTHelper.writeEnum(tag, TAG_INDICATION, indication);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		if (tag.contains(TAG_INDICATION))
			indication = NBTHelper.readEnum(tag, TAG_INDICATION, RelayIndication.class);
	}

	@Override
	protected AABB createRenderBoundingBox() {
		// Reaches the targeted track, which can be several blocks away, because the placement
		// marker drawn on it belongs to this block entity.
		return new AABB(Vec3.atLowerCornerOf(worldPosition), Vec3.atLowerCornerOf(target.getGlobalPosition()))
			.inflate(2);
	}

	@Override
	public void transform(BlockEntity be, StructureTransform transform) {
		target.transform(be, transform);
	}

	/**
	 * Targeting without ever becoming a point on the track.
	 *
	 * <p>{@link TrackTargetingBehaviour} normally registers an edge point on the graph the first
	 * time it ticks, which is what makes a station a station and a signal a signal. Refusing to is
	 * the whole of what makes this a relay: no point means nothing for a train to reserve, obey,
	 * brake for, or trip. Everything else the behaviour does — remembering which track was clicked
	 * and from which side, surviving being moved by a contraption, drawing the marker — is wanted
	 * and kept.
	 *
	 * <p>The type it is constructed with is inert, since the only thing that ever reads it is the
	 * creation being overridden here. Create's own SIGNAL is passed rather than a type of this
	 * mod's so that the placement check the item runs treats a relay like a signal for the purpose
	 * of not stacking two points on one spot; nothing else follows from it.
	 */
	private static class RelayTargetingBehaviour extends TrackTargetingBehaviour<SignalBoundary> {

		RelayTargetingBehaviour(SmartBlockEntity be) {
			super(be, EdgePointType.SIGNAL);
		}

		@Override
		public SignalBoundary createEdgePoint() {
			return null;
		}
	}
}
