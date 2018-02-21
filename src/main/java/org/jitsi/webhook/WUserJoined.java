package org.jitsi.webhook;

import org.jitsi.protocol.xmpp.XmppChatMember;

public class WUserJoined extends Webhook {

	/**
	 * Constructor
	 * 
	 * @param chatRoomMember
	 */
	public WUserJoined(XmppChatMember chatRoomMember) {
		super();
		this.setConferenceId(chatRoomMember.getContactAddressJid().getResourcepart().toString());
		this.setUserId(chatRoomMember.getContactAddressJid().getLocalpart().toString());
		this.setUserJid(chatRoomMember.getJabberID().toString());
		this.setEventType("user_joined");
	}

	/**
	 * Set user jid
	 * 
	 * @param id
	 */
	public void setUserJid(String id) {
		this.data.put("user_jid", id);
	}

	/**
	 * Set user id
	 * 
	 * @param id
	 */
	public void setUserId(String id) {
		this.data.put("user_id", id);
	}
}
