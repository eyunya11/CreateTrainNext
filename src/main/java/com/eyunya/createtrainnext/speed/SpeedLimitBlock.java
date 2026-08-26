package com.eyunya.createtrainnext.speed;

import com.eyunya.createtrainnext.CTNRegistry;
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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A trackside sign capping how fast trains may run past the point it targets.
 *
 * <p>The block is placed the way Create's own signals and stations are: its item is a
 * {@code TrackTargetingBlockItem}, so the player clicks a piece of track to pick the point first
 * and then clicks somewhere nearby to put the sign down. Everything about which point was chosen
 * lives in {@link SpeedLimitBlockEntity}'s targeting behaviour, not in this block's state — the
 * sign does not have to touch the rail it governs, and {@link #ROTATION} is only which way it is
 * turned to be read.
 *
 * <p>Implementing {@code IBE} is what makes that placement work at all, and not just a
 * convenience: {@code TrackTargetingBlockItem} casts the block it is holding to it to find the
 * block entity type it should write the chosen point into.
 *
 * <p>The sign turns in 22.5 degree steps and is drawn by {@code SpeedLimitRenderer} rather than by
 * its blockstate, which is a choice rather than a necessity — see that class for why.
 */
public class SpeedLimitBlock extends Block implements IBE<SpeedLimitBlockEntity>, IWrenchable {

	/** Sixteen positions around the circle, one every 22.5 degrees. */
	public static final int SEGMENTS = 16;

	/** Deliberately floating point: 360 over 16 is 22.5, and integer division would make it 22. */
	public static final float DEGREES_PER_SEGMENT = 360F / SEGMENTS;

	/**
	 * Which of the sixteen the sign is turned to. Segment 0 is the sign facing north — the model as
	 * authored, undrawn and unrotated — and segments run clockwise seen from above, so 4 is east.
	 *
	 * <p>Sixteen segments is what {@link net.minecraft.world.level.block.state.properties.BlockStateProperties#ROTATION_16}
	 * holds too, and this could have used it. It does not, so that {@link #SEGMENTS} stays the one
	 * place the granularity is written down — it has already changed once — and because vanilla's
	 * property comes with the convention that segment 0 points south, which is not the one here.
	 */
	public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, SEGMENTS - 1);

	private static final VoxelShape[] SHAPES = buildShapes();

	public SpeedLimitBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(ROTATION, 0));
	}

	// ------------------------------------------------------------------ angles

	/**
	 * Degrees to turn the model by to reach the given state, in the sense a blockstate variant's
	 * {@code y} means: clockwise seen from above.
	 *
	 * <p>Both the renderer and the value box measure from here rather than working it out
	 * themselves, so the number on the sign cannot drift away from the sign it is drawn on.
	 */
	public static float modelYaw(BlockState state) {
		return state.getValue(ROTATION) * DEGREES_PER_SEGMENT;
	}

	/** The direction the sign's face points, as a horizontal unit vector. */
	public static Vec3 facing(BlockState state) {
		double radians = Math.toRadians(modelYaw(state));
		// Segment 0 is north, so this is (0, 0, -1) spun clockwise by the yaw.
		return new Vec3(Math.sin(radians), 0, -Math.cos(radians));
	}

	// ------------------------------------------------------------------- state

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder.add(ROTATION));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		// Segment 0 faces north and segments run clockwise, which is also how Minecraft measures a
		// player's yaw from due south — so the player's own yaw, rounded, is already the segment
		// that turns the sign back to face them.
		int segment = Math.floorMod(Math.round(context.getRotation() / DEGREES_PER_SEGMENT), SEGMENTS);
		return defaultBlockState().setValue(ROTATION, segment);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		int quarter = SEGMENTS / 4;
		int segments = switch (rotation) {
			case CLOCKWISE_90 -> quarter;
			case CLOCKWISE_180 -> quarter * 2;
			case COUNTERCLOCKWISE_90 -> quarter * 3;
			default -> 0;
		};
		return state.setValue(ROTATION, (state.getValue(ROTATION) + segments) % SEGMENTS);
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		// Spelled out rather than deferred to Mirror#mirror(int, int), whose arithmetic assumes
		// vanilla's convention of segment 0 pointing south. Segment 0 points north here, and with
		// sixteen segments that helper looks applicable — it is not, and would mirror about the
		// wrong axis.
		int segment = state.getValue(ROTATION);
		int mirrored = switch (mirror) {
			// Flips the z axis: north and south swap, east and west stay put.
			case LEFT_RIGHT -> SEGMENTS / 2 - segment;
			// Flips the x axis, so it is north and south that stay put.
			case FRONT_BACK -> -segment;
			default -> segment;
		};
		return state.setValue(ROTATION, Math.floorMod(mirrored, SEGMENTS));
	}

	/**
	 * A wrench nudges the sign one segment on, rather than the quarter turn Create's default gives.
	 * Fine turning is the point of the block having sixteen positions, and a wrench is the only way
	 * to reach the ones a player cannot stand at.
	 */
	@Override
	public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
		return originalState.setValue(ROTATION, (originalState.getValue(ROTATION) + 1) % SEGMENTS);
	}

	// ------------------------------------------------------------------ shapes

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES[state.getValue(ROTATION)];
	}

	/**
	 * One shape per segment, each the smallest axis-aligned box holding the turned sign.
	 *
	 * <p>An approximation by necessity — a voxel shape is axis aligned and a turned sign is not — so
	 * at the angles between the axes the box is a little wider than the sign inside it. Bounding it
	 * is the right way to be wrong: the sign stays clickable everywhere it is visible, which the
	 * inscribed box would not manage.
	 */
	private static VoxelShape[] buildShapes() {
		VoxelShape[] shapes = new VoxelShape[SEGMENTS];
		for (int segment = 0; segment < SEGMENTS; segment++) {
			double radians = Math.toRadians(segment * (double) DEGREES_PER_SEGMENT);
			double cos = Math.abs(Math.cos(radians));
			double sin = Math.abs(Math.sin(radians));
			shapes[segment] = Shapes.or(spun(1, 1, cos, sin, 0, 5), spun(5.5, 1, cos, sin, 5, 14));
		}
		return shapes;
	}

	/** Bounds a block-centred box of the given half extents after spinning it about the y axis. */
	private static VoxelShape spun(double halfX, double halfZ, double cos, double sin, double minY, double maxY) {
		double x = halfX * cos + halfZ * sin;
		double z = halfX * sin + halfZ * cos;
		return Block.box(8 - x, minY, 8 - z, 8 + x, maxY, 8 + z);
	}

	// --------------------------------------------------------------------- IBE

	@Override
	public Class<SpeedLimitBlockEntity> getBlockEntityClass() {
		return SpeedLimitBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends SpeedLimitBlockEntity> getBlockEntityType() {
		return CTNRegistry.TRACK_SPEED_LIMIT_ENTITY.get();
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		// Not super.onRemove: a SmartBlockEntity has to be told it is going away before it is
		// dropped, or the point it put in the track graph is left behind with nothing pointing at
		// it and trains keep obeying a sign that is no longer there.
		IBE.onRemove(state, level, pos, newState);
	}
}
