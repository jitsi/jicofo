package org.jitsi.jicofo.util

import io.opentelemetry.api.common.Attributes
import org.jitsi.jicofo.xmpp.muc.ChatRoom
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import java.util.Objects

class TracingUtil {
    companion object {
        @JvmStatic
        fun memberAttributes(member: ChatRoomMember): Attributes {
            return Attributes.builder()
                .put("member.name", member.name)
                .put("member.id", Objects.toString(member.jid))
                .put("member.role", member.role.toString())
                .put("member.region", Objects.toString(member.region))
                .build()
        }

        @JvmStatic
        fun roomAttributes(room: ChatRoom): Attributes {
            return Attributes.builder()
                .put("room.id", Objects.toString(room.roomJid))
                .put("room.members", room.memberCount.toString())
                .put("room.visitors", room.visitorCount.toString())
                .build()
        }
    }
}
