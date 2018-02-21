package org.jitsi.webhook;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

public class Webhook {

	/**
	 * Data to send request
	 */
	protected Map<String, Object> data = new HashMap<>();

	/**
	 * Constructor
	 */
	public Webhook() {
		this.setDateTime();
	}

	/**
	 * Create thread send information to api
	 */
	public void send() {
		Runnable sendEvent = new SendEvent(this.data);
		Thread thread = new Thread(sendEvent);
		thread.start();
	}

	/**
	 * Set conference id
	 * 
	 * @param id
	 */
	public void setConferenceId(String id) {
		this.data.put("conference_id", id);
	}

	/**
	 * Set event type
	 * 
	 * @param type
	 */
	public void setEventType(String type) {
		this.data.put("event_type", type);
	}

	/**
	 * Set current date time
	 */
	public void setDateTime() {
		Timestamp timestamp = new Timestamp(System.currentTimeMillis());
		this.data.put("date_time", timestamp.getTime());
	}

}
