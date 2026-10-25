package com.eyunya.createtrainnext.signal;

/**
 * The plates on a post: the ones a signal's pole carries, and the trackside signs that are nothing but a plate
 * on a post.
 *
 * <p>場内, 出発 and 閉そく signals have the same heads; what says which is which is on the pole. A
 * 閉そく signal has a number plate under its head (the national standard asks for one, 解釈基準 Ⅹ－26), and
 * at a station with many tracks the 場内 and 出発 signals have a crown above theirs saying which track they
 * are for. Both are a plate with a few characters on it, so they are one block class with this to tell
 * them apart. A plate saying 場内 or 出発, as some private railways put on, is a number plate with that
 * written on it.
 *
 * <p>Most of the signs beside a Japanese line are the same thing: a plate on a post, its meaning in its
 * picture, with no lamp and nothing for a train to obey. They are kinds here as well, and are turned, joined
 * to poles and written on as the plates are. Those with nothing to write ({@link #maxLength()} 0) are their
 * texture alone, and no screen opens on them.
 *
 * <p>Each kind says where its plate is in its model, as drawn facing south, unturned: the renderer writes
 * on the plate's front face from these numbers, the block's shape is made from them, and a game test reads
 * the model and holds the two together, as {@code SignalModelTests} does for the heads. The colours of the
 * plates are the models' textures, and only the colour of the writing is here.
 */
public enum PlateKind {

	/** 閉そく信号機標識. Under the head, white with black figures until the colours are known. */
	NUMBER("signal_number_plate", 4, 0xFF000000, 5, 5, 9, 11, 11, 10),

	/** 冠, the track a 場内 or 出発 signal is for. Over the head, black with white figures. */
	CROWN("signal_crown_plate", 6, 0xFFFFFFFF, 4, 3, 9, 12, 8, 10),

	/**
	 * 停止位置目標, where a driver stops a train of that many cars: a number, or ◇ where every train stops at
	 * the same place, × for a train longer than any number, 併 where trains are joined.
	 */
	STOP_POSITION("stop_position_marker", 3, 0xFF000000, 4, 4, 9, 12, 12, 10),

	/** 列車停止標識, a white square with a black cross: no train goes past here. */
	TRAIN_STOP("train_stop_sign", 0, 0, 4, 4, 9, 12, 12, 10),

	/** 車両停止標識, the train stop sign in reverse colours: the end of where shunting may go. */
	CAR_STOP("car_stop_sign", 0, 0, 4, 4, 9, 12, 12, 10),

	/** 車止標識, a black square with a white cross of four lozenges: the line ends ahead. */
	BUFFER_STOP("buffer_stop_sign", 0, 0, 3, 3, 9, 13, 13, 10),

	/** 信号喚呼位置標, the yellow triangle where the driver calls out the next signal, with its number if it has one. */
	SIGNAL_CALL("signal_call_marker", 3, 0xFF000000, 4, 4, 9, 12, 11, 10),

	/** 距離標, the kilometre post, its distance written down it. */
	KILOMETRE_POST("kilometre_post", 4, 0xFF000000, true, 6, 2, 9, 10, 14, 10),

	/** 車両接触限界標, the low post past which a train would foul the next track. */
	CLEARANCE_POST("clearance_post", 0, 0, 6, 0, 9, 10, 4, 10),

	/** 信号警標, yellow with two black diagonals: a 場内 or 出発 signal stands here. */
	SIGNAL_WARNING("signal_warning_sign", 0, 0, 5, 2, 9, 11, 14, 10),

	/** 気笛吹鳴標識, a yellow square with a black cross: sound the whistle. */
	WHISTLE("whistle_sign", 0, 0, 4, 4, 9, 12, 12, 10),

	/** 制動標, where to begin braking. */
	BRAKE("brake_marker", 0, 0, 5, 5, 9, 11, 11, 10),

	/** 停車場接近標, yellow with a black stripe rising to the right: a station without a home signal is near. */
	STATION_APPROACH("station_approach_sign", 0, 0, 5, 2, 9, 11, 14, 10);

	private final String blockName;
	private final int maxLength;
	private final int textColour;
	private final boolean vertical;
	private final double[] bounds;

	PlateKind(String blockName, int maxLength, int textColour, double x0, double y0, double z0, double x1,
		double y1, double z1) {
		this(blockName, maxLength, textColour, false, x0, y0, z0, x1, y1, z1);
	}

	PlateKind(String blockName, int maxLength, int textColour, boolean vertical, double x0, double y0, double z0,
		double x1, double y1, double z1) {
		this.blockName = blockName;
		this.maxLength = maxLength;
		this.textColour = textColour;
		this.vertical = vertical;
		this.bounds = new double[] { x0, y0, z0, x1, y1, z1 };
	}

	/** The name of the block, and of its item, in the registry; also the folder its models are in. */
	public String blockName() {
		return blockName;
	}

	/** How many characters fit on the plate. Longer text is cut to this when it is set; 0 for a plate with none. */
	public int maxLength() {
		return maxLength;
	}

	/** Whether anything can be written on the plate. */
	public boolean takesText() {
		return maxLength > 0;
	}

	/** The colour the text is written in, as ARGB. */
	public int textColour() {
		return textColour;
	}

	/** Whether the text runs down the plate, a character under the one before, as on a kilometre post. */
	public boolean vertical() {
		return vertical;
	}

	/**
	 * The plate as drawn and unturned: x0, y0, z0, x1, y1, z1 in pixels. Its front, which the text is
	 * written on, is the face at z1, towards the south.
	 */
	public double[] plateBounds() {
		return bounds.clone();
	}
}
