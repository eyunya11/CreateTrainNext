package com.eyunya.createtrainnext.track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.AllShapes;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.BezierTrackPointLocation;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackRenderer;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour.RenderedTrackOverlayType;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.transform.Affine;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One end of a free-angle curve, and a real Create track block.
 *
 * <p>The anchor carries an ordinary one-block straight stub along {@link #AXIS}, which
 * keeps its graph nodes on the half-block lattice {@code TrackNodeLocation} rounds everything to
 * and lets it dock with Create's own track. The free angle lives in the curve's <em>tangent</em>,
 * which nothing downstream quantises — not the graph, not NBT.
 *
 * <p>That split has a cost worth stating plainly: the stub can only lie on a discrete axis while
 * the curve leaves at whatever angle was chosen, so the two meet at a corner of up to 45 degrees.
 * {@code alignStubToYaw} picks the nearer axis to keep it to that bound, and it falls to zero when
 * the yaw is axis-aligned. Removing the stub does not fix this — {@code TrackPropagator} seeds its
 * search with a linear-only query and crashes on a block that has no straight geometry — so a
 * corner where a lattice-quantised graph meets a free angle is inherent, not a defect to chase.
 *
 * <p>Controls:
 * <ul>
 * <li>Right click with an empty hand turns the anchor by 15 degrees (sneak to go back).</li>
 * <li>Right click with a stick starts a link, then completes it on a second anchor.
 * Using a stick on an anchor that already has a link clears it.</li>
 * </ul>
 */
public class AngledTrackAnchorBlock extends Block implements EntityBlock, ITrackBlock {

	public static final EnumProperty<AnchorAxis> AXIS = EnumProperty.create("axis", AnchorAxis.class);

	private static final float STEP_DEGREES = 15.0F;

	/** Server-side scratch state: which anchor each player picked first. */
	private static final Map<UUID, BlockPos> PENDING_LINKS = new HashMap<>();

	public AngledTrackAnchorBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(AXIS, AnchorAxis.Z_ORTHO));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(AXIS);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(AXIS,
			AnchorAxis.nearestTo(Vec3.atLowerCornerOf(context.getHorizontalDirection()
				.getNormal())));
	}

	@Override
	@Nullable
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new AngledTrackAnchorBlockEntity(pos, state);
	}

	/** Which pair of block boundaries this anchor's node may sit on. Discrete, to stay on the lattice. */
	public static Vec3 axisVector(BlockState state) {
		return state.getValue(AXIS).axis();
	}

	// ------------------------------------------------------------------ shape

	// Reuse Create's own track shapes rather than approximating them, so an anchor selects and
	// walks exactly like the track it sits in line with.

	private VoxelShape outlineShape(BlockState state) {
		return switch (state.getValue(AXIS)) {
			case X_ORTHO -> AllShapes.TRACK_ORTHO.get(Direction.EAST);
			case Z_ORTHO -> AllShapes.TRACK_ORTHO.get(Direction.SOUTH);
			case DIAG -> AllShapes.TRACK_DIAG.get(Direction.EAST);
			case DIAG_2 -> AllShapes.TRACK_DIAG.get(Direction.SOUTH);
		};
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return outlineShape(state);
	}

	@Override
	protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
		return outlineShape(state);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
		CollisionContext context) {
		return AllShapes.TRACK_COLLISION;
	}

	// -------------------------------------------------------------- lifecycle

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
		super.onPlace(state, level, pos, oldState, isMoving);
		// Create builds the graph from a scheduled tick rather than here, so that neighbouring
		// track has settled by the time the propagator walks it. Same reasoning applies to us.
		if (!level.isClientSide)
			level.scheduleTick(pos, this, 1);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		TrackPropagator.onRailAdded(level, pos, state);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		if (!state.is(newState.getBlock())) {
			if (!level.isClientSide && level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor)
				anchor.unlinkPartner();
			TrackPropagator.onRailRemoved(level, pos, state);
		}
		super.onRemove(state, level, pos, newState, isMoving);
	}

	// ------------------------------------------------------------- ITrackBlock

	@Override
	public Vec3 getUpNormal(BlockGetter world, BlockPos pos, BlockState state) {
		return new Vec3(0, 1, 0);
	}

	@Override
	public List<Vec3> getTrackAxes(BlockGetter world, BlockPos pos, BlockState state) {
		return ImmutableList.of(axisVector(state));
	}

	@Override
	public Vec3 getCurveStart(BlockGetter world, BlockPos pos, BlockState state, Vec3 axis) {
		return Vec3.atBottomCenterOf(pos)
			.add(axis.scale(0.5));
	}

	@Override
	public BlockState getBogeyAnchor(BlockGetter world, BlockPos pos, BlockState state) {
		return AllBlocks.SMALL_BOGEY.getDefaultState()
			.setValue(BlockStateProperties.HORIZONTAL_AXIS,
				Math.abs(axisVector(state).x) > Math.abs(axisVector(state).z) ? Direction.Axis.X : Direction.Axis.Z);
	}

	@Override
	public boolean trackEquals(BlockState state1, BlockState state2) {
		return state1.getBlock() == this && state2.getBlock() == this
			&& state1.getValue(AXIS) == state2.getValue(AXIS);
	}

	@Override
	public TrackMaterial getMaterial() {
		return TrackMaterial.ANDESITE;
	}

	/**
	 * Reports only the free-angle curve, as a {@code viaTurn} connection.
	 *
	 * <p>Note what is <em>not</em> here: a straight edge across the anchor's own block. An earlier
	 * version reported one, so the anchor behaved like a one-block piece of track that a curve
	 * happened to leave from. But that stub can only lie on a discrete axis — it has to, to keep
	 * its nodes on the half-block lattice — while the curve leaves at whatever angle the player
	 * chose, so the two met at an angle of up to 45 degrees. The graph was right and the curve was
	 * right; the bend between them was what read as a train running beside its rails.
	 *
	 * <p>With one node per anchor there is nothing for the curve to disagree with. Docking to
	 * Create's own track still works: that node sits on the block boundary, which is a lattice
	 * point an adjacent Create track reports too, so the two merge into one node as before.
	 */
	@Override
	public Collection<DiscoveredLocation> getConnected(BlockGetter worldIn, BlockPos pos, BlockState state,
		boolean linear, @Nullable TrackNodeLocation connectedTo) {

		BlockGetter world = connectedTo != null && worldIn instanceof ServerLevel sl ? sl.getServer()
			.getLevel(connectedTo.dimension) : worldIn;
		if (world == null)
			return List.of();

		List<DiscoveredLocation> list = new ArrayList<>();

		// Deliberately not ITrackBlock.super.getConnected: the interface's default reads
		// state.getValue(TrackBlock.SHAPE) to obtain the up normal, so it only works on blocks
		// carrying Create's own SHAPE property and throws on anyone else's. This is the same
		// logic with getUpNormal() substituted for that lookup.
		Vec3 center = Vec3.atBottomCenterOf(pos)
			.add(0, getElevationAtCenter(world, pos, state), 0);

		for (Vec3 axis : getTrackAxes(world, pos, state)) {
			BiFunction<Double, Boolean, Vec3> offsetFactory = (d, b) -> axis.scale(b ? d : -d)
				.add(center);
			Function<Boolean, ResourceKey<Level>> dimensionFactory =
				b -> world instanceof Level l ? l.dimension() : Level.OVERWORLD;
			Function<Vec3, Integer> yOffsetFactory = v -> getYOffsetAt(world, pos, state, v);

			ITrackBlock.addToListIfConnected(connectedTo, list, offsetFactory,
				b -> getUpNormal(world, pos, state), dimensionFactory, yOffsetFactory, axis, null,
				(b, v) -> ITrackBlock.getMaterialSimple(world, v, getMaterial()));
		}

		// onRailAdded seeds its search with a linear-only query, and dereferences the start node
		// it expects that to have found. Reporting nothing here leaves it null and crashes, so
		// the stub is not optional: the propagator requires every track block to have straight
		// geometry of its own.
		if (linear)
			return list;

		if (!(world.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor))
			return list;

		BezierConnection bc = anchor.buildCurve();
		if (bc == null)
			return list;

		ITrackBlock.addToListIfConnected(connectedTo, list,
			(d, b) -> d == 1 ? Vec3.atLowerCornerOf(bc.bePositions.get(b)) : bc.starts.get(b), bc.normals::get,
			b -> world instanceof Level l ? l.dimension() : Level.OVERWORLD, bc::yOffsetAt, null, bc,
			(b, v) -> getMaterial());

		return list;
	}

	@Override
	public <Self extends Affine<Self>> PartialModel prepareTrackOverlay(Affine<Self> affine, BlockGetter world,
		BlockPos pos, BlockState state, BezierTrackPointLocation bezierPoint, AxisDirection direction,
		RenderedTrackOverlayType type) {

		Vec3 axis = axisVector(state);
		Vec3 diff = axis.scale(direction.getStep())
			.normalize();
		Vec3 angles = TrackRenderer.getModelAngles(getUpNormal(world, pos, state), diff);

		affine.center()
			.rotateY((float) angles.y)
			.rotateX((float) angles.x)
			.uncenter();

		return switch (type) {
			case DUAL_SIGNAL -> AllPartialModels.TRACK_SIGNAL_DUAL_OVERLAY;
			case OBSERVER -> AllPartialModels.TRACK_OBSERVER_OVERLAY;
			case SIGNAL -> AllPartialModels.TRACK_SIGNAL_OVERLAY;
			case STATION -> AllPartialModels.TRACK_STATION_OVERLAY;
		};
	}

	@Override
	public PartialModel prepareAssemblyOverlay(BlockGetter world, BlockPos pos, BlockState state, Direction direction,
		PoseStack ms) {
		TransformStack.of(ms)
			.rotateCentered(AngleHelper.rad(AngleHelper.horizontalAngle(direction)), Direction.UP);
		return AllPartialModels.TRACK_ASSEMBLING_OVERLAY;
	}

	// ----------------------------------------------------------- interaction

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
		BlockHitResult hit) {
		if (!(level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor))
			return InteractionResult.PASS;
		if (level.isClientSide)
			return InteractionResult.SUCCESS;

		anchor.rotateYaw(player.isShiftKeyDown() ? -STEP_DEGREES : STEP_DEGREES);
		player.displayClientMessage(
			Component.translatable("message.createtrainnext.angle", String.format("%.1f", anchor.getYaw())), true);
		return InteractionResult.CONSUME;
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hit) {
		if (!stack.is(Items.STICK))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!(level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (level.isClientSide)
			return ItemInteractionResult.SUCCESS;

		UUID playerId = player.getUUID();

		if (anchor.getPartner() != null) {
			anchor.unlinkPartner();
			PENDING_LINKS.remove(playerId);
			player.displayClientMessage(Component.translatable("message.createtrainnext.link_cleared"), true);
			return ItemInteractionResult.CONSUME;
		}

		BlockPos pending = PENDING_LINKS.get(playerId);
		boolean pendingIsUsable = pending != null && !pending.equals(pos)
			&& level.getBlockEntity(pending) instanceof AngledTrackAnchorBlockEntity;

		if (!pendingIsUsable) {
			PENDING_LINKS.put(playerId, pos);
			player.displayClientMessage(Component.translatable("message.createtrainnext.link_started"), true);
			return ItemInteractionResult.CONSUME;
		}

		((AngledTrackAnchorBlockEntity) level.getBlockEntity(pending)).linkTo(anchor);
		PENDING_LINKS.remove(playerId);
		player.displayClientMessage(Component.translatable("message.createtrainnext.link_done"), true);
		return ItemInteractionResult.CONSUME;
	}
}
