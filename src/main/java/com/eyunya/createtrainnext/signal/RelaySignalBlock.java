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
 * A 中継信号機, the block.
 *
 * <p>Placed like a signal and looking like one, but it is not one and nothing about it touches a
 * train — see {@link RelaySignalBlockEntity} for why that is a property of how it is built rather
 * than a promise. It has no wrench setting and no value box, because it has nothing to set: what it
 * shows is decided entirely by the signal it is pointed at, and which way it faces is decided when
 * it is placed. Right clicking one does nothing.
 *
 * <p>The head turns in 22.5 degree steps, which is why only the mast is left in the blockstate
 * model: a blockstate turns in quarters, so the head and its seven lamps are drawn by the block
 * entity renderer instead. See {@link SignalRotation} for why there is no way around that.
 */
public class RelaySignalBlock extends Block implements IBE<RelaySignalBlockEntity>, PoleConnectable {

	/** Sixteen angles, 22.5 degrees apart. See {@link SignalRotation} for the whole of the arithmetic. */
	public static final IntegerProperty ROTATION = SignalRotation.ROTATION;

	/**
	 * Degrees the head is turned by, in the sense a blockstate variant's {@code y} means. Zero for
	 * south because that is the way the model was drawn: its lamps sit on the +z side of the panel —
	 * the same convention the five-aspect head uses.
	 */
	public static float modelYaw(BlockState state) {
		return SignalRotation.modelYaw(state);
	}

	/** The central pole, which never turns and is therefore the same box in every shape. */
	private static final VoxelShape MAST = Block.box(7, 0, 7, 9, 16, 9);

	/**
	 * The head as drawn, unturned, read off housing.json with its element rotations applied. The
	 * mast is not in here — it is added to every shape unchanged.
	 */
	private static final double[] HEAD_BOUNDS = { 2.5, 2.5, 9, 13.5, 13.5, 12.5 };

	/** One shape per segment: the mast, plus the head's own box turned to match. */
	private static final VoxelShape[] SHAPES = new VoxelShape[SignalRotation.SEGMENTS];

	static {
		for (int segment = 0; segment < SignalRotation.SEGMENTS; segment++)
			SHAPES[segment] = Shapes.or(MAST, SignalRotation.spin(HEAD_BOUNDS[0], HEAD_BOUNDS[1],
				HEAD_BOUNDS[2], HEAD_BOUNDS[3], HEAD_BOUNDS[4], HEAD_BOUNDS[5],
				segment * SignalRotation.DEGREES_PER_SEGMENT));
	}

	public RelaySignalBlock(Properties properties) {
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

	/** Poles join from behind or below, never from the front, which has to stay clear to be read. */
	@Override
	public boolean connectsToPole(BlockState state, Direction towardsPole) {
		// Rounded to the nearest quarter, because a pole can only arrive from one of four sides
		// however finely the head itself is turned.
		return towardsPole == Direction.DOWN || towardsPole == SignalRotation.nearestFacing(state)
			.getOpposite();
	}

	@Override
	public Class<RelaySignalBlockEntity> getBlockEntityClass() {
		return RelaySignalBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends RelaySignalBlockEntity> getBlockEntityType() {
		return CTNRegistry.RELAY_SIGNAL_ENTITY.get();
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		// Nothing here has anything in the track graph to take back out, unlike the five-aspect
		// head; this is IBE's own removal rather than the plain one only so the block entity's
		// behaviours are told they are going, the same as every other block of this mod's.
		IBE.onRemove(state, level, pos, newState);
	}
}
