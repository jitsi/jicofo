/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026 - present 8x8, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jicofo.conference

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.jicofo.ClientRequirement
import org.jitsi.jicofo.ClientRequirementsConfig
import org.jitsi.jicofo.xmpp.Features
import org.jitsi.jicofo.xmpp.muc.ChatRoomMember
import org.jitsi.jicofo.xmpp.tryToSendStanza
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.xmpp.extensions.clientrequirements.ClientRequirementsIq
import org.jitsi.xmpp.extensions.clientrequirements.MissingFeatureExtension
import org.jitsi.xmpp.extensions.clientrequirements.RequirementLevel
import org.jitsi.xmpp.extensions.clientrequirements.RequirementsAction
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.MessageBuilder
import org.jivesoftware.smack.packet.Stanza
import org.jxmpp.jid.EntityFullJid
import java.util.UUID

/**
 * Checks that the endpoints in a conference advertise the capabilities that the deployment requires (see
 * [ClientRequirementsConfig]), notifies the ones that do not, and keeps the state necessary to do this only once per
 * endpoint.
 *
 * An endpoint which misses a requirement with a level of [RequirementLevel.HARD] is not invited to the conference. It
 * stays in the MUC, so it can still use the features which do not require a media session (e.g. chat).
 */
class ClientRequirementsHandler @JvmOverloads constructor(
    parentLogger: Logger,
    private val config: ClientRequirementsConfig = ClientRequirementsConfig.config,
    /** Sends a stanza to an endpoint. Overridable for testing. */
    private val sender: (ChatRoomMember, Stanza) -> Unit = { member, stanza ->
        member.chatRoom.xmppProvider.xmppConnection.tryToSendStanza(stanza)
    }
) {
    private val logger = createChildLogger(parentLogger)

    /** The endpoints that have already been checked and found to miss a requirement, keyed by occupant JID. */
    private val checked = mutableMapOf<EntityFullJid, Verdict>()

    /**
     * Check whether [member] advertises the required capabilities. Notify it, log and update metrics if it does not.
     *
     * Only the first call for a given member has any effect. Subsequent calls (e.g. when something triggers a
     * re-invite) return the previous result without notifying the endpoint again.
     *
     * @return true if the member should be invited to the conference.
     */
    fun checkAndNotify(member: ChatRoomMember): Boolean {
        if (!config.enabled) {
            return true
        }

        synchronized(checked) {
            checked[member.occupantJid]?.let {
                logger.info("Already checked ${member.name}: $it")
                return !it.reject
            }
        }

        // Jibri, jigasi and the transcriber advertise a different set of features, and the requirements do not apply.
        if (member.isJibri || member.isJigasi || member.isTranscriber) {
            return true
        }

        val requirements = config.requirements
        if (requirements.isEmpty()) {
            return true
        }

        val features = member.features
        if (!member.featuresDiscovered) {
            // The features were not discovered, so we can not conclude that any of them is missing.
            logger.info("Not checking requirements for ${member.name}, features were not discovered.")
            ConferenceMetrics.participantsFeaturesNotDiscovered.inc()
            return true
        }

        val missing = requirements.filter { !features.contains(it.feature) }
        if (missing.isEmpty()) {
            return true
        }

        val hasHard = missing.any { it.level == RequirementLevel.HARD }
        val verdict = Verdict(missing, reject = hasHard && config.enforce)
        synchronized(checked) {
            checked[member.occupantJid] = verdict
        }

        missing.forEach {
            ConferenceMetrics.participantsMissingFeatures.inc(listOf(it.feature.name, it.level.value))
        }
        if (verdict.reject) {
            ConferenceMetrics.participantsRejectedMissingFeatures.inc()
        }

        logger.warn(
            "Endpoint ${member.name} is missing required capabilities: " +
                "missing=${missing.map { "${it.feature.name}/${it.level.value}" }}, reject=${verdict.reject}" +
                (if (hasHard && !config.enforce) " (enforce is disabled)" else "") +
                ", clientVersion=${member.clientVersion}, statsId=${member.statsId}, region=${member.region}" +
                ", features=${features.map { it.name }}"
        )

        notify(member, verdict)
        return !verdict.reject
    }

    /**
     * Clean up the state associated with a member which left the conference.
     * @return true if the member had been rejected (i.e. was never invited).
     */
    fun memberLeft(member: ChatRoomMember): Boolean = synchronized(checked) {
        checked.remove(member.occupantJid)?.reject == true
    }

    /** The number of members which are in the MUC, but were not invited because they miss a requirement. */
    val rejectedCount: Int
        get() = synchronized(checked) { checked.values.count { it.reject } }

    private fun notify(member: ChatRoomMember, verdict: Verdict) {
        if (member.features.contains(Features.CLIENT_REQUIREMENTS_1)) {
            sender(member, createIq(member.occupantJid, verdict))
            ConferenceMetrics.clientRequirementsNotifications.inc(listOf("iq"))
        } else {
            // The endpoint does not understand the IQ. Send it plain chat messages instead, which any client renders.
            verdict.missing.forEach {
                sender(member, createMessage(member.occupantJid, it))
                ConferenceMetrics.clientRequirementsNotifications.inc(listOf("message"))
            }
        }
    }

    private fun createIq(to: EntityFullJid, verdict: Verdict): IQ = ClientRequirementsIq.Builder(
        UUID.randomUUID().toString()
    ).apply {
        action = if (verdict.reject) RequirementsAction.REJECT else RequirementsAction.WARN
        verdict.missing.forEach { requirement ->
            addExtension(
                MissingFeatureExtension(
                    feature = requirement.feature.value,
                    name = requirement.feature.name,
                    // In a dry run (enforce disabled) we do not reject the endpoint, so we do not tell it that we did.
                    level = if (verdict.reject) requirement.level else RequirementLevel.SOFT,
                    details = requirement.details,
                    url = requirement.url
                )
            )
        }
        to(to)
        ofType(IQ.Type.set)
    }.build()

    private fun createMessage(to: EntityFullJid, requirement: ClientRequirement) = MessageBuilder
        .buildMessage()
        .to(to)
        .ofType(Message.Type.chat)
        .setBody(getMessageBody(requirement))
        .build()

    private fun getMessageBody(requirement: ClientRequirement) = buildString {
        if (requirement.level == RequirementLevel.HARD && config.enforce) {
            append(
                "This app version does not support a capability that is required in this meeting " +
                    "(${requirement.feature.name}), and can not send or receive audio or video. Please update."
            )
        } else {
            append(
                "This app version does not support a capability that is expected in this meeting " +
                    "(${requirement.feature.name}). Please update."
            )
        }
        requirement.details?.let { append(" $it") }
        requirement.url?.let { append(" $it") }
    }

    val debugState: ObjectNode
        get() = JsonNodeFactory.instance.objectNode().apply {
            put("enabled", config.enabled)
            put("enforce", config.enforce)
            set<ObjectNode>(
                "requirements",
                JsonNodeFactory.instance.arrayNode().apply {
                    config.requirements.forEach { add("${it.feature.name}/${it.level.value}") }
                }
            )
            set<ObjectNode>(
                "checked",
                JsonNodeFactory.instance.objectNode().apply {
                    synchronized(checked) {
                        checked.forEach { (jid, verdict) -> put(jid.resourceOrEmpty.toString(), verdict.toString()) }
                    }
                }
            )
        }

    /** The result of checking an endpoint against the configured requirements. */
    data class Verdict(
        val missing: List<ClientRequirement>,
        /** Whether the endpoint is not to be invited to the conference. */
        val reject: Boolean
    ) {
        override fun toString() = "[missing=${missing.map { "${it.feature.name}/${it.level.value}" }}, " +
            "reject=$reject]"
    }
}
