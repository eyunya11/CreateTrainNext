package com.eyunya.createtrainnext.signal;

import java.util.EnumMap;
import java.util.Map;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.pole.PoleConnectable;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
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

import net.neoforged.neoforge.common.Tags;

/**
 * A Japanese five-aspect signal head.
 *
 * <p>Placed the way Create's own signals are, because it is one: its item is a
 * {@code TrackTargetingBlockItem} built on {@code EdgePointType.SIGNAL}, so clicking a piece of
 * track and then a spot nearby creates a real {@code SignalBoundary}. Trains brake for it and
 * reserve blocks through it using Create's own code — see {@link MultiAspectSignalBlockEntity} for
 * why sharing Create's point type rather than inventing one is what makes that work.
 *
 * <p>The housing is a plain blockstate model. Only the five lamps are drawn by the block entity
 * renderer, since which of them are lit changes with traffic.
 */
public class MultiAspectSignalBlock extends Block
	implements IBE<MultiAspectSignalBlockEntity>, IWrenchable, PoleConnectable {

	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

	/**
	 * Degrees the housing model is turned by to face a given direction, in the sense a blockstate
	 * variant's {@code y} means. Zero for south because that is the way the model was drawn: its
	 * lamps sit at z=10, on the +z side of the panel.
	 *
	 * <p>The renderer measures the lamps from here too, so the lamps cannot drift off the housing.
	 */
	public static float modelYaw(BlockState state) {
		return state.getValue(FACING)
			.get2DDataValue() * 90F;
	}

	/**
	 * The housing is not symmetric front to back — it is deeper in front of the mast than behind —
	 * so each facing gets its own box rather than one being rotated by an axis test.
	 */
	private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

	static {
		// Drawn facing south: x 4.5..11.5, z 7..11.5. The rest follow by turning that clockwise
		// about the block's centre, matching the blockstate's own y rotations.
		SHAPES.put(Direction.SOUTH, Block.box(4.5, 0, 7, 11.5, 16, 11.5));
		SHAPES.put(Direction.WEST, Block.box(4.5, 0, 4.5, 9, 16, 11.5));
		SHAPES.put(Direction.NORTH, Block.box(4.5, 0, 4.5, 11.5, 16, 9));
		SHAPES.put(Direction.EAST, Block.box(7, 0, 4.5, 11.5, 16, 11.5));
	}

	public MultiAspectSignalBlock(Properties properties) {
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
	 * A wrench toggles 警戒, matching Create's own signal, where a wrench cycles entry and cross
	 * mode rather than turning the block.
	 *
	 * <p>It is a toggle rather than something worked out from traffic because it cannot be worked
	 * out from traffic: 警戒 exists where the block ahead is too short to stop in from 注意 speed,
	 * which is a fact about the layout that only the person who built it knows.
	 *
	 * <p>Taking the wrench over costs the rotation IWrenchable would otherwise give, which here
	 * only ever worked when clicking the top or bottom face anyway. Turning the head is on an empty
	 * hand instead, the same as this mod's track anchors.
	 */
	@Override
	public InteractionResult onWrenched(BlockState state, UseOnContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		if (level.isClientSide)
			return InteractionResult.SUCCESS;

		return onBlockEntityUse(level, pos, be -> {
			boolean restricted = !be.isRestricted();
			be.setRestricted(restricted);
			Player player = context.getPlayer();
			if (player != null)
				player.displayClientMessage(Component.translatable(restricted
					? "message.createtrainnext.signal.restricted_on"
					: "message.createtrainnext.signal.restricted_off"), true);
			return InteractionResult.SUCCESS;
		});
	}

	/**
	 * Lets a wrench through to {@link #onWrenched}.
	 *
	 * <p>Needed because {@link #useWithoutItem} below runs whether or not the player is holding
	 * anything: the game offers the block the item first, and a block that does not take it — which
	 * is what the inherited PASS_TO_DEFAULT_BLOCK_INTERACTION says — then gets its no-item
	 * interaction, and only if <em>that</em> also declines does the item finally get used. So the
	 * rotation was consuming the click and the wrench never reached its own useOn at all. SKIP
	 * rather than PASS is the difference: it declines without asking for the no-item interaction.
	 *
	 * <p>By the tag rather than by Create's own wrench, so a wrench from another mod behaves the
	 * same way here as it does on Create's blocks.
	 */
	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hit) {
		return stack.is(Tags.Items.TOOLS_WRENCH) ? ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION
			: ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
	}

	/** An empty hand turns the head a quarter turn, sneak to go back. */
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

	/**
	 * Poles join this from behind or from below — a head on the front of a mast, or on top of one.
	 * Never from the front, which is the side that has to stay clear to be read.
	 */
	@Override
	public boolean connectsToPole(BlockState state, Direction towardsPole) {
		return towardsPole == Direction.DOWN || towardsPole == state.getValue(FACING)
			.getOpposite();
	}

	@Override
	public Class<MultiAspectSignalBlockEntity> getBlockEntityClass() {
		return MultiAspectSignalBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends MultiAspectSignalBlockEntity> getBlockEntityType() {
		return CTNRegistry.MULTI_ASPECT_SIGNAL_ENTITY.get();
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		// Not super.onRemove: the block entity has to be told it is going, or the signal boundary it
		// put in the track graph outlives it and trains keep obeying a signal that is not there.
		IBE.onRemove(state, level, pos, newState);
	}
}
