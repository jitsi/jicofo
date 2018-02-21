package org.jitsi.webhook;

import org.jitsi.protocol.xmpp.XmppChatMember;

public class WUserEnded extends WUserJoined {
	
	/**
	 * Constructor
	 * 
	 * @param chatRoomMember
	 */
	public WUserEnded(XmppChatMember chatRoomMember) {
		super(chatRoomMember);
		this.setEventType("user_left");
	}

}
