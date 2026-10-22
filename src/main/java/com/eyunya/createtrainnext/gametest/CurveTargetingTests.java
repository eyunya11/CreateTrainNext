package com.eyunya.createtrainnext.gametest;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.eyunya.createtrainnext.CTNRegistry;
import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.signal.MultiAspectSignalBlockEntity;
import com.eyunya.createtrainnext.signal.SignalAspect;
import com.eyunya.createtrainnext.track.AnchorAxis;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlock;
import com.eyunya.createtrainnext.track.AngledTrackAnchorBlockEntity;
import com.eyunya.createtrainnext.track.CurvePlanner;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraphHelper;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.BezierTrackPointLocation;
import com.simibubi.create.content.trains.track.CurvedTrackDestroyPacket;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem.OverlapResult;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks that Create's curve targeting finds the curves between anchors, so that a signal or a
 * station can be put on a curve without a point being added to it first.
 *
 * <p>The picking itself, and drawing the marker, are on the client and are not here. What is here
 * is what everything after the pick depends on: the connections an anchor holds for Create, the
 * edge Create resolves a picked segment to, and a signal that is placed against it.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class CurveTargetingTests {

	private static final String EMPTY = "empty";

	/**
	 * Run apart from the other tests, so that they are laid out where they were before these were
	 * added. Some of the others build beyond the box their template gives them, and some count the
	 * nodes in theirs, so which of them stand next to which is not something to change by adding
	 * tests to the same batch.
	 */
	private static final String BATCH = "curveTargeting";

	private static final float EAST = 270.0F;
	private static final float WEST = 90.0F;

	/** How long Create takes to build a graph out of anchors that have just been linked. */
	private static final int BUILD_TICKS = 8;

	/** How long a signal takes to find its edge once it has been told where to stand. */
	private static final int SIGNAL_TICKS = 6;

	/** Long enough for a head to have worked out its aspect, which it does every ten ticks. */
	private static final int OPEN_LINE_TICKS = 30;

	private static final double SAME = 1.0E-3;

	private static BlockPos at(int x) {
		return new BlockPos(x, 2, 16);
	}

	private static BlockPos[] along(int... xs) {
		BlockPos[] positions = new BlockPos[xs.length];
		for (int i = 0; i < xs.length; i++)
			positions[i] = at(xs[i]);
		return positions;
	}

	/** A route through the given positions in order, every anchor pointed along {@code yaw}. */
	private static AngledTrackAnchorBlockEntity[] route(GameTestHelper helper, float yaw, BlockPos... positions) {
		AngledTrackAnchorBlockEntity[] anchors = new AngledTrackAnchorBlockEntity[positions.length];
		for (int i = 0; i < positions.length; i++) {
			helper.setBlock(positions[i], CTNRegistry.ANGLED_TRACK_ANCHOR.get()
				.defaultBlockState()
				.setValue(AngledTrackAnchorBlock.AXIS, AnchorAxis.Z_ORTHO));
			anchors[i] = (AngledTrackAnchorBlockEntity) helper.getBlockEntity(positions[i]);
		}

		Vec3 heading = AngledTrackAnchorBlockEntity.directionFromYaw(yaw);
		for (int i = 0; i + 1 < anchors.length; i++)
			anchors[i].linkTo(anchors[i + 1], i == 0 ? heading : null, heading);
		return anchors;
	}

	// --------------------------------------------------------- what is held

	/**
	 * Each curve is held by one of its two anchors, as a primary connection keyed by the other.
	 * Running east, the lower position is the one the curve leaves from, and it is held as built.
	 */
	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 60)
	public static void aCurveIsHeldByItsLowerEndWhenTheRouteRunsUp(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = route(helper, EAST, along(6, 14, 22, 30));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			for (int i = 0; i < 3; i++) {
				AngledTrackAnchorBlockEntity holder = anchors[i];
				helper.assertTrue(holder.getConnections()
					.size() == 1, "anchor " + i + " should hold exactly the curve it leaves by, but holds "
						+ holder.getConnections()
							.size());

				BezierConnection held = holder.getConnections()
					.get(anchors[i + 1].getBlockPos());
				helper.assertTrue(held != null, "anchor " + i + " should hold its curve under the next anchor");
				helper.assertTrue(held.isPrimary(), "a held curve has to be the primary connection");
				helper.assertTrue(held.bePositions.getFirst()
					.equals(holder.getBlockPos()), "the held curve should start at the anchor holding it");
				helper.assertTrue(held.equalsSansMaterial(holder.buildCurveToNext()),
					"anchor " + i + " holds a curve that is not the one the route has");
			}
			helper.assertTrue(anchors[3].getConnections()
				.isEmpty(), "the last anchor is nobody's lower end and should hold nothing");

			// Only a client's anchors are ever offered to be picked, whatever the client is doing.
			AngledTrackAnchorBlockEntity.setCurveInteraction(true);
			try {
				helper.assertTrue(anchors[0].hasCurves() && !anchors[0].hasInteractableConnections(),
					"a server's anchor should hold curves and still not be offered to be picked");
			} finally {
				AngledTrackAnchorBlockEntity.setCurveInteraction(false);
			}
			helper.succeed();
		});
	}

	/**
	 * Running west, the lower position is the one the curve arrives at, so it holds the curve the
	 * other way round. That is the point of choosing by position: a route turned round must not
	 * change which anchor a signal on one of its curves was placed against.
	 */
	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 60)
	public static void aCurveIsHeldByItsLowerEndWhenTheRouteRunsDown(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = route(helper, WEST, along(30, 22, 14, 6));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			helper.assertTrue(anchors[0].getConnections()
				.isEmpty(), "the first anchor is the highest and should hold nothing");

			for (int i = 1; i < 4; i++) {
				AngledTrackAnchorBlockEntity holder = anchors[i];
				AngledTrackAnchorBlockEntity farEnd = anchors[i - 1];
				helper.assertTrue(holder.getConnections()
					.size() == 1, "anchor " + i + " should hold exactly the curve it arrives by, but holds "
						+ holder.getConnections()
							.size());

				BezierConnection held = holder.getConnections()
					.get(farEnd.getBlockPos());
				helper.assertTrue(held != null, "anchor " + i + " should hold its curve under the anchor before it");
				helper.assertTrue(held.isPrimary(), "a held curve has to be the primary connection");
				helper.assertTrue(held.bePositions.getFirst()
					.equals(holder.getBlockPos()), "the held curve should start at the anchor holding it");
				helper.assertTrue(near(held.starts.getFirst(), holder.entryNode()),
					"the held curve should start at the node it arrives at on the holder");
				helper.assertTrue(near(held.starts.getSecond(), farEnd.exitNode()),
					"the held curve should end at the node it leaves from on the far anchor");
				helper.assertTrue(held.equalsSansMaterial(farEnd.buildCurveToNext()),
					"anchor " + i + " holds a curve that is not the one the route has");
			}
			helper.succeed();
		});
	}

	// ------------------------------------------------------ finding the edge

	/**
	 * Create turns a picked segment into an edge of the graph. Both ways round, because which way
	 * a signal faces is which way it is put.
	 */
	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 60)
	public static void everySegmentOfAHeldCurveFindsItsEdge(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = route(helper, EAST, along(6, 14, 22, 30));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			Level level = helper.getLevel();
			AngledTrackAnchorBlockEntity holder = anchors[1];
			BlockPos farEnd = anchors[2].getBlockPos();
			BezierConnection held = holder.getConnections()
				.get(farEnd);
			int segments = (int) (held.getLength() * 2);
			helper.assertTrue(segments >= 8, "setup: the curve should be long enough to have segments");

			for (int segment : new int[] { 0, segments / 2, segments - 2 })
				for (AxisDirection direction : AxisDirection.values()) {
					TrackGraphLocation found = TrackGraphHelper.getBezierGraphLocationAt(level, holder.getBlockPos(),
						direction, new BezierTrackPointLocation(farEnd, segment));
					String what = "segment " + segment + " facing " + direction;
					helper.assertTrue(found != null, what + " should resolve to an edge");

					Vec3 holderNode = held.starts.getFirst();
					Vec3 farNode = held.starts.getSecond();
					boolean positive = direction == AxisDirection.POSITIVE;
					helper.assertTrue(near(found.edge.getFirst()
						.getLocation(), positive ? farNode : holderNode), what + " starts at the wrong node");
					helper.assertTrue(near(found.edge.getSecond()
						.getLocation(), positive ? holderNode : farNode), what + " ends at the wrong node");

					TrackEdge edge = found.graph.getConnection(found.edge.map(found.graph::locateNode));
					helper.assertTrue(edge != null, what + " names an edge the graph does not hold");
					double along = (segment + 1) / 2.0;
					double expected = positive ? edge.getLength() - along : along;
					helper.assertTrue(Math.abs(found.position - expected) < SAME,
						what + " is " + found.position + " along the edge, not " + expected);
				}
			helper.succeed();
		});
	}

	// ------------------------------------------------------------- a signal

	/**
	 * The whole chain from a picked segment to a signal that exists: the placement check passes, and
	 * a signal told to stand against the curve makes itself a point on the edge between the anchors.
	 */
	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 80)
	public static void aSignalPlacedAgainstACurveStandsOnItsEdge(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = route(helper, EAST, along(6, 14, 22, 30));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			Level level = helper.getLevel();
			AngledTrackAnchorBlockEntity holder = anchors[1];
			BlockPos farEnd = anchors[2].getBlockPos();
			BezierConnection held = holder.getConnections()
				.get(farEnd);
			int segment = (int) (held.getLength() * 2) / 2;

			AtomicReference<OverlapResult> result = new AtomicReference<>();
			TrackTargetingBlockItem.withGraphLocation(level, holder.getBlockPos(), true,
				new BezierTrackPointLocation(farEnd, segment), EdgePointType.SIGNAL,
				(overlap, location) -> result.set(overlap));
			helper.assertTrue(result.get() == OverlapResult.VALID,
				"a signal should be allowed on the curve, but the check said " + result.get());

			MultiAspectSignalBlockEntity signal =
				signalOnCurve(helper, new BlockPos(18, 2, 18), holder, farEnd, segment, true);

			helper.runAfterDelay(SIGNAL_TICKS, () -> {
				SignalBoundary point = signal.edgePoint.getEdgePoint();
				helper.assertTrue(point != null, "the signal should have made itself a point on the curve's edge");

				Vec3 first = point.edgeLocation.getFirst()
					.getLocation();
				Vec3 second = point.edgeLocation.getSecond()
					.getLocation();
				Vec3 holderNode = held.starts.getFirst();
				Vec3 farNode = held.starts.getSecond();
				helper.assertTrue(near(first, holderNode) && near(second, farNode)
					|| near(first, farNode) && near(second, holderNode),
					"the signal's point should be on the edge between the two anchors");
				helper.succeed();
			});
		});
	}

	/**
	 * A head on a line with nothing ahead of it shows the most permissive aspect its type has, and not
	 * the five-aspect's: the line is clear as far as the head can look, and how far that is, and so what
	 * it is called, is for the type's ladder to say. The head here has its own block, so a head that read
	 * the wrong ladder would show the wrong rung.
	 */
	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 120)
	public static void aTwoAspectHeadOnAnOpenLineShowsProceed(GameTestHelper helper) {
		openLine(helper, CTNRegistry.MULTI_ASPECT_SIGNAL_2.get(), SignalAspect.PROCEED);
	}

	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 120)
	public static void aThreeAspectHeadOnAnOpenLineShowsProceed(GameTestHelper helper) {
		openLine(helper, CTNRegistry.MULTI_ASPECT_SIGNAL_3.get(), SignalAspect.PROCEED);
	}

	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 120)
	public static void aFourAspectHeadOnAnOpenLineShowsProceed(GameTestHelper helper) {
		openLine(helper, CTNRegistry.MULTI_ASPECT_SIGNAL_4.get(), SignalAspect.PROCEED);
	}

	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 120)
	public static void aFiveAspectHeadOnAnOpenLineShowsProceed(GameTestHelper helper) {
		openLine(helper, CTNRegistry.MULTI_ASPECT_SIGNAL.get(), SignalAspect.PROCEED);
	}

	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 120)
	public static void aSixAspectHeadOnAnOpenLineShowsHighSpeedProceed(GameTestHelper helper) {
		openLine(helper, CTNRegistry.MULTI_ASPECT_SIGNAL_6.get(), SignalAspect.HIGH_SPEED_PROCEED);
	}

	private static void openLine(GameTestHelper helper, Block block, SignalAspect expected) {
		AngledTrackAnchorBlockEntity[] anchors = route(helper, EAST, along(6, 14, 22, 30));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			AngledTrackAnchorBlockEntity holder = anchors[1];
			BlockPos farEnd = anchors[2].getBlockPos();
			int segment = (int) (holder.getConnections()
				.get(farEnd)
				.getLength() * 2) / 2;
			MultiAspectSignalBlockEntity signal =
				signalOnCurve(helper, new BlockPos(18, 2, 18), holder, farEnd, segment, true, block);

			helper.runAfterDelay(SIGNAL_TICKS + OPEN_LINE_TICKS, () -> {
				helper.assertTrue(signal.getAspect() == expected, "a head of " + signal.signalType() + " on an open line should show "
					+ expected + ", but shows " + signal.getAspect());
				helper.succeed();
			});
		});
	}

	/**
	 * A route turned round leaves the graph alone, and the curve is held by the same anchor under the
	 * same key, so a signal's address still means the same edge.
	 */
	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 60)
	public static void aCurveStillFindsItsEdgeAfterTheRouteIsTurnedRound(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = route(helper, EAST, along(6, 14, 22, 30));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			Level level = helper.getLevel();
			BlockPos holderPos = anchors[1].getBlockPos();
			BlockPos farEnd = anchors[2].getBlockPos();
			int segment = (int) (anchors[1].getConnections()
				.get(farEnd)
				.getLength() * 2) / 2;

			helper.assertTrue(anchors[0].reverseRoute() == 4, "setup: all four anchors should have been turned");

			helper.assertTrue(anchors[1].getConnections()
				.containsKey(farEnd), "the same anchor should still hold the curve under the same key");
			helper.assertTrue(anchors[1].getConnections()
				.get(farEnd)
				.isPrimary(), "and still as the primary connection");

			for (AxisDirection direction : AxisDirection.values())
				helper.assertTrue(TrackGraphHelper.getBezierGraphLocationAt(level, holderPos, direction,
					new BezierTrackPointLocation(farEnd, segment)) != null,
					"the segment should still resolve to an edge facing " + direction);
			helper.succeed();
		});
	}

	// ------------------------------------------------------------ splitting

	/** A curve with a signal on it is not split, since the signal would be left with no curve. */
	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 80)
	public static void aCurveWithASignalOnItIsNotSplit(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = route(helper, EAST, along(6, 14, 22, 30));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			Level level = helper.getLevel();
			AngledTrackAnchorBlockEntity holder = anchors[1];
			BlockPos farEnd = anchors[2].getBlockPos();
			BezierConnection held = holder.getConnections()
				.get(farEnd);
			int segment = (int) (held.getLength() * 2) / 2;

			Vec3 onSignalled = held.getPosition(0.5);
			Vec3 onFree = anchors[2].buildCurveToNext()
				.getPosition(0.5);

			CurvePlanner.SplitTarget before = CurvePlanner.findCurveNear(level, onSignalled, 2.0);
			helper.assertTrue(before != null && before.valid(),
				"setup: a curve with nothing on it should be possible to split, but: "
					+ (before == null ? "no curve found" : before.error()));

			MultiAspectSignalBlockEntity signal =
				signalOnCurve(helper, new BlockPos(18, 2, 18), holder, farEnd, segment, true);

			helper.runAfterDelay(SIGNAL_TICKS, () -> {
				helper.assertTrue(signal.edgePoint.getEdgePoint() != null, "setup: the signal should be on the curve");

				CurvePlanner.SplitTarget refused = CurvePlanner.findCurveNear(level, onSignalled, 2.0);
				helper.assertTrue(refused != null && "split_has_points".equals(refused.error()),
					"a curve with a signal on it should not be split, but: "
						+ (refused == null ? "no curve found" : refused.error()));

				CurvePlanner.SplitTarget other = CurvePlanner.findCurveNear(level, onFree, 2.0);
				helper.assertTrue(other != null && other.valid(),
					"the next curve along has nothing on it and should still be split, but: "
						+ (other == null ? "no curve found" : other.error()));
				helper.succeed();
			});
		});
	}

	/**
	 * The ends of a curve with a signal on it say so, and no other anchor does. What the wand asks
	 * before it takes an anchor's curves apart.
	 */
	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 80)
	public static void theEndsOfACurveWithASignalOnItSaySo(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = route(helper, EAST, along(6, 14, 22, 30));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			AngledTrackAnchorBlockEntity holder = anchors[1];
			BlockPos farEnd = anchors[2].getBlockPos();
			int segment = (int) (holder.getConnections()
				.get(farEnd)
				.getLength() * 2) / 2;

			for (AngledTrackAnchorBlockEntity anchor : anchors)
				helper.assertTrue(!anchor.carriesPoints(), "setup: nothing is on any curve yet");

			MultiAspectSignalBlockEntity signal =
				signalOnCurve(helper, new BlockPos(18, 2, 18), holder, farEnd, segment, true);

			helper.runAfterDelay(SIGNAL_TICKS, () -> {
				helper.assertTrue(signal.edgePoint.getEdgePoint() != null, "setup: the signal should be on the curve");
				helper.assertTrue(!anchors[0].carriesPoints(), "the anchor before the curve should say nothing is on it");
				helper.assertTrue(anchors[1].carriesPoints() && anchors[2].carriesPoints(),
					"both ends of the curve with the signal on it should say so");
				helper.assertTrue(!anchors[3].carriesPoints(), "the anchor after the next curve should say nothing is on it");
				helper.succeed();
			});
		});
	}

	// ------------------------------------------------------------ breaking

	/**
	 * Create's packet for breaking a curve by hand, sent for an anchor's curve, does nothing: no track
	 * for the player, none on the ground, the curve still held, and the graph still has its edge.
	 * What a client does cannot be relied on to keep it from being sent.
	 */
	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 80)
	public static void aDestroyPacketForAnAnchorCurveChangesNothing(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = route(helper, EAST, along(6, 14, 22, 30));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			ServerLevel level = helper.getLevel();
			AngledTrackAnchorBlockEntity holder = anchors[1];
			BlockPos farEnd = anchors[2].getBlockPos();
			int segment = (int) (holder.getConnections()
				.get(farEnd)
				.getLength() * 2) / 2;

			// Not a creative player, who is given nothing for breaking track anyway.
			ServerPlayer player = FakePlayerFactory.getMinecraft(level);
			player.getInventory()
				.clearContent();
			Vec3 near = Vec3.atCenterOf(holder.getBlockPos());
			player.setPos(near.x, near.y + 1, near.z);
			helper.assertTrue(!player.isCreative(), "setup: the player should not be in creative");

			for (boolean wrench : new boolean[] { true, false })
				new CurvedTrackDestroyPacket(holder.getBlockPos(), farEnd, holder.getBlockPos(), wrench).handle(player);

			helper.assertTrue(holder.getConnections()
				.containsKey(farEnd), "the curve should still be held");
			helper.assertTrue(holder.getNext() != null && anchors[2].getPrev() != null, "the links should be as they were");
			helper.assertTrue(player.getInventory()
				.isEmpty(), "the player should have been given no track");

			List<ItemEntity> dropped = level.getEntitiesOfClass(ItemEntity.class,
				new AABB(holder.getBlockPos()).inflate(40));
			helper.assertTrue(dropped.isEmpty(), "no track should have been dropped, but " + dropped.size() + " was");

			helper.runAfterDelay(SIGNAL_TICKS, () -> {
				helper.assertTrue(TrackGraphHelper.getBezierGraphLocationAt(level, holder.getBlockPos(),
					AxisDirection.NEGATIVE, new BezierTrackPointLocation(farEnd, segment)) != null,
					"the curve should still have its edge in the graph");
				helper.succeed();
			});
		});
	}

	// ------------------------------------------------------------ saving

	/** What was held before a save is held after it, along with the links and the heading. */
	@GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 60)
	public static void savingAndLoadingKeepsTheLinksAndTheCurves(GameTestHelper helper) {
		AngledTrackAnchorBlockEntity[] anchors = route(helper, EAST, along(6, 14, 22));

		helper.runAfterDelay(BUILD_TICKS, () -> {
			HolderLookup.Provider registries = helper.getLevel()
				.registryAccess();
			AngledTrackAnchorBlockEntity original = anchors[1];

			CompoundTag saved = original.saveWithoutMetadata(registries);
			AngledTrackAnchorBlockEntity loaded =
				new AngledTrackAnchorBlockEntity(original.getBlockPos(), original.getBlockState());
			loaded.loadWithComponents(saved, registries);

			helper.assertTrue(loaded.getYaw() == original.getYaw(), "the heading should have been kept");
			helper.assertTrue(java.util.Objects.equals(loaded.getPrev(), original.getPrev())
				&& java.util.Objects.equals(loaded.getNext(), original.getNext()), "the links should have been kept");
			helper.assertTrue(loaded.getConnections()
				.keySet()
				.equals(original.getConnections()
					.keySet()), "the held curves should have been kept");
			original.getConnections()
				.forEach((key, curve) -> helper.assertTrue(curve.equalsSansMaterial(loaded.getConnections()
					.get(key)), "the curve under " + key + " should be the same shape afterwards"));
			helper.succeed();
		});
	}

	// --------------------------------------------------------------- helpers

	/**
	 * A five-aspect signal told to stand against a segment of a curve, in the way the item that
	 * places one tells it: the block it is against, which way it faces, and the curve and segment.
	 */
	private static MultiAspectSignalBlockEntity signalOnCurve(GameTestHelper helper, BlockPos relative,
		AngledTrackAnchorBlockEntity holder, BlockPos farEnd, int segment, boolean front) {
		return signalOnCurve(helper, relative, holder, farEnd, segment, front, CTNRegistry.MULTI_ASPECT_SIGNAL.get());
	}

	private static MultiAspectSignalBlockEntity signalOnCurve(GameTestHelper helper, BlockPos relative,
		AngledTrackAnchorBlockEntity holder, BlockPos farEnd, int segment, boolean front, Block block) {
		helper.setBlock(relative, block.defaultBlockState());
		helper.assertTrue(helper.getBlockEntity(relative) instanceof MultiAspectSignalBlockEntity,
			"no signal block entity where one was placed at " + relative);
		MultiAspectSignalBlockEntity signal = (MultiAspectSignalBlockEntity) helper.getBlockEntity(relative);

		BlockPos signalPos = helper.absolutePos(relative);
		CompoundTag bezier = new CompoundTag();
		bezier.putInt("Segment", segment);
		bezier.put("Key", NbtUtils.writeBlockPos(farEnd.subtract(signalPos)));

		CompoundTag target = new CompoundTag();
		target.putBoolean("TargetDirection", front);
		target.put("TargetTrack", NbtUtils.writeBlockPos(holder.getBlockPos()
			.subtract(signalPos)));
		target.put("Bezier", bezier);

		signal.edgePoint.read(target, helper.getLevel()
			.registryAccess(), false);
		return signal;
	}

	private static boolean near(Vec3 a, Vec3 b) {
		return a.distanceTo(b) < SAME;
	}
}
