package com.eyunya.createtrainnext.dispatch;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.eyunya.createtrainnext.CreateTrainNext;
import com.google.common.collect.ImmutableList;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
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
 */
public class TrainPassedCondition extends ScheduleWaitCondition {

	private static final String DATA_TRAIN = "Train";
	private static final String DATA_RADIUS = "Radius";

	/** Set on the condition's own context tag once a matching train has been inside the radius. */
	private static final String CONTEXT_ARRIVED = "Arrived";

	private static final int DEFAULT_RADIUS = 32;
	private static final int MIN_RADIUS = 4;
	private static final int MAX_RADIUS = 128;

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
		if (filter.isEmpty())
			// Nothing named to wait for. Releasing the train is the safer reading of an empty field
			// than holding it at the platform forever with nothing on screen to explain why.
			return true;

		GlobalStation station = train.getCurrentStation();
		if (station == null)
			return false;

		ResourceKey<Level> dimension = station.getBlockEntityDimension();
		BlockPos stationPos = station.getBlockEntityPos();
		if (dimension == null || stationPos == null)
			return false;

		boolean present = isMatchingTrainNear(train, filter, dimension, stationPos, radius());
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
	 * Whether any train other than {@code waiting} whose name matches sits within the radius.
	 *
	 * <p>Deliberately not {@code Train#getPositionInDimension}, which reports the first carriage
	 * that has a position and so speaks for the head of the train alone. A train pulling out of a
	 * loop is not clear of it until its last carriage is.
	 */
	private static boolean isMatchingTrainNear(Train waiting, String filter, ResourceKey<Level> dimension,
		BlockPos stationPos, int radius) {
		for (Train other : Create.RAILWAYS.trains.values()) {
			if (other.id.equals(waiting.id))
				continue;
			if (!matches(other, filter))
				continue;
			for (Carriage carriage : other.carriages) {
				Optional<BlockPos> position = carriage.getPositionInDimension(dimension);
				if (position.isPresent() && position.get()
					.closerThan(stationPos, radius))
					return true;
			}
		}
		return false;
	}

	/** Substring, ignoring case, so "Express" catches "Express 1" and "Night Express" alike. */
	private static boolean matches(Train train, String filter) {
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
		Component name = Component.literal(trainFilter());
		return Component.translatable(key(context.getBoolean(CONTEXT_ARRIVED) ? "status.passing" : "status.waiting"),
			name);
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
		builder.addTextInput(0, 86, (editBox, tooltip) -> {}, DATA_TRAIN);
		builder.addScrollInput(90, 31, (input, label) -> {
			input.titled(Component.translatable(key("radius")))
				.withRange(MIN_RADIUS, MAX_RADIUS + 1)
				.withShiftStep(16);
			input.lockedTooltipX = -15;
			input.lockedTooltipY = 35;
		}, DATA_RADIUS);
	}
}
