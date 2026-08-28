package com.eyunya.createtrainnext.signal;

import org.jetbrains.annotations.Nullable;

/**
 * What a 中継信号機 shows: the state of the next signal, and nothing else.
 *
 * <p>Three lamps light at a time, out of a bank of seven arranged so that the three form a bar
 * across, a bar on the diagonal, or a bar upright. The shape is the message — a driver reads the
 * angle of the bar, not its colour, which is why every lamp is the same white. That also means it
 * survives being seen badly: an angle is still an angle through rain, at night, or at the far end
 * of a straight.
 *
 * <p>Only three indications for five aspects, because that is all a relay is asked for. 警戒, 注意
 * and 減速 all say the same thing to a driver who cannot see the signal yet — expect a restriction
 * — and are not worth distinguishing at relay range. What matters is stop, restricted, or clear.
 *
 * <p>This relays; it does not decide. Nothing here is consulted by anything that moves a train.
 */
public enum RelayIndication {

	/**
	 * Nothing lit. There is no next signal to relay — the line ahead has none, or the target track
	 * is gone.
	 *
	 * <p>Dark rather than falling back to {@link #STOP}, because a relay is not a signal and being
	 * fail-safe is not its job: it has no authority to stop anything, and showing a stop that no
	 * signal is displaying would be a lie about a signal that does not exist. A real relay goes dark
	 * when the signal it repeats is out of service, for the same reason.
	 */
	DARK,

	/** 停止中継. Three across. The signal ahead is at 停止. */
	STOP,

	/** 制限中継. Three on the diagonal. The signal ahead is at 警戒, 注意 or 減速. */
	RESTRICTED,

	/** 進行中継. Three upright. The signal ahead is at 進行. */
	PROCEED;

	/** Whether this indication lights the lamp in the given socket. */
	public boolean isLit(RelaySocket socket) {
		return socket.litBy(this);
	}

	/** What to show for the aspect the next signal is displaying, or {@link #DARK} if there is none. */
	public static RelayIndication of(@Nullable SignalAspect aspect) {
		if (aspect == null)
			return DARK;
		return switch (aspect) {
			case STOP -> STOP;
			case RESTRICTED, APPROACH, REDUCED_SPEED -> RESTRICTED;
			case PROCEED -> PROCEED;
		};
	}
}
