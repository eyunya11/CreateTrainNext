package com.eyunya.createtrainnext.signal;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CreateTrainNext;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.signal.SignalBoundary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Which kind of head stands where, known whether or not its chunk is loaded.
 *
 * <p>What a head shows is worked out from the track graph alone ({@link AspectResolver}), and the graph is
 * there whether or not anyone is near. The one thing the block entity adds is which head it is, and that is
 * its block, which cannot be read in a chunk that is not loaded. Create's own signals work the same with or
 * without a player nearby, so a head of this mod's has to as well, or a train runs differently depending on
 * who is watching. So the server keeps a table of the heads it has seen: a head writes itself in when it is
 * placed or loaded, and is taken out when its block is broken or replaced by another. A chunk being unloaded
 * leaves it in; that is the whole point of the table.
 *
 * <p>A head placed in a world from before the table, in a chunk that has not been loaded since, is not in it,
 * and is still not known until it is loaded once.
 *
 * <p>The table is saved with the overworld, as Create's railway data is, keyed by dimension and position.
 * Everything here is to be called on the server thread.
 *
 * <p>{@link #typeAt} and {@link #aspectOf} are for other mods too, which may call them by reflection; keep
 * their names and parameters as they are.
 */
public final class SignalHeads {

	private SignalHeads() {}

	/**
	 * The kind of head at a position, or null if there is none there or it is not known.
	 *
	 * <p>A loaded position is read from its block, and an unloaded one from the table. The answer is an enum
	 * constant of {@link SignalType}; a caller without this mod on its class path can take its {@code name()}
	 * ({@code "TWO"} to {@code "SIX"}).
	 */
	@Nullable
	public static SignalType typeAt(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos) {
		ServerLevel level = server.getLevel(dimension);
		if (level != null && level.isLoaded(pos))
			return level.getBlockState(pos)
				.getBlock() instanceof MultiAspectSignalBlock head ? head.signalType() : null;
		return recordedType(server, dimension, pos);
	}

	/**
	 * What the heads on one side of a signal show now, loaded or not: the strictest of them if there are
	 * several, or null if the side has no head whose type is known (a signal of Create's own, or a head the
	 * server has never seen loaded).
	 *
	 * <p>Worked out afresh from the graph, as a head does every half second, and nothing is changed: a
	 * loaded head's lamps are left to catch up on their own. The answer is an enum constant of
	 * {@link SignalAspect}, from {@code "STOP"}, the strictest, to {@code "HIGH_SPEED_PROCEED"}; its
	 * {@code name()} serves a caller without this mod on its class path.
	 *
	 * @param side the side whose heads to read, as Create's {@code SignalBoundary#isPrimary(TrackNode)}
	 *        gives it for the node a train is heading for.
	 */
	@Nullable
	public static SignalAspect aspectOf(MinecraftServer server, SignalBoundary signal, boolean side) {
		ResourceKey<Level> dimension = signal.edgeLocation.getFirst().dimension;
		TrackGraph graph = null;
		boolean graphLooked = false;
		SignalAspect strictest = null;
		for (BlockPos pos : signal.blockEntities.get(side)
			.keySet()) {
			SignalType type = typeAt(server, dimension, pos);
			if (type == null)
				continue;
			if (!graphLooked) {
				graph = graphOf(signal);
				graphLooked = true;
			}
			SignalAspect aspect = resolve(graph, signal, side, type);
			if (strictest == null || aspect.ordinal() < strictest.ordinal())
				strictest = aspect;
		}
		return strictest;
	}

	/** What the table says is at a position, without looking at the block even if it is loaded. */
	@Nullable
	public static SignalType recordedType(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos) {
		return Data.get(server)
			.type(GlobalPos.of(dimension, pos));
	}

	/**
	 * What a head of {@code type} on {@code side} of {@code signal} shows, worked out now from the graph, as
	 * the head itself would: 停止 where there is no graph to count blocks in.
	 */
	public static SignalAspect resolve(@Nullable TrackGraph graph, SignalBoundary signal, boolean side,
		SignalType type) {
		if (graph == null)
			return SignalAspect.STOP;
		return AspectResolver.resolve(graph, signal, side, type.ladder());
	}

	/**
	 * The graph a signal is in, or null if none holds it. Every network is looked through, so a caller that
	 * asks often should keep the answer and check it with {@code getPoint} first.
	 */
	@Nullable
	public static TrackGraph graphOf(SignalBoundary signal) {
		for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values())
			if (graph.getPoint(EdgePointType.SIGNAL, signal.getId()) == signal)
				return graph;
		return null;
	}

	/** Writes a head into the table: done by the head as it is placed or loaded. */
	public static void record(ServerLevel level, BlockPos pos, SignalType type) {
		Data.get(level.getServer())
			.put(GlobalPos.of(level.dimension(), pos.immutable()), type);
	}

	/** Takes a head out of the table: done by its block as it is broken or replaced. */
	public static void forget(ServerLevel level, BlockPos pos) {
		Data.get(level.getServer())
			.remove(GlobalPos.of(level.dimension(), pos));
	}

	/** The table itself. */
	public static class Data extends SavedData {

		private static final String NAME = CreateTrainNext.ID + "_signal_heads";
		private static final String TAG_HEADS = "Heads";
		private static final String TAG_DIMENSION = "Dimension";
		private static final String TAG_POS = "Pos";
		private static final String TAG_TYPE = "Type";

		private final Map<GlobalPos, SignalType> heads = new HashMap<>();

		static Data get(MinecraftServer server) {
			return server.overworld()
				.getDataStorage()
				.computeIfAbsent(new SavedData.Factory<>(Data::new, Data::load, null), NAME);
		}

		@Nullable
		SignalType type(GlobalPos pos) {
			return heads.get(pos);
		}

		void put(GlobalPos pos, SignalType type) {
			// A head is written in every time its chunk loads, which is almost always to say what is
			// there already, and that is not worth saving the file for.
			if (heads.put(pos, type) != type)
				setDirty();
		}

		void remove(GlobalPos pos) {
			if (heads.remove(pos) != null)
				setDirty();
		}

		@Override
		public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
			ListTag list = new ListTag();
			heads.forEach((pos, type) -> {
				CompoundTag entry = new CompoundTag();
				entry.putString(TAG_DIMENSION, pos.dimension()
					.location()
					.toString());
				entry.putLong(TAG_POS, pos.pos()
					.asLong());
				entry.putString(TAG_TYPE, type.name());
				list.add(entry);
			});
			tag.put(TAG_HEADS, list);
			return tag;
		}

		public static Data load(CompoundTag tag, HolderLookup.Provider registries) {
			Data data = new Data();
			for (Tag each : tag.getList(TAG_HEADS, Tag.TAG_COMPOUND)) {
				CompoundTag entry = (CompoundTag) each;
				ResourceLocation dimension = ResourceLocation.tryParse(entry.getString(TAG_DIMENSION));
				SignalType type = typeNamed(entry.getString(TAG_TYPE));
				// An entry that cannot be read is dropped rather than failing the whole table: the head
				// writes itself in again the next time it loads.
				if (dimension == null || type == null)
					continue;
				data.heads.put(GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dimension),
					BlockPos.of(entry.getLong(TAG_POS))), type);
			}
			return data;
		}

		@Nullable
		private static SignalType typeNamed(String name) {
			for (SignalType type : SignalType.values())
				if (type.name()
					.equals(name))
					return type;
			return null;
		}
	}
}
