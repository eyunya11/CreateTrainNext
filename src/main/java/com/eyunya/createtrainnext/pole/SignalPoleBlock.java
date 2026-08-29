package com.eyunya.createtrainnext.pole;

import java.util.Map;

import com.simibubi.create.content.equipment.wrench.IWrenchable;

import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A two pixel mast that trackside equipment hangs on.
 *
 * <p>One block and one item covers masts, gantry beams and the brackets between them, because
 * nothing about the pole is decided when it is registered. It is all decided the moment it is
 * placed, and then kept up to date as its neighbours change:
 *
 * <ul>
 * <li>{@link #AXIS} comes from the face that was clicked, so the same item dropped on the ground
 * stands up and pressed against a wall lies flat.</li>
 * <li>The six {@link #CONNECTIONS} say which neighbours this pole has grown an arm into. A pole
 * always joins another pole, joins a {@link PoleConnectable} that asks to be joined, and buries
 * its ends in solid terrain - but only its ends, so a mast planted in the ground does not also
 * weld itself to the wall it happens to stand beside.</li>
 * </ul>
 *
 * <p>The blockstate is multipart and mirrors {@link #buildShapes} exactly: a pole with nothing
 * branching across it is drawn as one length of mast running clean through the block, and one
 * that does have a branch is drawn as a hub with an arm per connection. That is what makes a tee
 * look like a tee - the mast stops where the run stops, rather than always filling its block.
 *
 * <p>Long runs are not meant to be placed a block at a time. Holding the item and clicking a pole
 * that is already down uses Create's placement assist to put the next one on the far end of the
 * run; see {@link SignalPolePlacementHelper}.
 */
public class SignalPoleBlock extends Block implements SimpleWaterloggedBlock, IWrenchable {

	/** Which way the mast runs. Taken from the clicked face, and cycled by a wrench. */
	public static final EnumProperty<Axis> AXIS = BlockStateProperties.AXIS;

	public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

	/**
	 * One boolean per face, borrowed wholesale from {@link PipeBlock} rather than declared again
	 * here. They are the same six vanilla properties a fence or a pane uses, so the blockstate
	 * file reads the way anyone would expect, and the map saves writing out the switch from
	 * {@link Direction} to property by hand.
	 */
	public static final Map<Direction, BooleanProperty> CONNECTIONS = PipeBlock.PROPERTY_BY_DIRECTION;

	/**
	 * Registering the helper is a side effect of this class being loaded, which happens when the
	 * block is registered. The id handed back is the only way to get the helper out again.
	 */
	private static final int PLACEMENT_HELPER_ID = PlacementHelpers.register(new SignalPolePlacementHelper());

	/** The centre the arms radiate from. */
	private static final VoxelShape HUB = Block.box(7, 7, 7, 9, 9, 9);

	private static final VoxelShape[] SHAPES = buildShapes();

	public SignalPoleBlock(Properties properties) {
		super(properties);
		BlockState state = defaultBlockState().setValue(AXIS, Axis.Y)
			.setValue(WATERLOGGED, false);
		// Not redundant: BooleanProperty offers true first, so an untouched state arrives with
		// every connection already set, and would draw a pole joined to all six of its neighbours
		// before a single one had been looked at.
		for (BooleanProperty connection : CONNECTIONS.values())
			state = state.setValue(connection, false);
		registerDefaultState(state);
	}

	// -------------------------------------------------------------- connections

	/**
	 * Whether a pole in the given state should reach out of its {@code side} face.
	 *
	 * @param neighbour    the state on that side, passed in rather than read back out of the
	 *                     level because {@link #updateShape} is handed a newer one than the level
	 *                     has.
	 * @param neighbourPos where that state lives, which is what a face's sturdiness is measured
	 *                     against.
	 */
	private static boolean connectsTo(BlockGetter level, BlockState state, Direction side, BlockState neighbour,
		BlockPos neighbourPos) {
		if (neighbour.getBlock() instanceof SignalPoleBlock)
			return true;
		if (neighbour.getBlock() instanceof PoleConnectable connectable)
			return connectable.connectsToPole(neighbour, side.getOpposite());
		if (Block.isExceptionForConnection(neighbour))
			return false;

		// Terrain is only joined at the ends of the run. Dropping the axis test would make a mast
		// grow a stub into every wall it passed, which is the difference between a pole planted in
		// the ground and one that looks welded into the scenery.
		return side.getAxis() == state.getValue(AXIS)
			&& neighbour.isFaceSturdy(level, neighbourPos, side.getOpposite());
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();

		BlockState state = defaultBlockState().setValue(AXIS, context.getClickedFace()
			.getAxis())
			.setValue(WATERLOGGED, level.getFluidState(pos)
				.getType() == Fluids.WATER);

		// The axis has to be settled before this loop runs: connectsTo reads it.
		for (Direction side : Direction.values()) {
			BlockPos neighbourPos = pos.relative(side);
			state = state.setValue(CONNECTIONS.get(side),
				connectsTo(level, state, side, level.getBlockState(neighbourPos), neighbourPos));
		}
		return state;
	}

	@Override
	protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
		LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
		if (state.getValue(WATERLOGGED))
			level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));

		return state.setValue(CONNECTIONS.get(direction),
			connectsTo(level, state, direction, neighbourState, neighbourPos));
	}

	// -------------------------------------------------------------------- state

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(AXIS, WATERLOGGED);
		CONNECTIONS.values()
			.forEach(builder::add);
		super.createBlockStateDefinition(builder);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		// Rotation#rotate leaves a vertical direction alone, which is what both uses of it here
		// want: a quarter turn swaps X for Z, and leaves an upright mast upright.
		BlockState rotated = state.setValue(AXIS, rotation.rotate(Direction.get(AxisDirection.POSITIVE,
			state.getValue(AXIS)))
			.getAxis());
		for (Direction side : Direction.values())
			rotated = rotated.setValue(CONNECTIONS.get(rotation.rotate(side)), state.getValue(CONNECTIONS.get(side)));
		return rotated;
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		// A mirror maps each axis onto itself, so only which side a connection is on changes; the
		// pole's own axis comes through untouched.
		BlockState mirrored = state;
		for (Direction side : Direction.values())
			mirrored = mirrored.setValue(CONNECTIONS.get(mirror.mirror(side)), state.getValue(CONNECTIONS.get(side)));
		return mirrored;
	}

	@Override
	protected FluidState getFluidState(BlockState state) {
		return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
	}

	@Override
	protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
		return false;
	}

	// ------------------------------------------------------------------ placing

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hitResult) {
		IPlacementHelper helper = PlacementHelpers.get(PLACEMENT_HELPER_ID);
		// Sneaking is the way out of the assist, and so also the way to place a pole against the
		// side of one already standing rather than on the end of its run.
		if (player != null && !player.isShiftKeyDown() && helper.matchesItem(stack))
			return helper.getOffset(player, level, state, pos, hitResult)
				.placeInWorld(level, (BlockItem) stack.getItem(), player, hand, hitResult);

		return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
	}

	/**
	 * A wrench turns the mast through the three axes, and sneak-wrenching takes it back into the
	 * inventory - both of those come from {@code IWrenchable}'s defaults. Turning matters because
	 * the clicked face cannot always reach the axis wanted: there is no face to click that stands
	 * a pole up in mid air.
	 *
	 * <p>The connections do not have to be recomputed here. {@code IWrenchable} runs the turned
	 * state back through {@link Block#updateFromNeighbourShapes} before placing it, and that calls
	 * {@link #updateShape} once per side.
	 */
	@Override
	public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
		Axis axis = switch (originalState.getValue(AXIS)) {
			case X -> Axis.Y;
			case Y -> Axis.Z;
			case Z -> Axis.X;
		};
		return originalState.setValue(AXIS, axis);
	}

	// ------------------------------------------------------------------- shapes

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES[index(state.getValue(AXIS), connectionMask(state))];
	}

	/**
	 * One shape per axis and per combination of connections, worked out once up front. There are
	 * only 192 of them, which is small enough that a lookup beats caching on demand.
	 *
	 * <p>The two cases below are the two cases in the blockstate file, and they have to stay in
	 * step or the pole will be clickable where it is not drawn.
	 */
	private static VoxelShape[] buildShapes() {
		VoxelShape[] shapes = new VoxelShape[Axis.values().length * 64];

		for (Axis axis : Axis.values()) {
			for (int mask = 0; mask < 64; mask++) {
				boolean branched = false;
				for (Direction side : Direction.values())
					if (side.getAxis() != axis && isConnected(mask, side))
						branched = true;

				VoxelShape shape;
				if (branched) {
					shape = HUB;
					for (Direction side : Direction.values())
						if (isConnected(mask, side))
							shape = Shapes.or(shape, arm(side));
				} else {
					shape = through(axis);
				}

				shapes[index(axis, mask)] = shape.optimize();
			}
		}
		return shapes;
	}

	/** The seven long stub from the hub out to one face. */
	private static VoxelShape arm(Direction side) {
		return switch (side) {
			case DOWN -> Block.box(7, 0, 7, 9, 7, 9);
			case UP -> Block.box(7, 9, 7, 9, 16, 9);
			case NORTH -> Block.box(7, 7, 0, 9, 9, 7);
			case SOUTH -> Block.box(7, 7, 9, 9, 9, 16);
			case WEST -> Block.box(0, 7, 7, 7, 9, 9);
			case EAST -> Block.box(9, 7, 7, 16, 9, 9);
		};
	}

	/** A whole block of mast, for a run with nothing branching across it. */
	private static VoxelShape through(Axis axis) {
		return switch (axis) {
			case X -> Block.box(0, 7, 7, 16, 9, 9);
			case Y -> Block.box(7, 0, 7, 9, 16, 9);
			case Z -> Block.box(7, 7, 0, 9, 9, 16);
		};
	}

	private static int index(Axis axis, int mask) {
		return axis.ordinal() * 64 + mask;
	}

	private static boolean isConnected(int mask, Direction side) {
		return (mask & 1 << side.get3DDataValue()) != 0;
	}

	private static int connectionMask(BlockState state) {
		int mask = 0;
		for (Direction side : Direction.values())
			if (state.getValue(CONNECTIONS.get(side)))
				mask |= 1 << side.get3DDataValue();
		return mask;
	}
}
