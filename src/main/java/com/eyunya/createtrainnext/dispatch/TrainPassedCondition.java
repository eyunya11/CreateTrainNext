package com.eyunya.createtrainnext.dispatch;

import java.util.List;
import java.util.Locale;

import com.eyunya.createtrainnext.CreateTrainNext;
import com.eyunya.createtrainnext.dispatch.TrainBound.Match;
import com.google.common.collect.ImmutableList;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;
import com.simibubi.create.foundation.utility.CreateLang;

import net.createmod.catnip.data.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Holds a train at its station until another, named train has gone past.
 *
 * <p>This is the piece Create's own schedules cannot express, and the reason a passing loop is hard
 * to build with them: every wait condition Create ships looks at the waiting train itself, at its
 * cargo, or at a redstone signal, and none of them can say "let the express through first". A
 * redstone line from a Train Observer comes close, but an observer reports <em>a</em> train, not
 * <em>which</em> train, so it cannot tell the express it is waiting for from the local already
 * sitting in the loop.
 *
 * <p>Passing is measured as seen-then-gone rather than as presence, and that matters in both
 * directions. Waiting for a matching train to merely be absent would complete immediately, before
 * the express had even arrived; waiting for it to merely be present would release the local while
 * the express was still alongside. So the condition first watches a matching train come inside the
 * radius, and only then waits for it to leave.
 *
 * <p>The radius is measured from the station rather than from the waiting train, so the point being
 * cleared stays put no matter where along a long platform the train happened to stop. Every
 * carriage of the other train is tested, not just its leading one, so a train longer than the
 * radius still counts as passing until its tail is clear.
 *
 * <p>The bound match is what makes this usable on a two-track line. Without it a local waiting in
 * an up-line loop would also be released by an express running <em>down</em> the other track, which
 * is exactly the train it does not care about. Set to same-bound, the condition compares the other
 * train's schedule title against the waiting train's own, so one condition serves both directions
 * of a route without naming either — see {@link TrainBound}.
 */
public class TrainPassedCondition extends ScheduleWaitCondition {

	private static final String DATA_TRAIN = "Train";
	private static final String DATA_BOUND = "Bound";
	private static final String DATA_RADIUS = "Radius";

	/** Set on the condition's own context tag once a matching train has been inside the radius. */
	private static final String CONTEXT_ARRIVED = "Arrived";

	/**
	 * The radius is one number doing two jobs, which is what makes a large one a poor default
	 * rather than simply a generous one: it is both how far out a train is first noticed and how
	 * far it must get before it counts as gone. Widen it and the waiting train notices the express
	 * sooner, but then also sits at the platform until the express is that far past. So the value
	 * that works is the size of the conflict — the loop and its approaches — not the furthest the
	 * express can be seen from.
	 */
	private static final int DEFAULT_RADIUS = 32;
	private static final int MIN_RADIUS = 4;
	private static final int MAX_RADIUS = 256;

	public TrainPassedCondition() {
		// Written into the data tag rather than only defaulted on read, so that the number the
		// editor shows on a freshly added condition is the number it will actually use.
		data.putInt(DATA_RADIUS, DEFAULT_RADIUS);
	}

	@Override
	public ResourceLocation getId() {
		return CreateTrainNext.asResource("train_passed");
	}

	// ------------------------------------------------------------------- data

	private String trainFilter() {
		return textData(DATA_TRAIN).trim();
	}

	private Match boundMatch() {
		return enumData(DATA_BOUND, Match.class);
	}

	private int radius() {
		// Guarded rather than trusted: a condition whose editor never ran reads back as zero, and
		// a zero radius would never see anything at all.
		int radius = intData(DATA_RADIUS);
		return radius <= 0 ? DEFAULT_RADIUS : radius;
	}

	// -------------------------------------------------------------- behaviour

	@Override
	public boolean tickCompletion(Level level, Train train, CompoundTag context) {
		String filter = trainFilter();
		Match bound = boundMatch();
		if (filter.isEmpty() && bound == Match.ANY)
			// No name and no bound is no criterion at all. Releasing the train is the safer reading
			// of a blank condition than holding it at the platform forever with nothing on screen
			// to explain why. Either field on its own is a real filter, and does hold it.
			return true;

		GlobalStation station = train.getCurrentStation();
		if (station == null)
			return false;

		ResourceKey<Level> dimension = station.getBlockEntityDimension();
		BlockPos stationPos = station.getBlockEntityPos();
		if (dimension == null || stationPos == null)
			return false;

		boolean present = isMatchingTrainNear(train, filter, bound, dimension, stationPos, radius());
		boolean arrived = context.getBoolean(CONTEXT_ARRIVED);

		if (present) {
			if (!arrived) {
				context.putBoolean(CONTEXT_ARRIVED, true);
				requestStatusToUpdate(context);
			}
			return false;
		}

		return arrived;
	}

	/**
	 * Whether any train other than {@code waiting} that passes the filters sits within the radius.
	 *
	 * <p>Positions come from the track graph, not from {@code Carriage#getPositionInDimension}.
	 * That one reads the carriage's entity, which only exists where the chunk is loaded, so a train
	 * approaching through unloaded terrain would report nothing at all and no radius, however
	 * large, would find it. Create keeps trains moving through unloaded chunks, so the express this
	 * condition exists to wait for is very often exactly that train. A travelling point's position
	 * is derived from the edge it sits on and is available wherever the graph is.
	 *
	 * <p>Both ends of every carriage are tested rather than one point for the train, so a train
	 * longer than the radius still counts as passing until its tail is clear.
	 */
	private static boolean isMatchingTrainNear(Train waiting, String filter, Match bound,
		ResourceKey<Level> dimension, BlockPos stationPos, int radius) {
		Vec3 centre = Vec3.atCenterOf(stationPos);
		double radiusSqr = (double) radius * radius;

		for (Train other : Create.RAILWAYS.trains.values()) {
			if (other.id.equals(waiting.id))
				continue;
			if (other.graph == null)
				continue;
			if (!matches(other, filter))
				continue;
			if (!bound.test(waiting, other))
				continue;

			for (Carriage carriage : other.carriages)
				for (TravellingPoint point : List.of(carriage.getLeadingPoint(), carriage.getTrailingPoint())) {
					if (point.edge == null || point.node1 == null)
						continue;
					// The graph spans dimensions through portals, so an edge has to be placed before
					// its position means anything against a station in this one.
					if (!dimension.equals(point.node1.getLocation()
						.getDimension()))
						continue;
					if (point.getPosition(other.graph)
						.distanceToSqr(centre) <= radiusSqr)
						return true;
				}
		}
		return false;
	}

	/**
	 * Substring, ignoring case, so "Express" catches "Express 1" and "Night Express" alike. A blank
	 * filter matches every train, which is the useful reading once a bound is doing the filtering.
	 */
	private static boolean matches(Train train, String filter) {
		if (filter.isEmpty())
			return true;
		return train.name.getString()
			.toLowerCase(Locale.ROOT)
			.contains(filter.toLowerCase(Locale.ROOT));
	}

	// ----------------------------------------------------------------- labels

	@Override
	public Pair<ItemStack, Component> getSummary() {
		String filter = trainFilter();
		return Pair.of(ItemStack.EMPTY, filter.isEmpty() ? title() : Component.literal(filter));
	}

	@Override
	public List<Component> getTitleAs(String type) {
		String filter = trainFilter();
		if (filter.isEmpty())
			return ImmutableList.of(title());
		return ImmutableList.of(title().withStyle(ChatFormatting.GOLD),
			CreateLang.translateDirect("generic.in_quotes", Component.literal(filter)));
	}

	@Override
	public MutableComponent getWaitingStatus(Level level, Train train, CompoundTag context) {
		// Called out because it is the one way this condition can hang with nothing visibly wrong:
		// a bound match needs a title on both trains, and a route where nobody added a Rename gives
		// no hint on the board about why the train is not leaving.
		if (boundMatch() != Match.ANY && TrainBound.of(train)
			.isEmpty())
			return Component.translatable(key("status.no_bound"));

		String filter = trainFilter();
		Component subject = filter.isEmpty() ? Component.translatable(key("subject.any"))
			: Component.literal(filter);
		return Component.translatable(key(context.getBoolean(CONTEXT_ARRIVED) ? "status.passing" : "status.waiting"),
			subject);
	}

	private MutableComponent title() {
		return Component.translatable(key(null));
	}

	/** The lang key Create derives for this condition, so the editor and these labels agree. */
	private String key(String suffix) {
		ResourceLocation id = getId();
		String base = id.getNamespace() + ".schedule.condition." + id.getPath();
		return suffix == null ? base : base + "." + suffix;
	}

	// -------------------------------------------------------------------- nbt

	@Override
	protected void writeAdditional(HolderLookup.Provider registries, CompoundTag tag) {}

	@Override
	protected void readAdditional(HolderLookup.Provider registries, CompoundTag tag) {}

	// ----------------------------------------------------------------- editor

	@Override
	@OnlyIn(Dist.CLIENT)
	public void initConfigurationWidgets(ModularGuiLineBuilder builder) {
		// The widgets carry their own persistence: ModularGuiLine copies an EditBox's text and a
		// ScrollInput's state into the data tag under these keys, so there is nothing to wire up.
		builder.addTextInput(0, 48, (editBox, tooltip) -> {}, DATA_TRAIN);

		builder.addSelectionScrollInput(50, 44, (input, label) -> {
			input.forOptions(List.of(Component.translatable(key("bound.any")),
				Component.translatable(key("bound.same")), Component.translatable(key("bound.opposite"))))
				.titled(Component.translatable(key("bound")));
		}, DATA_BOUND);

		builder.addScrollInput(96, 25, (input, label) -> {
			input.titled(Component.translatable(key("radius")))
				.withRange(MIN_RADIUS, MAX_RADIUS + 1)
				.withShiftStep(16);
			input.lockedTooltipX = -15;
			input.lockedTooltipY = 35;
		}, DATA_RADIUS);
	}
}
