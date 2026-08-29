package com.eyunya.createtrainnext;

import org.apache.commons.lang3.tuple.Pair;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The mod's configuration.
 *
 * <p>Split the way the settings themselves divide. Everything under {@link Client} changes how
 * something is drawn and nothing else, so each is a matter of taste per player. {@link Server}
 * holds the settings that change what is actually in the world, which every player connected has
 * to agree on — the one there decides whether a curve is something you fall through or something
 * you walk along.
 */
public class CTNConfig {

	public static final Client CLIENT;
	public static final ModConfigSpec CLIENT_SPEC;

	public static final Server SERVER;
	public static final ModConfigSpec SERVER_SPEC;

	static {
		Pair<Client, ModConfigSpec> client = new ModConfigSpec.Builder().configure(Client::new);
		CLIENT = client.getLeft();
		CLIENT_SPEC = client.getRight();

		Pair<Server, ModConfigSpec> server = new ModConfigSpec.Builder().configure(Server::new);
		SERVER = server.getLeft();
		SERVER_SPEC = server.getRight();
	}

	public static class Server {

		public final ModConfigSpec.BooleanValue curveCollision;

		private Server(ModConfigSpec.Builder builder) {
			builder.comment("Free angle curves")
				.push("curves");

			curveCollision = builder
				.comment("Whether a curve is something you can stand on.",
					"Create's own curves are not. Its straight track carries a two pixel collision",
					"slab, but a curve between two track blocks is drawn and nothing more, so walking",
					"onto one drops you through it. With this on, a curve laid by this mod puts an",
					"invisible floor along itself at the height the rails are drawn - the same slab",
					"straight track already has, following the bend.",
					"That floor is small blocks placed along the curve and renewed every few seconds",
					"by the anchors at either end. They cannot be seen, looked at or mined, and",
					"building over one simply takes it out. Turning this off stops new ones being",
					"placed; the ones already down expire on their own within a few minutes.")
				.define("collision", true);

			builder.pop();
		}
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
