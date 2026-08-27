package com.eyunya.createtrainnext.signal;

import java.util.EnumMap;
import java.util.Locale;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.util.StringRepresentable;
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
	 * Where the housing sits across the width of its own block, so a pole standing in the middle of
	 * a multi-track bed can carry heads offset to either side of it rather than only ever centred.
	 */
	public enum HorizontalOffset implements StringRepresentable {
		// CENTER first and so ordinal 0: TrackTargetingBlockItem writes block entity NBT at
		// placement (the targeted track), which makes a fresh block's ScrollValueBehaviour run its
		// read() with no "ScrollValue" key present, and that reads back as 0. Ordinal 0 being the
		// blockstate's own default turns that into a no-op instead of a bug to work around.
		//
		// LOCAL_X is where the lamp column sits in housing_left.json/housing_right.json, in the
		// same south-authored local space the model is built in, and MultiAspectSignalRenderer
		// shifts the lamps by the same amount before applying the same rotation, so the two cannot
		// drift apart the way two independently-chosen numbers could.
		//
		// Read off the "light_base" element in each model (its backing board, spanning the full
		// lamp column) rather than assumed: housing_left/housing_right are no longer a uniform shift
		// of housing.json — they carry their own pole and support geometry now — so there is no
		// single delta to reapply by formula the way there was when this was still 3.5 either way.
		// If light_base moves again, read its new centre the same way rather than guessing from the
		// visible geometry around it.
		CENTER(0), LEFT(-6.5F), RIGHT(6.5F);

		private final float localX;

		HorizontalOffset(float localX) {
			this.localX = localX;
		}

		@Override
		public String getSerializedName() {
			return name().toLowerCase(Locale.ROOT);
		}

		/** Which baked model this offset uses, alongside {@code housing.json} for {@link #CENTER}. */
		public String modelPath() {
			return this == CENTER ? "housing" : "housing_" + getSerializedName();
		}

		/**
		 * How far the housing model was shifted along its own local x, south-authored, in blocks.
		 * The lamps need the same number so they land on the housing rather than beside it.
		 */
		public float localX() {
			return localX / 16F;
		}
	}

	public static final EnumProperty<HorizontalOffset> OFFSET =
		EnumProperty.create("offset", HorizontalOffset.class);

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
	 * The housing is not symmetric front to back, nor (away from {@link HorizontalOffset#CENTER})
	 * left to right, so every facing x offset combination gets its own box rather than one being
	 * rotated by an axis test. All twelve are the same rotation applied to the housing model's own
	 * bounds, worked out once and hardcoded rather than computed on every shape query.
	 */
	private static final Map<Direction, Map<HorizontalOffset, VoxelShape>> SHAPES = new EnumMap<>(Direction.class);

	static {
		// Drawn facing south, CENTER: x 4.5..11.5, z 7..11.5. LEFT and RIGHT are that shifted by
		// the same 3.5 the housing_left/housing_right models were, and every other facing follows
		// by turning the SOUTH box clockwise about the block's centre per 90 degrees of FACING, the
		// same rotation the blockstate's own 'y' applies to the model.
		put(Direction.SOUTH, HorizontalOffset.LEFT, Block.box(1, 0, 7, 8, 16, 11.5));
		put(Direction.SOUTH, HorizontalOffset.CENTER, Block.box(4.5, 0, 7, 11.5, 16, 11.5));
		put(Direction.SOUTH, HorizontalOffset.RIGHT, Block.box(8, 0, 7, 15, 16, 11.5));

		put(Direction.WEST, HorizontalOffset.LEFT, Block.box(4.5, 0, 1, 9, 16, 8));
		put(Direction.WEST, HorizontalOffset.CENTER, Block.box(4.5, 0, 4.5, 9, 16, 11.5));
		put(Direction.WEST, HorizontalOffset.RIGHT, Block.box(4.5, 0, 8, 9, 16, 15));

		put(Direction.NORTH, HorizontalOffset.LEFT, Block.box(8, 0, 4.5, 15, 16, 9));
		put(Direction.NORTH, HorizontalOffset.CENTER, Block.box(4.5, 0, 4.5, 11.5, 16, 9));
		put(Direction.NORTH, HorizontalOffset.RIGHT, Block.box(1, 0, 4.5, 8, 16, 9));

		put(Direction.EAST, HorizontalOffset.LEFT, Block.box(7, 0, 8, 11.5, 16, 15));
		put(Direction.EAST, HorizontalOffset.CENTER, Block.box(7, 0, 4.5, 11.5, 16, 11.5));
		put(Direction.EAST, HorizontalOffset.RIGHT, Block.box(7, 0, 1, 11.5, 16, 8));
	}

	private static void put(Direction facing, HorizontalOffset offset, VoxelShape shape) {
		SHAPES.computeIfAbsent(facing, $ -> new EnumMap<>(HorizontalOffset.class))
			.put(offset, shape);
	}

	public MultiAspectSignalBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH)
			.setValue(OFFSET, HorizontalOffset.CENTER));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder.add(FACING, OFFSET));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection()
			.getOpposite());
		// OFFSET keeps its default of CENTER: placement has no face to read a side from, and this
		// mod's TrackTargetingBlockItem places on a right click chosen for the target track, not for
		// where across the block the housing should sit. See the value box for how OFFSET changes.
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
		return SHAPES.get(state.getValue(FACING))
			.get(state.getValue(OFFSET));
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
	 * Poles join this from behind, from below, or from either side — never from the front, which is
	 * the side that has to stay clear to be read.
	 *
	 * <p>The sides matter once {@link #OFFSET} is anything but {@link HorizontalOffset#CENTER}: a
	 * head pushed to one edge of its block is meant to be reached by a horizontal arm running in
	 * from that side, off a mast planted further along the gantry rather than directly behind the
	 * head. A centred head has no reason to prefer a side over the back, so it accepts both; nothing
	 * here forces the arm to originate from whichever side the offset leans toward.
	 */
	@Override
	public boolean connectsToPole(BlockState state, Direction towardsPole) {
		if (towardsPole == Direction.DOWN)
			return true;
		Direction facing = state.getValue(FACING);
		return towardsPole == facing.getOpposite() || towardsPole == facing.getClockWise()
			|| towardsPole == facing.getCounterClockWise();
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
