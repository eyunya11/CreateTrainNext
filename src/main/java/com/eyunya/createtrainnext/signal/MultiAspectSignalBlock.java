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
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A Japanese multi-aspect signal head, of any of the types in {@link SignalType}: two, three, four, five
 * or six lamps. Each type is a block of its own, and the lamps and what they say are the type's.
 *
 * <p>Placed the way Create's own signals are, because it is one: its item is a
 * {@code TrackTargetingBlockItem} built on {@code EdgePointType.SIGNAL}, so clicking a piece of
 * track and then a spot nearby creates a real {@code SignalBoundary}. Trains brake for it and
 * reserve blocks through it using Create's own code — see {@link MultiAspectSignalBlockEntity} for
 * why sharing Create's point type rather than inventing one is what makes that work.
 *
 * <p>The head turns in 22.5 degree steps, which is why only the mast is left in the blockstate
 * model: a blockstate turns in quarters, so the head and its lamps are drawn by the block
 * entity renderer instead. See {@link SignalRotation} for why there is no way around that.
 *
 * <p>Which way a head points is settled once, when it is placed, from the direction the player was
 * facing. There is no way to turn a placed one: the wrench and a right click do nothing.
 */
public class MultiAspectSignalBlock extends Block
	implements IBE<MultiAspectSignalBlockEntity>, IWrenchable, PoleConnectable {

	/** Sixteen angles, 22.5 degrees apart. See {@link SignalRotation} for the whole of the arithmetic. */
	public static final IntegerProperty ROTATION = SignalRotation.ROTATION;

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

		/**
		 * How far the housing model was shifted along its own local x, south-authored, in blocks.
		 * The lamps need the same number so they land on the housing rather than beside it.
		 */
		public float localX() {
			return localX / 16F;
		}

		/** The name of the model of the turning half of the housing for this offset, in a type's folder. */
		public String headModelName() {
			return switch (this) {
				case CENTER -> "housing_head";
				case LEFT -> "housing_left_head";
				case RIGHT -> "housing_right_head";
			};
		}

		/** The name of the model of the mast for this offset, in a type's folder. */
		public String postModelName() {
			return switch (this) {
				case CENTER -> "housing_post";
				case LEFT -> "housing_left_post";
				case RIGHT -> "housing_right_post";
			};
		}
	}

	public static final EnumProperty<HorizontalOffset> OFFSET =
		EnumProperty.create("offset", HorizontalOffset.class);

	/**
	 * Degrees the head is turned by, in the sense a blockstate variant's {@code y} means. Zero for
	 * south because that is the way the model was drawn: its lamps sit at z=10, on the +z side of
	 * the panel.
	 *
	 * <p>The renderer measures both the housing and the lamps from here, so they cannot drift apart.
	 */
	public static float modelYaw(BlockState state) {
		return SignalRotation.modelYaw(state);
	}

	/**
	 * The bounds of each offset's head as drawn, unturned, read off housing.json, housing_left.json
	 * and housing_right.json with their element rotations applied.
	 *
	 * <p>The mast is not in here. It never turns, so it is the same box in every shape and is added
	 * to all of them unchanged.
	 */
	private static final Map<HorizontalOffset, double[]> HEAD_BOUNDS = new EnumMap<>(HorizontalOffset.class);

	/**
	 * The mast: the central pole, and the collars the offset housings wrap around it, which is what
	 * makes this a hair wider than the two pixel post on its own.
	 */
	private static final VoxelShape MAST = Block.box(6.9, 0, 6.9, 9.1, 16, 9.1);

	/** One shape per offset per segment: the mast, plus that offset's head turned to match. */
	private static final Map<HorizontalOffset, VoxelShape[]> SHAPES = new EnumMap<>(HorizontalOffset.class);

	static {
		HEAD_BOUNDS.put(HorizontalOffset.CENTER, new double[] { 4.5, 0, 9, 11.5, 16, 11.5 });
		// The offset heads hang into the block next door on purpose, so these reach past 0 and 16.
		// SignalRotation.spin clamps what comes out, which is the same approximation the twelve
		// hardcoded boxes here used to be.
		HEAD_BOUNDS.put(HorizontalOffset.LEFT, new double[] { -2, 0, 6.9, 7, 16, 11.5 });
		HEAD_BOUNDS.put(HorizontalOffset.RIGHT, new double[] { 9, 0, 6.9, 18, 16, 11.5 });

		for (HorizontalOffset offset : HorizontalOffset.values()) {
			double[] bounds = HEAD_BOUNDS.get(offset);
			VoxelShape[] shapes = new VoxelShape[SignalRotation.SEGMENTS];
			for (int segment = 0; segment < SignalRotation.SEGMENTS; segment++)
				shapes[segment] = Shapes.or(MAST, SignalRotation.spin(bounds[0], bounds[1], bounds[2],
					bounds[3], bounds[4], bounds[5], segment * SignalRotation.DEGREES_PER_SEGMENT));
			SHAPES.put(offset, shapes);
		}
	}

	private final SignalType signalType;

	public MultiAspectSignalBlock(Properties properties, SignalType signalType) {
		super(properties);
		this.signalType = signalType;
		registerDefaultState(defaultBlockState().setValue(ROTATION, 0)
			.setValue(OFFSET, HorizontalOffset.CENTER));
	}

	/** Which head this is: how many lamps it has and what they say. */
	public SignalType signalType() {
		return signalType;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder.add(ROTATION, OFFSET));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(ROTATION, SignalRotation.segmentForPlacement(context));
		// OFFSET keeps its default of CENTER: placement has no face to read a side from, and this
		// mod's TrackTargetingBlockItem places on a right click chosen for the target track, not for
		// where across the block the housing should sit. See the value box for how OFFSET changes.
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
		return SHAPES.get(state.getValue(OFFSET))[state.getValue(ROTATION)];
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
		// Rounded to the nearest quarter, because a pole can only arrive from one of four sides
		// however finely the head itself is turned.
		Direction facing = SignalRotation.nearestFacing(state);
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
