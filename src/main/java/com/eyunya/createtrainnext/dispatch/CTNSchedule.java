package com.eyunya.createtrainnext.dispatch;

import java.util.function.Supplier;

import com.eyunya.createtrainnext.CreateTrainNext;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;

import net.createmod.catnip.data.Pair;
import net.minecraft.resources.ResourceLocation;

/**
 * The schedule entries this mod adds to Create's train schedules.
 *
 * <p>Like {@code EdgePointType}, Create keeps these in a plain static list rather than a game
 * registry, and its own {@code registerCondition} is private — but the list itself is public and
 * mutable, which is the seam an addon gets. Adding to it has to happen before anything reads it,
 * and the readers are late: a saved schedule resolves its conditions by id when the schedule item
 * is loaded, and the editor builds its dropdown when a player opens one. The mod constructor is
 * early enough for both.
 *
 * <p>Registering on both sides is deliberate rather than an oversight. The condition is evaluated
 * on the server, but the client needs the same entry present to name it in the dropdown and to
 * build its editor row, and a client missing it would fall through to Create's "could not parse"
 * path and silently drop the condition out of the schedule.
 */
public class CTNSchedule {

	/**
	 * Waits at a station until a named train has come past and gone.
	 *
	 * <p>The id is written into every schedule item that uses it, so renaming it would turn those
	 * conditions into unparseable entries and drop them from the schedule.
	 */
	public static final ResourceLocation TRAIN_PASSED = CreateTrainNext.asResource("train_passed");

	public static void register() {
		registerCondition(TRAIN_PASSED, TrainPassedCondition::new);
	}

	private static void registerCondition(ResourceLocation id, Supplier<? extends ScheduleWaitCondition> factory) {
		Schedule.CONDITION_TYPES.add(Pair.of(id, factory));
	}

	private CTNSchedule() {}
}
