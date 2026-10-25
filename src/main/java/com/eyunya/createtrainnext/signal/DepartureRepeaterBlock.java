package com.eyunya.createtrainnext.signal;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.pole.PoleConnectable;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A 出発反応標識, the block: a lamp on a platform that lights when the starting signal ahead has cleared.
 *
 * <p>At a station where the guard cannot see the starting signal, round a curve or under the roof, this
 * is what they look at before giving the right away: lit, the signal shows something other than 停止. It
 * is placed the way a relay is, against the track a train leaving the platform runs along, and reads the
 * next signal from there; see {@link DepartureRepeaterBlockEntity}. Like the relay it has nothing to set
 * and no say over a train.
 *
 * <p>The head turns in 22.5 degree steps and is drawn by the block entity renderer; only the pole it
 * hangs on is the blockstate's model. See {@link SignalRotation}.
 */
public class DepartureRepeaterBlock extends Block implements IBE<DepartureRepeaterBlockEntity>, PoleConnectable {

	public static final IntegerProperty ROTATION = SignalRotation.ROTATION;

	/** The pole through the middle of the block, the same box as the relay's mast. */
	private static final VoxelShape MAST = Block.box(7, 0, 7, 9, 16, 9);

	/**
	 * The lamp as drawn, unturned: x0, y0, z0, x1, y1, z1 in pixels. Its lit end is the face at z1. A game
	 * test holds the head model to it.
	 */
	public static final double[] HEAD_BOUNDS = { 5.5, 5.5, 9, 10.5, 10.5, 14 };

	private static final VoxelShape[] SHAPES = new VoxelShape[SignalRotation.SEGMENTS];

	static {
		for (int segment = 0; segment < SignalRotation.SEGMENTS; segment++)
			SHAPES[segment] = Shapes.or(MAST, SignalRotation.spin(HEAD_BOUNDS[0], HEAD_BOUNDS[1], HEAD_BOUNDS[2],
				HEAD_BOUNDS[3], HEAD_BOUNDS[4], HEAD_BOUNDS[5], segment * SignalRotation.DEGREES_PER_SEGMENT));
	}

	public DepartureRepeaterBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(ROTATION, 0));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder.add(ROTATION));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(ROTATION, SignalRotation.segmentForPlacement(context));
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(ROTATION, SignalRotation.rotate(state.getValue(ROTATION), rotation));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.setValue(ROTATION, SignalRotation.mirror(state.getValue(ROTATION), mirror));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES[state.getValue(ROTATION)];
	}

	/**
	 * Poles join from above, below or behind. Above as well as below, unlike the relay, because a platform's
	 * lamp is as often hung from the roof as stood on a post.
	 */
	@Override
	public boolean connectsToPole(BlockState state, Direction towardsPole) {
		return towardsPole == Direction.DOWN || towardsPole == Direction.UP
			|| towardsPole == SignalRotation.nearestFacing(state)
				.getOpposite();
	}

	@Override
	public Class<DepartureRepeaterBlockEntity> getBlockEntityClass() {
		return DepartureRepeaterBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends DepartureRepeaterBlockEntity> getBlockEntityType() {
		return CTNRegistry.DEPARTURE_REPEATER_ENTITY.get();
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		// As the relay: nothing in the graph to take out, but the behaviours are told they are going.
		IBE.onRemove(state, level, pos, newState);
	}
}
