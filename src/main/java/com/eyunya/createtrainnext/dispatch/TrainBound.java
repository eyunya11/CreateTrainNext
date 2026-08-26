package com.eyunya.createtrainnext.dispatch;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.destination.ChangeTitleInstruction;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;

/**
 * Which way round a line a train is currently working — its 方面, up or down.
 *
 * <p>Create already has the concept and calls it the schedule title: the "Rename" instruction sets
 * one, and it is what a station's display board shows as the service. Reusing it means a bound
 * needs no storage of its own and no new instruction — a player labels each direction of a route
 * with a Rename the same way they would label it for the boards, and it doubles as the thing this
 * mod matches on.
 */
public class TrainBound {

	/**
	 * The title the given train is currently working under, or empty if it has none.
	 *
	 * <p>Read from the schedule rather than from {@link ScheduleRuntime#currentTitle}, even though
	 * that field holds the same thing and is what the boards read. The runtime's copy is not
	 * written to NBT — {@code ScheduleRuntime#read} starts by resetting it to empty — so after a
	 * world reload every train claims no bound until it happens to pass its next Rename. The
	 * schedule and the entry index into it are both persisted, so walking the one from the other
	 * survives a reload.
	 */
	public static String of(Train train) {
		ScheduleRuntime runtime = train.runtime;
		if (runtime == null)
			return "";

		// Still preferred when it is set, because it is what the player sees on the boards, and a
		// schedule edited mid-route can disagree with the title the train is actually running under.
		String live = runtime.currentTitle;
		if (live != null && !live.isBlank())
			return live.trim();

		Schedule schedule = runtime.getSchedule();
		if (schedule == null || schedule.entries.isEmpty())
			return "";

		// Backwards from where the train is now, because the title in force is the last one it
		// passed. A Rename advances the schedule as soon as it runs, so it is never the current
		// entry itself and starting the scan there costs nothing.
		int size = schedule.entries.size();
		for (int step = 0; step < size; step++) {
			int index = Math.floorMod(runtime.currentEntry - step, size);
			ScheduleInstruction instruction = schedule.entries.get(index).instruction;
			if (instruction instanceof ChangeTitleInstruction title)
				return title.getScheduleTitle()
					.trim();
			// A cyclic schedule wraps past its start, so the last Rename in the list is still in
			// force at the top of the next lap. A one-shot schedule has nothing before entry zero.
			if (!schedule.cyclic && index == 0)
				break;
		}

		return "";
	}

	/** How a condition wants the other train's bound to relate to the waiting train's own. */
	public enum Match {

		/** Bounds are not compared at all. */
		ANY,

		/** Both trains are working the same direction of the route — the passing loop case. */
		SAME,

		/** The other train is working the opposite direction, so it is on the other track. */
		OPPOSITE;

		/**
		 * Whether {@code other} satisfies this match against {@code waiting}.
		 *
		 * <p>Fails closed when either train has no bound: comparing two blanks would call them
		 * equal, so a route where nobody set a Rename would silently behave as if every train were
		 * same-bound, which is the filter quietly doing nothing rather than doing its job.
		 */
		public boolean test(Train waiting, Train other) {
			if (this == ANY)
				return true;
			String mine = of(waiting);
			String theirs = of(other);
			if (mine.isEmpty() || theirs.isEmpty())
				return false;
			boolean same = mine.equalsIgnoreCase(theirs);
			return this == SAME ? same : !same;
		}
	}

	private TrainBound() {}
}
