package com.eyunya.createtrainnext;

import org.apache.commons.lang3.tuple.Pair;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The mod's configuration.
 *
 * <p>Client only so far, and deliberately: the one setting here changes how a sign is drawn and
 * nothing else, so it is a matter of taste per player rather than something a server has to agree
 * on. A speed limit that actually governed trains would belong in a server config instead.
 */
public class CTNConfig {

	public static final Client CLIENT;
	public static final ModConfigSpec CLIENT_SPEC;

	static {
		Pair<Client, ModConfigSpec> client = new ModConfigSpec.Builder().configure(Client::new);
		CLIENT = client.getLeft();
		CLIENT_SPEC = client.getRight();
	}

	public static class Client {

		public final ModConfigSpec.DoubleValue signFontScale;

		private Client(ModConfigSpec.Builder builder) {
			builder.comment("Speed limit signs")
				.push("speed_limit_sign");

			signFontScale = builder
				.comment("How large the limit is drawn on the face of a speed limit sign, as a multiplier.",
					"Every limit is drawn at the same size rather than stretched to fill the face, and",
					"that size is set by the widest one that has to fit, which is '100'.",
					"At the default of 1.7 a limit of 100 spans about 10 of the face's 11 pixels, so it",
					"overlaps the red ring; below about 1.15 three digits stay inside the white centre.",
					"Above 2.0 a three digit limit runs off the edge of the sign altogether.")
				.defineInRange("fontScale", 1.7, 0.25, 4.0);

			builder.pop();
		}
	}

	private CTNConfig() {}
}
