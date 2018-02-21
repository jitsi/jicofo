package org.jitsi.webhook;

public class WRoomEnded extends Webhook {

	/**
	 * Constructor
	 * 
	 * @param id
	 */
	public WRoomEnded(String id) {
		this.setConferenceId(id);
		this.setEventType("room_ended");
	}
}
