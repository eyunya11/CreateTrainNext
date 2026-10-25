package com.eyunya.createtrainnext.signal;

import java.util.EnumMap;
import java.util.Map;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.pole.PoleConnectable;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
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
 * A plate on a signal's pole: the number under a 閉そく signal's head or the crown over a 場内 or 出発
 * signal's. See {@link PlateKind} for why there are plates at all.
 *
 * <p>It is a length of pole with the plate on it, so it takes the place of a {@code SignalPoleBlock}
 * under or over a signal. The pole runs up the middle of the block whichever way the plate is turned, and
 * is the blockstate's model; the plate turns in 22.5 degree steps like the heads and is drawn by the
 * block entity renderer, for the reason given in {@link SignalRotation}.
 *
 * <p>A plate is turned the way the signal it is put on is, so it reads from where the signal is read: one
 * placed straight above or below a signal, a relay or another plate takes its turn, and one placed
 * anywhere else faces whoever placed it, as a signal does. Poles join it from above and below, so a signal
 * under a crown runs its mast up to it ({@link MultiAspectSignalBlock#UP}).
 *
 * <p>What it says is right clicked in with an empty hand, and kept by {@link SignalPlateBlockEntity}.
 */
public class SignalPlateBlock extends Block implements IBE<SignalPlateBlockEntity>, IWrenchable, PoleConnectable {

	public static final IntegerProperty ROTATION = SignalRotation.ROTATION;

	/** The pole through the middle of the block: the same box as a signal's mast. */
	private static final VoxelShape POLE = Block.box(7, 0, 7, 9, 16, 9);

	private static final Map<PlateKind, VoxelShape[]> SHAPES = new EnumMap<>(PlateKind.class);

	static {
		for (PlateKind kind : PlateKind.values()) {
			double[] b = kind.plateBounds();
			VoxelShape[] shapes = new VoxelShape[SignalRotation.SEGMENTS];
			for (int segment = 0; segment < SignalRotation.SEGMENTS; segment++)
				shapes[segment] = Shapes.or(POLE,
					SignalRotation.spin(b[0], b[1], b[2], b[3], b[4], b[5], segment * SignalRotation.DEGREES_PER_SEGMENT));
			SHAPES.put(kind, shapes);
		}
	}

	private final PlateKind kind;

	public SignalPlateBlock(Properties properties, PlateKind kind) {
		super(properties);
		this.kind = kind;
		registerDefaultState(defaultBlockState().setValue(ROTATION, 0));
	}

	/** Which plate this is. */
	public PlateKind kind() {
		return kind;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder.add(ROTATION));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockPos pos = context.getClickedPos();
		Integer turned = turnOf(context.getLevel()
			.getBlockState(pos.above()));
		if (turned == null)
			turned = turnOf(context.getLevel()
				.getBlockState(pos.below()));
		return defaultBlockState().setValue(ROTATION,
			turned != null ? turned : SignalRotation.segmentForPlacement(context));
	}

	/**
	 * How a block a plate is put against is turned, if it is one that turns as the signals do: a head, a
	 * relay or another plate, which all have {@link SignalRotation#ROTATION}.
	 *
	 * <p>A state is asked for a property by the object, not by its name, which is what keeps the speed limit
	 * sign out: its rotation has the same name and range, measured from north, and is an object of its own
	 * so that it cannot be mistaken for this one.
	 */
	@Nullable
	private static Integer turnOf(BlockState state) {
		return state.hasProperty(SignalRotation.ROTATION) ? state.getValue(SignalRotation.ROTATION) : null;
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
		return SHAPES.get(kind)[state.getValue(ROTATION)];
	}

	/** Above and below only: the pole runs up and down, and the plate is in the way of the other sides. */
	@Override
	public boolean connectsToPole(BlockState state, Direction towardsPole) {
		return towardsPole == Direction.UP || towardsPole == Direction.DOWN;
	}

	@Override
	public Class<SignalPlateBlockEntity> getBlockEntityClass() {
		return SignalPlateBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends SignalPlateBlockEntity> getBlockEntityType() {
		return CTNRegistry.SIGNAL_PLATE_ENTITY.get();
	}
}
