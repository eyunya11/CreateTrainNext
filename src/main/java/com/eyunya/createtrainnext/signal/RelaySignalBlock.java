package com.eyunya.createtrainnext.signal;

import java.util.EnumMap;
import java.util.Map;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.pole.PoleConnectable;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A 中継信号機, the block.
 *
 * <p>Placed like a signal and looking like one, but it is not one and nothing about it touches a
 * train — see {@link RelaySignalBlockEntity} for why that is a property of how it is built rather
 * than a promise. It has no wrench setting and no value box, because it has nothing to set: what it
 * shows is decided entirely by the signal it is pointed at.
 *
 * <p>The housing is a blockstate model. Only the seven lamps are drawn by the block entity
 * renderer, since which three of them are lit changes with the signal ahead.
 */
public class RelaySignalBlock extends Block implements IBE<RelaySignalBlockEntity>, PoleConnectable {

	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

	/**
	 * Degrees the housing model is turned by to face a given direction, in the sense a blockstate
	 * variant's {@code y} means. Zero for south because that is the way the model was drawn: its
	 * lamps sit at z=10, on the +z side of the panel — the same convention the five-aspect head uses.
	 */
	public static float modelYaw(BlockState state) {
		return state.getValue(FACING)
			.get2DDataValue() * 90F;
	}

	/**
	 * One box per facing, the model's own bounds turned about the block's centre.
	 *
	 * <p>The head is wider than a block is deep, so unlike the five-aspect signal there is no offset
	 * to multiply this by — a relay is read from directly ahead and has no reason to sit anywhere
	 * but centred.
	 */
	private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

	static {
		// Drawn facing south: x 2.5..13.5, z 7..11.5, measured off the housing's own elements with
		// their rotations applied. Each further facing is the previous one turned a quarter clockwise
		// about the block centre, which is (x, z) -> (16 - z, x) - the same turn the blockstate's own
		// 'y' applies to the model.
		SHAPES.put(Direction.SOUTH, Block.box(2.5, 0, 7, 13.5, 16, 11.5));
		SHAPES.put(Direction.WEST, Block.box(4.5, 0, 2.5, 9, 16, 13.5));
		SHAPES.put(Direction.NORTH, Block.box(2.5, 0, 4.5, 13.5, 16, 9));
		SHAPES.put(Direction.EAST, Block.box(7, 0, 2.5, 11.5, 16, 13.5));
	}

	public RelaySignalBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder.add(FACING));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection()
			.getOpposite());
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES.get(state.getValue(FACING));
	}

	/**
	 * An empty hand turns the head a quarter turn, sneak to go back — the same as the five-aspect
	 * head and this mod's track anchors.
	 *
	 * <p>Which way a relay faces is only about who can see it: it reads the same signal whichever
	 * way it is turned, because what it reads is fixed by the track it was pointed at when it was
	 * placed, not by which way it looks now.
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
		BlockHitResult hit) {
		if (level.isClientSide)
			return InteractionResult.SUCCESS;

		Direction facing = state.getValue(FACING);
		Direction turned = player.isShiftKeyDown() ? facing.getCounterClockWise() : facing.getClockWise();
		level.setBlock(pos, state.setValue(FACING, turned), Block.UPDATE_ALL);
		return InteractionResult.SUCCESS;
	}

	/** Poles join from behind or below, never from the front, which has to stay clear to be read. */
	@Override
	public boolean connectsToPole(BlockState state, Direction towardsPole) {
		return towardsPole == Direction.DOWN || towardsPole == state.getValue(FACING)
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
