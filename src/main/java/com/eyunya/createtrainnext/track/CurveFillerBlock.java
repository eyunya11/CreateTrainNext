package com.eyunya.createtrainnext.track;

import org.jetbrains.annotations.Nullable;

import com.eyunya.createtrainnext.CTNRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The floor under a curve: an invisible block that holds up whoever walks along it.
 *
 * <p>Create's own curves have no physical presence at all. Its straight track carries a two pixel
 * collision slab, but a bezier between two track blocks is drawn and nothing more — the
 * {@code fake_track} it scatters along a curve is declared {@code noCollission()} and
 * {@code replaceable()}, and exists to keep mobs from pathing across the curve, to make footsteps
 * sound right, and to put the line on the train map. Walking onto one of Create's own curves drops
 * you through it.
 *
 * <p>This is that missing slab, one block at a time. The block entity holds the height the rails pass at
 * over each quarter-block column of it, so a curve that climbs is held up at the height it is actually
 * drawn at rather than at the floor, even at 45 degrees: see {@link CurveFillerBlockEntity}. {@link #HEIGHT}
 * is the lowest of them, and what the whole block holds up at when there are no columns to go by.
 *
 * <p>Invisible and untargetable on purpose. It has no outline shape, so it cannot be looked at or
 * broken, and it is {@code replaceable}, so building over it simply takes it out — the way to
 * change a route is to work on its anchors, and a scattering of small blocks that could be mined
 * one by one would only be something to get wrong. {@link CurveOccupancy} puts them back within a
 * few seconds of anything that moves the curve.
 */
public class CurveFillerBlock extends Block implements EntityBlock {

	/** Where in the block the rail runs at its lowest, in pixels from the bottom. */
	public static final IntegerProperty HEIGHT = IntegerProperty.create("height", 0, 15);

	/** Matched to Create's own {@code AllShapes.TRACK_COLLISION}, which is a two pixel slab. */
	private static final VoxelShape[] SHAPES = new VoxelShape[16];

	static {
		for (int height = 0; height < 16; height++)
			SHAPES[height] = Block.box(0, height, 0, 16, height + 2, 16);
	}

	public CurveFillerBlock(Properties properties) {
		super(properties.randomTicks()
			.noOcclusion()
			.replaceable()
			.noLootTable());
		registerDefaultState(defaultBlockState().setValue(HEIGHT, 0));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HEIGHT);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
		CollisionContext context) {
		if (level.getBlockEntity(pos) instanceof CurveFillerBlockEntity filler) {
			VoxelShape columns = filler.collisionShape();
			if (columns != null)
				return columns;
		}
		return SHAPES[state.getValue(HEIGHT)];
	}

	/**
	 * Nothing to look at, which is what keeps this block out of the player's way.
	 *
	 * <p>An outline would make it minable, and a curve is not a thing that should come apart one
	 * hidden block at a time. It also means the anchor or the terrain behind the curve stays the
	 * thing a click lands on.
	 */
	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
		return Shapes.empty();
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	/** Same answer Create's own marker gives, so mobs treat a curve as somewhere not to walk. */
	@Override
	public @Nullable PathType getBlockPathType(BlockState state, BlockGetter level, BlockPos pos, @Nullable Mob mob) {
		return PathType.DAMAGE_OTHER;
	}

	// Both of these mean "handled, do not spawn the default", which is what an invisible block
	// wants: there is no texture here to throw up as dust, and a player running along a curve
	// kicking up untextured squares would be worse than one kicking up nothing. Create's own
	// marker answers the same way.

	@Override
	public boolean addLandingEffects(BlockState state, ServerLevel level, BlockPos pos, BlockState state2,
		LivingEntity entity, int numberOfParticles) {
		return true;
	}

	@Override
	public boolean addRunningEffects(BlockState state, Level level, BlockPos pos, Entity entity) {
		return true;
	}

	// ---------------------------------------------------------------- upkeep

	/**
	 * Fillers time out rather than being tracked.
	 *
	 * <p>The anchor that owns a curve puts its fillers back every few seconds, so anything still
	 * standing under a curve is renewed and anything left over from a curve that has moved is not.
	 * That is the only way to clean up after a change made while the far half of a long curve was
	 * in an unloaded chunk, which no amount of bookkeeping at the anchor can reach. Create's own
	 * marker blocks work exactly this way.
	 */
	@Override
	protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (level.getBlockEntity(pos) instanceof CurveFillerBlockEntity filler)
			filler.expire();
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new CurveFillerBlockEntity(pos, state);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState();
	}

	@Override
	protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
		BlockPos pos, BlockPos neighbourPos) {
		return state;
	}

	/**
	 * Puts a filler at {@code pos} with its columns at these heights, or renews the one already there.
	 *
	 * @param floor the heights {@link CurveOccupancy#rasterise} gives the block.
	 */
	public static void placeOrRenew(Level level, BlockPos pos, byte[] floor) {
		int height = CurveOccupancy.lowest(floor);
		BlockState existing = level.getBlockState(pos);
		if (existing.is(CTNRegistry.CURVE_FILLER.get())) {
			if (existing.getValue(HEIGHT) != height)
				level.setBlock(pos, existing.setValue(HEIGHT, height), Block.UPDATE_CLIENTS);
			if (level.getBlockEntity(pos) instanceof CurveFillerBlockEntity filler) {
				filler.setFloor(floor);
				filler.keepAlive();
			}
			return;
		}

		if (!existing.canBeReplaced() || !existing.getFluidState()
			.isEmpty())
			return;

		level.setBlock(pos, CTNRegistry.CURVE_FILLER.get()
			.defaultBlockState()
			.setValue(HEIGHT, height), Block.UPDATE_CLIENTS);
		if (level.getBlockEntity(pos) instanceof CurveFillerBlockEntity filler)
			filler.setFloor(floor);
	}

	/** Takes a filler away, if that is what is there. Anything else is left alone. */
	public static void remove(Level level, BlockPos pos) {
		if (level.getBlockState(pos)
			.is(CTNRegistry.CURVE_FILLER.get()))
			level.removeBlock(pos, false);
	}
}
