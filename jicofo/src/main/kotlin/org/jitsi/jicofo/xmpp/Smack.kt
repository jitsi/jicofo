/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc
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
package org.jitsi.jicofo.xmpp

import org.jitsi.xmpp.extensions.DefaultPacketExtensionProvider
import org.jitsi.xmpp.extensions.colibri.ColibriStatsIqProvider
import org.jitsi.xmpp.extensions.colibri.ForcefulShutdownIqProvider
import org.jitsi.xmpp.extensions.colibri.GracefulShutdownIqProvider
import org.jitsi.xmpp.extensions.colibri2.IqProviderUtils
import org.jitsi.xmpp.extensions.health.HealthCheckIQProvider
import org.jitsi.xmpp.extensions.health.HealthStatusPacketExt
import org.jitsi.xmpp.extensions.jibri.JibriBusyStatusPacketExt
import org.jitsi.xmpp.extensions.jibri.JibriIq
import org.jitsi.xmpp.extensions.jibri.JibriIqProvider
import org.jitsi.xmpp.extensions.jibri.JibriStatusPacketExt
import org.jitsi.xmpp.extensions.jingle.JingleIQ
import org.jitsi.xmpp.extensions.jingle.JingleIQProvider
import org.jitsi.xmpp.extensions.jitsimeet.AudioMutedExtension
import org.jitsi.xmpp.extensions.jitsimeet.BridgeSessionPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIqProvider
import org.jitsi.xmpp.extensions.jitsimeet.FeatureExtension
import org.jitsi.xmpp.extensions.jitsimeet.FeaturesExtension
import org.jitsi.xmpp.extensions.jitsimeet.IceStatePacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.JitsiParticipantCodecList
import org.jitsi.xmpp.extensions.jitsimeet.JitsiParticipantRegionPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.JsonMessageExtension
import org.jitsi.xmpp.extensions.jitsimeet.LoginUrlIqProvider
import org.jitsi.xmpp.extensions.jitsimeet.LogoutIqProvider
import org.jitsi.xmpp.extensions.jitsimeet.StartMutedProvider
import org.jitsi.xmpp.extensions.jitsimeet.StatsId
import org.jitsi.xmpp.extensions.jitsimeet.UserInfoPacketExt
import org.jitsi.xmpp.extensions.jitsimeet.VideoMutedExtension
import org.jitsi.xmpp.extensions.rayo.RayoIqProvider
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.SASLAuthentication
import org.jivesoftware.smack.SmackConfiguration
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smackx.caps.EntityCapsManager

fun initializeSmack() {
    org.jitsi.xmpp.Smack.initialize(useJitsiXmppStringprep = XmppConfig.config.useJitsiJidValidation)

    EntityCapsManager.setDefaultEntityNode("http://jitsi.org/jicofo")

    // Default to 15 seconds, may be overriden by the different connections.
    SmackConfiguration.setDefaultReplyTimeout(15000)

    ReconnectionManager.setEnabledPerDefault(true)
    // Jicofo handles at most two connections and most of the time that is localhost and the number
    // of jicofo instances is small so we can afford to retry quickly.
    ReconnectionManager.setDefaultFixedDelay(2)

    // Smack uses SASL Mechanisms ANONYMOUS and PLAIN, but tries to authenticate with GSSAPI when it's offered
    // by the server. Disable GSSAPI.
    SASLAuthentication.unregisterSASLMechanism("org.jivesoftware.smack.sasl.javax.SASLGSSAPIMechanism")

    // Enable Stream Management
    XMPPTCPConnection.setUseStreamManagementResumptionDefault(true)
    XMPPTCPConnection.setUseStreamManagementDefault(true)

    registerXmppExtensions()
}

fun registerXmppExtensions() {
    // Constructors called to register extension providers
    ConferenceIqProvider()
    LoginUrlIqProvider()
    LogoutIqProvider()
    GracefulShutdownIqProvider.registerIQProvider()
    ForcefulShutdownIqProvider.registerIQProvider()
    ColibriStatsIqProvider()
    HealthCheckIQProvider.registerIQProvider()
    // ice-state
    ProviderManager.addExtensionProvider(
        IceStatePacketExtension.ELEMENT,
        IceStatePacketExtension.NAMESPACE,
        DefaultPacketExtensionProvider(IceStatePacketExtension::class.java)
    )
    // bridge-session
    ProviderManager.addExtensionProvider(
        BridgeSessionPacketExtension.ELEMENT,
        BridgeSessionPacketExtension.NAMESPACE,
        DefaultPacketExtensionProvider(BridgeSessionPacketExtension::class.java)
    )
    // Jibri IQs
    ProviderManager.addIQProvider(JibriIq.ELEMENT, JibriIq.NAMESPACE, JibriIqProvider())
    JibriStatusPacketExt.registerExtensionProvider()
    JibriBusyStatusPacketExt.registerExtensionProvider()
    HealthStatusPacketExt.registerExtensionProvider()
    // User info
    ProviderManager.addExtensionProvider(
        UserInfoPacketExt.ELEMENT,
        UserInfoPacketExt.NAMESPACE,
        DefaultPacketExtensionProvider(UserInfoPacketExt::class.java)
    )
    ProviderManager.addExtensionProvider(
        JitsiParticipantRegionPacketExtension.ELEMENT,
        JitsiParticipantRegionPacketExtension.NAMESPACE,
        DefaultPacketExtensionProvider(JitsiParticipantRegionPacketExtension::class.java)
    )
    ProviderManager.addExtensionProvider(
        StatsId.ELEMENT,
        StatsId.NAMESPACE,
        StatsId.Provider()
    )
    ProviderManager.addExtensionProvider(
        JitsiParticipantCodecList.ELEMENT,
        JitsiParticipantCodecList.NAMESPACE,
        DefaultPacketExtensionProvider(JitsiParticipantCodecList::class.java)
    )

    // register Jingle
    ProviderManager.addIQProvider(
        JingleIQ.ELEMENT,
        JingleIQ.NAMESPACE,
        JingleIQProvider()
    )
    ProviderManager.addExtensionProvider(
        JsonMessageExtension.ELEMENT,
        JsonMessageExtension.NAMESPACE,
        DefaultPacketExtensionProvider(JsonMessageExtension::class.java)
    )
    ProviderManager.addExtensionProvider(
        FeaturesExtension.ELEMENT,
        FeaturesExtension.NAMESPACE,
        DefaultPacketExtensionProvider(FeaturesExtension::class.java)
    )
    ProviderManager.addExtensionProvider(
        FeatureExtension.ELEMENT,
        FeatureExtension.NAMESPACE,
        DefaultPacketExtensionProvider(FeatureExtension::class.java)
    )
    RayoIqProvider().registerRayoIQs()
    org.jitsi.xmpp.Smack.registerMuteIqProviders()
    StartMutedProvider.registerStartMutedProvider()

    ProviderManager.addExtensionProvider(
        AudioMutedExtension.ELEMENT,
        AudioMutedExtension.NAMESPACE,
        DefaultPacketExtensionProvider(AudioMutedExtension::class.java)
    )

    ProviderManager.addExtensionProvider(
        VideoMutedExtension.ELEMENT,
        VideoMutedExtension.NAMESPACE,
        DefaultPacketExtensionProvider(VideoMutedExtension::class.java)
    )
    IqProviderUtils.registerProviders()
}
