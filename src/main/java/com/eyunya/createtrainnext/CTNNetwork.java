package com.eyunya.createtrainnext;

import com.eyunya.createtrainnext.track.SetCurveAnglePacket;
import com.eyunya.createtrainnext.track.SetCurveGradePacket;
import com.eyunya.createtrainnext.track.SetSurveyMarkerPacket;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The mod's packets.
 *
 * <p>Registered against NeoForge directly rather than through Create's own network helper: this
 * mod's only packet is about a tool in a player's hand and has nothing to do with Create's
 * machinery, and going through Create would tie the two versions together for no gain.
 */
public class CTNNetwork {

	/**
	 * Bumped when a packet's shape changes.
	 *
	 * <p>Clients whose version does not match are refused, which is the point — the alternative is
	 * a client that connects and then misreads every packet it is sent.
	 */
	private static final String VERSION = "2";

	public static void init(IEventBus modEventBus) {
		modEventBus.addListener(CTNNetwork::register);
	}

	private static void register(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar(VERSION);
		registrar.playToServer(SetCurveAnglePacket.TYPE, SetCurveAnglePacket.STREAM_CODEC,
			SetCurveAnglePacket::handle);
		registrar.playToServer(SetCurveGradePacket.TYPE, SetCurveGradePacket.STREAM_CODEC,
			SetCurveGradePacket::handle);
		registrar.playToServer(SetSurveyMarkerPacket.TYPE, SetSurveyMarkerPacket.STREAM_CODEC,
			SetSurveyMarkerPacket::handle);
	}

	private CTNNetwork() {}
}
