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
import net.createmod.catnip.data.Couple;
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
 * <p>{@link #AXIS} says which pair of block boundaries this anchor's two graph nodes sit on. It is
 * limited to the four directions that land on the half-block lattice {@code TrackNodeLocation}
 * rounds every node to, and it follows the heading as closely as those four allow. The heading
 * itself is free: nothing downstream quantises a tangent, not the graph and not NBT.
 *
 * <p>Anchors chain, and the chain is what makes the geometry work — see
 * {@link AngledTrackAnchorBlockEntity}. Joints in the middle of a chain are smooth because the
 * headings either side of them are the same vector; only the two ends of a chain reconcile a free
 * angle with a discrete axis, and the anchor's own span bends across its block to do it.
 *
 * <p>Controls:
 * <ul>
 * <li>Right click with an empty hand turns the anchor by 15 degrees (sneak to go back).</li>
 * <li>Right click with a stick selects an anchor; right clicking a second one links the two and
 * ends the selection. To carry a route on, click the anchor just reached to select it again.
 * Clicking the anchor already selected clears the selection instead.</li>
 * <li>Breaking an anchor is what undoes its links.</li>
 * </ul>
 */
public class AngledTrackAnchorBlock extends Block implements EntityBlock, ITrackBlock {

	public static final EnumProperty<AnchorAxis> AXIS = EnumProperty.create("axis", AnchorAxis.class);

	private static final float STEP_DEGREES = 15.0F;

	/** Server-side scratch state: which anchor each player picked first. */
	/**
	 * The anchor each player has selected with a stick, if any.
	 *
	 * <p>The dimension is held alongside the position rather than the position alone. A bare
	 * BlockPos is resolved against whatever level the next click happens in, so a selection made in
	 * one dimension would happily link to whatever anchor sat at the same coordinates in another.
	 */
	private record PendingLink(ResourceKey<Level> dimension, BlockPos pos) {}

	private static final Map<UUID, PendingLink> PENDING_LINKS = new HashMap<>();

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
				anchor.unlink();
			TrackPropagator.onRailRemoved(level, pos, state);
		}
		super.onRemove(state, level, pos, newState, isMoving);
	}

	// ------------------------------------------------------------- ITrackBlock

	@Override
	public Vec3 getUpNormal(BlockGetter world, BlockPos pos, BlockState state) {
		return new Vec3(0, 1, 0);
	}

	/**
	 * The heading, not the block's discrete axis.
	 *
	 * <p>This is what station and signal placement asks for: {@code getNearestTrackAxis} picks from
	 * here, and {@code TrackGraphHelper.getGraphLocationAt} then requires the chosen axis to match
	 * the direction of an edge leaving the node to within about a degree. The edges leaving an
	 * anchor follow its heading, so reporting the discrete axis instead only agreed when the two
	 * happened to coincide — at multiples of 45 degrees — and placement was refused everywhere else.
	 *
	 * <p>Safe to report a free vector here because nothing this mod puts on the lattice comes
	 * through it: node positions are taken from the block's axis directly. The one caveat is
	 * Create's own track wand, which builds a curve start from whatever this returns, and would
	 * get an off-lattice point from an anchor pointed between axes.
	 */
	@Override
	public List<Vec3> getTrackAxes(BlockGetter world, BlockPos pos, BlockState state) {
		if (world.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor)
			return ImmutableList.of(anchor.targetingAxis());
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

		AngledTrackAnchorBlockEntity anchor =
			world.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity a ? a : null;
		BezierConnection span = linear || anchor == null ? null : anchor.buildSpan();

		Function<Boolean, ResourceKey<Level>> dimensionFactory =
			b -> world instanceof Level l ? l.dimension() : Level.OVERWORLD;
		Function<Vec3, Integer> yOffsetFactory = v -> getYOffsetAt(world, pos, state, v);

		if (span != null) {
			// The block's own stretch of route, bending across it to meet a discrete axis at
			// whichever side is open.
			ITrackBlock.addToListIfConnected(connectedTo, list,
				(d, b) -> d == 1 ? Vec3.atLowerCornerOf(span.bePositions.get(b)) : span.starts.get(b),
				b -> getUpNormal(world, pos, state), dimensionFactory, yOffsetFactory, null, span,
				(b, v) -> ITrackBlock.getMaterialSimple(world, v, getMaterial()));

		} else if (linear) {
			// Seeding only. onRailAdded runs a linear-only query and then dereferences the start
			// node it expects to have found, so reporting nothing here crashes it — including for
			// an anchor mid-chain, which has no straight stretch of its own left to report.
			Couple<Vec3> seed = anchor != null ? anchor.seedPair()
				: Couple.create(center.subtract(axisVector(state).scale(0.5)),
					center.add(axisVector(state).scale(0.5)));

			ITrackBlock.addToListIfConnected(connectedTo, list, (d, b) -> seed.get(b),
				b -> getUpNormal(world, pos, state), dimensionFactory, yOffsetFactory,
				axisVector(state), null,
				(b, v) -> ITrackBlock.getMaterialSimple(world, v, getMaterial()));
		}
		// Otherwise this anchor is mid-chain: its curves meet at the centre and it has no span.

		if (linear || anchor == null)
			return list;

		// Both curves this anchor sits between, not just the one it owns. A node at a block's
		// centre is reachable only from that block, so reporting the curve ahead alone left the
		// graph walkable in one direction — and rebuilding walks forward after deleting everything
		// it can reach, which quietly dropped the curve behind. Reporting both makes it symmetric,
		// the way Create's own track is by storing each bezier on both of its ends.
		for (BezierConnection bc : new BezierConnection[] { anchor.buildCurveToNext(),
			anchor.buildCurveFromPrev() }) {
			if (bc == null)
				continue;

			ITrackBlock.addToListIfConnected(connectedTo, list,
				(d, b) -> d == 1 ? Vec3.atLowerCornerOf(bc.bePositions.get(b)) : bc.starts.get(b), bc.normals::get,
				b -> world instanceof Level l ? l.dimension() : Level.OVERWORLD, bc::yOffsetAt, null, bc,
				(b, v) -> getMaterial());
		}

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
		PendingLink pending = PENDING_LINKS.get(playerId);
		// A selection from another dimension cannot mean anything here, so it is not one.
		if (pending != null && !pending.dimension()
			.equals(level.dimension()))
			pending = null;

		// Clicking the anchor already selected puts it down again. Undoing a link is a different
		// gesture entirely: break the anchor, which onRemove handles.
		if (pending != null && pos.equals(pending.pos())) {
			PENDING_LINKS.remove(playerId);
			player.displayClientMessage(Component.translatable("message.createtrainnext.link_cancelled"), true);
			return ItemInteractionResult.CONSUME;
		}

		AngledTrackAnchorBlockEntity from = pending != null
			&& level.getBlockEntity(pending.pos()) instanceof AngledTrackAnchorBlockEntity f ? f : null;

		if (from == null) {
			PENDING_LINKS.put(playerId, new PendingLink(level.dimension(), pos));
			player.displayClientMessage(Component.translatable("message.createtrainnext.link_started"), true);
			return ItemInteractionResult.CONSUME;
		}

		from.linkTo(anchor);

		// The selection ends with the link rather than moving to the anchor just reached. Carrying
		// it on made a route one click per anchor, but it also left a selection standing with
		// nothing to end it, so the next anchor clicked — anywhere, however far away, however much
		// later — joined itself to wherever the last route happened to stop. Continuing a route now
		// costs one extra click, on the anchor just reached, and in exchange every link is
		// something the player pointed at twice on purpose.
		PENDING_LINKS.remove(playerId);
		player.displayClientMessage(Component.translatable("message.createtrainnext.link_done"), true);
		return ItemInteractionResult.CONSUME;
	}
}
