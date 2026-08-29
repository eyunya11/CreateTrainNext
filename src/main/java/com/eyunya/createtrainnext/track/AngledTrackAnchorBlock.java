package com.eyunya.createtrainnext.track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
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
 * <p>The block carries no interactions of its own. Everything a player does to a route — aiming an
 * anchor, joining two of them, taking a join apart, putting a new one part way along a curve — is
 * done with the {@link CurveWandItem}, which places anchors as a side effect of drawing a curve
 * rather than asking anyone to place them first. Breaking an anchor still undoes its links, because
 * that is what breaking a piece of track means.
 *
 * <p>Being a real track block is also what makes an anchor somewhere a signal or a station can go:
 * Create places those against a track block and a direction, and resolves them through
 * {@code TrackGraphHelper}, which asks {@link #getTrackAxes} — see
 * {@link AngledTrackAnchorBlockEntity#targetingAxis} for why that reports the heading rather than
 * the block's discrete axis. The curve between two anchors is not such a place; Create's own
 * mid-curve targeting recognises only its own block entities.
 */
public class AngledTrackAnchorBlock extends Block implements EntityBlock, ITrackBlock {

	public static final EnumProperty<AnchorAxis> AXIS = EnumProperty.create("axis", AnchorAxis.class);

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
		// Everything that moves a curve already schedules this tick to rebuild the graph, so the
		// floor under the curve rides along with it rather than needing its own set of call sites.
		if (level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor)
			anchor.updateOccupancy();
	}

	/**
	 * Ticks only to renew the floor under the curve — see
	 * {@link AngledTrackAnchorBlockEntity#serverTick}. Client side there is nothing to renew.
	 */
	@Override
	@Nullable
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		if (level.isClientSide)
			return null;
		return (tickLevel, tickPos, tickState, blockEntity) -> {
			if (blockEntity instanceof AngledTrackAnchorBlockEntity anchor)
				anchor.serverTick();
		};
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		if (!state.is(newState.getBlock())) {
			if (!level.isClientSide && level.getBlockEntity(pos) instanceof AngledTrackAnchorBlockEntity anchor) {
				// Before unlink, which drops the link this anchor's floor is derived from.
				anchor.clearOccupancy();
				anchor.unlink();
			}
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

	/**
	 * Where a curve leaving this block would start, for Create's own track wand.
	 *
	 * <p>Deliberately ignores the length of the axis it is handed. Create passes back whatever
	 * {@link #getTrackAxes} reported, and what an anchor reports there is aimed at the directions of
	 * its edges rather than at the node lattice — and at an anchor with a curve on either side it is
	 * short on purpose, since its length is what widens the placement tolerance. Half of that vector
	 * would be a point somewhere inside the block, which is nowhere Create can put a node.
	 *
	 * <p>So only the side of the block it picked is taken from the argument. The step itself is
	 * along {@link #AXIS}, which is where an anchor's nodes actually are.
	 */
	@Override
	public Vec3 getCurveStart(BlockGetter world, BlockPos pos, BlockState state, Vec3 axis) {
		Vec3 discrete = axisVector(state);
		return Vec3.atBottomCenterOf(pos)
			.add(discrete.scale(discrete.dot(axis) < 0 ? -0.5 : 0.5));
	}

	/**
	 * Never, whatever the route is doing overhead.
	 *
	 * <p>The default answer is {@code getTrackAxes().get(0).y != 0}, and an anchor's reported axis
	 * is measured off its curves, so one on a climbing route reports an axis that rises. That would
	 * be read as a sloped block, and a sloped block has {@code getElevationAtCenter} of half a block
	 * — which is where {@code TrackGraphHelper} then looks for the node at this block's centre, and
	 * finds nothing.
	 *
	 * <p>An anchor is flat in every case. A route climbs by having its anchors at different heights,
	 * exactly as Create's own ascending curves run between two flat track blocks.
	 */
	@Override
	public boolean isSlope(BlockGetter world, BlockPos pos, BlockState state) {
		return false;
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
	 * What this anchor contributes to the track graph: its own span across the block, and the
	 * curves either side of it.
	 *
	 * <p>The span is reported one of two ways, and which one matters more than it looks.
	 *
	 * <p>Where the span <em>bends</em> — an anchor whose heading was chosen freely, so that the
	 * route has to turn from that heading onto the nearest lattice-safe axis before it can hand
	 * over to a discrete-axis neighbour — it is a {@code viaTurn} connection, because that is what
	 * it is. Turning across the block rather than at a node is the whole reason the span exists:
	 * {@code TrackEdge.canTravelTo} refuses a node where the direction changes by more than about
	 * 29 degrees, and spreading the change over an edge keeps it under that.
	 *
	 * <p>Where the span is <em>straight</em> — which is every anchor docked onto Create's own
	 * track, since the wand aims those along the track's axis — it is reported exactly the way
	 * Create's own track block reports itself: a plain edge along {@link #AXIS}, no turn. This is
	 * not cosmetic. {@code TrackPropagator.isValidGraphNodeLocation} keeps a location as a graph
	 * node whenever anything continuing from it came {@code viaTurn}, and merges it away when the
	 * continuation is straight and in line. Reported as a turn, a dock grew a node and a one block
	 * bezier edge in the middle of what is physically a straight run of track — geometry Create
	 * never produces for its own joins, and a shape trains had to negotiate for no reason.
	 * Reported straight, the propagator folds the anchor's span into the run of Create track beside
	 * it, and the junction stops being a junction at all.
	 *
	 * <p>Note what is <em>not</em> here in either case: a straight edge across a block whose span
	 * bends. An early version reported one unconditionally, so the anchor behaved like a one-block
	 * piece of track that a curve happened to leave from. But that stub can only lie on a discrete
	 * axis, while the curve leaves at whatever angle the player chose, so the two met at an angle
	 * of up to 45 degrees. The graph was right and the curve was right; the bend between them was
	 * what read as a train running beside its rails.
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

		if (span != null && anchor.spanIsStraight()) {
			// Straight, so reported straight — the same offsets, direction and material sampling
			// Create's own track block uses, so that the propagator treats a docked anchor as one
			// more block of the line it is docked to.
			Vec3 axis = axisVector(state);
			ITrackBlock.addToListIfConnected(connectedTo, list, (d, b) -> axis.scale(b ? d : -d)
				.add(center), b -> getUpNormal(world, pos, state), dimensionFactory, yOffsetFactory, axis, null,
				(b, v) -> ITrackBlock.getMaterialSimple(world, v, getMaterial()));

		} else if (span != null) {
			// The block's own stretch of route, bending across it to meet a discrete axis at
			// whichever side is open.
			BezierConnection oriented = orientedAwayFrom(span, connectedTo);
			ITrackBlock.addToListIfConnected(connectedTo, list,
				(d, b) -> d == 1 ? Vec3.atLowerCornerOf(oriented.bePositions.get(b)) : oriented.starts.get(b),
				b -> getUpNormal(world, pos, state), dimensionFactory, yOffsetFactory, null, oriented,
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
		for (BezierConnection owned : new BezierConnection[] { anchor.buildCurveToNext(),
			anchor.buildCurveFromPrev() }) {
			if (owned == null)
				continue;

			BezierConnection bc = orientedAwayFrom(owned, connectedTo);
			ITrackBlock.addToListIfConnected(connectedTo, list,
				(d, b) -> d == 1 ? Vec3.atLowerCornerOf(bc.bePositions.get(b)) : bc.starts.get(b), bc.normals::get,
				b -> world instanceof Level l ? l.dimension() : Level.OVERWORLD, bc::yOffsetAt, null, bc,
				(b, v) -> getMaterial());
		}

		return list;
	}

	/**
	 * The same curve, turned around if need be so that it runs <em>away</em> from the node being
	 * asked about.
	 *
	 * <p>This is not a detail. {@code TrackPropagator} turns a reported location into an edge with
	 * {@code connectNodes(parentNode, currentNode, currentNode.getTurn())}, and
	 * {@code TrackGraph.connectNodes} then takes the bezier to run from the first of those to the
	 * second, building the reverse edge from {@code turn.secondary()}. So a bezier reported by the
	 * end the walk arrived <em>at</em> is a bezier pointing the wrong way, and both edges across it
	 * come out backwards — the graph says one thing and the geometry says the opposite.
	 *
	 * <p>What that looks like in game is a train stopping at a junction with "reached the end of
	 * its track". {@code TrackEdge.canTravelTo} compares the direction it is leaving one edge by
	 * against the direction of the next, and a reversed edge reports its direction as very nearly
	 * the exact opposite of the truth — a dot product close to -1 where 7/8 was needed.
	 *
	 * <p>Create never hits this because each of its curves is stored twice, once on the block
	 * entity at either end, the second already reversed by {@code secondary()}. An anchor builds
	 * its curves on demand instead, and the one behind it is built by its predecessor, so both ends
	 * would otherwise report the predecessor's orientation. Which of the two ends the propagator
	 * happens to walk from decides whether that is right — and that is settled by which scheduled
	 * tick ran last, which is why the fault came and went.
	 */
	private static BezierConnection orientedAwayFrom(BezierConnection curve,
		@Nullable TrackNodeLocation connectedTo) {
		if (connectedTo == null)
			return curve;

		// Whichever end the walk is standing on has to be the bezier's first. Nearest wins rather
		// than an exact comparison, because the two ends are always far apart compared to the
		// rounding the node lattice applies, and there is nothing to tune.
		Vec3 from = connectedTo.getLocation();
		return from.distanceToSqr(curve.starts.getSecond()) < from.distanceToSqr(curve.starts.getFirst())
			? curve.secondary()
			: curve;
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
}
