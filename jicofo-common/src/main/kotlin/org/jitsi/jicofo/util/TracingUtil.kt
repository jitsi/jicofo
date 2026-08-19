package org.jitsi.jicofo.util

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanId
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceId
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import org.jitsi.jicofo.xmpp.muc.ChatRoom
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jitsi.xmpp.extensions.TraceParent
import org.jivesoftware.smack.packet.IQ
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

        /**
         * Extracts a remote [Span] from the `traceparent` extension of an IQ, if present.
         *
         * Inlined from jicoco-tracing's TracingUtil, which was removed because it pulled in a Smack
         * dependency that isn't available on Maven Central: https://github.com/jitsi/jicoco/pull/241
         */
        @JvmStatic
        fun remoteSpanFromIq(iq: IQ): Span? {
            val extension = iq.getExtension(TraceParent::class.java) ?: return null
            return remoteSpan(extension.traceId, extension.parentId, extension.traceFlags)
        }

        /**
         * Extracts a remote [Context] from the `traceparent` extension of an IQ, if present, or the root
         * context otherwise.
         */
        @JvmStatic
        fun remoteContextFromIq(iq: IQ): Context {
            val root = Context.root()
            val span = remoteSpanFromIq(iq) ?: return root
            return root.with(span)
        }

        /**
         * Parses a W3C trace context `traceparent` value ("00-<trace-id>-<parent-id>-<flags>") as
         * carried in SIP/HTTP headers, rayo headers or conference properties. Returns the root
         * context when the value is missing or malformed.
         */
        @JvmStatic
        fun remoteContextFromW3CHeader(value: String?): Context {
            val root = Context.root()
            val parts = value?.trim()?.split("-") ?: return root
            if (parts.size < 4) {
                return root
            }
            val span = remoteSpan(parts[1], parts[2], parts[3]) ?: return root
            return root.with(span)
        }

        /**
         * Formats a span context as a W3C trace context `traceparent` value.
         */
        @JvmStatic
        fun toW3CHeader(spanContext: SpanContext): String =
            "00-${spanContext.traceId}-${spanContext.spanId}-${spanContext.traceFlags.asHex()}"

        private fun remoteSpan(traceId: String, spanId: String, flagsHex: String): Span? {
            if (!TraceId.isValid(traceId) || !SpanId.isValid(spanId)) {
                return null
            }
            val flags = try {
                TraceFlags.fromHex(flagsHex, 0)
            } catch (e: Exception) {
                TraceFlags.getDefault()
            }
            return Span.wrap(
                SpanContext.createFromRemoteParent(traceId, spanId, flags, TraceState.getDefault())
            )
        }
    }
}
