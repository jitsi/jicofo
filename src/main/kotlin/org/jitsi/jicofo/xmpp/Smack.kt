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
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.colibri.ColibriIQProvider
import org.jitsi.xmpp.extensions.health.HealthCheckIQProvider
import org.jitsi.xmpp.extensions.health.HealthStatusPacketExt
import org.jitsi.xmpp.extensions.jibri.JibriBusyStatusPacketExt
import org.jitsi.xmpp.extensions.jibri.JibriIq
import org.jitsi.xmpp.extensions.jibri.JibriIqProvider
import org.jitsi.xmpp.extensions.jibri.JibriStatusPacketExt
import org.jitsi.xmpp.extensions.jingle.JingleIQ
import org.jitsi.xmpp.extensions.jingle.JingleIQProvider
import org.jitsi.xmpp.extensions.jitsimeet.BridgeSessionPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.ConferenceIqProvider
import org.jitsi.xmpp.extensions.jitsimeet.IceStatePacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.LoginUrlIqProvider
import org.jitsi.xmpp.extensions.jitsimeet.LogoutIqProvider
import org.jitsi.xmpp.extensions.jitsimeet.RegionPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.StatsId
import org.jitsi.xmpp.extensions.jitsimeet.TranscriptionRequestExtension
import org.jitsi.xmpp.extensions.jitsimeet.TranscriptionStatusExtension
import org.jitsi.xmpp.extensions.jitsimeet.UserInfoPacketExt
import org.jivesoftware.smack.SmackConfiguration
import org.jivesoftware.smack.parsing.ExceptionLoggingCallback
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smackx.bytestreams.socks5.Socks5Proxy

fun initializeSmack() {
    SmackConfiguration.setDefaultReplyTimeout(15000)
    // if there is a parsing error, do not break the connection to the server(the default behaviour) as we need it for
    // the other conferences.
    SmackConfiguration.setDefaultParsingExceptionCallback(ExceptionLoggingCallback())

    Socks5Proxy.setLocalSocks5ProxyEnabled(false)

    registerXmppExtensions()
}

fun registerXmppExtensions() {
    // Constructors called to register extension providers
    ConferenceIqProvider()
    LoginUrlIqProvider()
    LogoutIqProvider()
    ColibriIQProvider()
    HealthCheckIQProvider.registerIQProvider()
    // ice-state
    ProviderManager.addExtensionProvider(
        IceStatePacketExtension.ELEMENT_NAME,
        IceStatePacketExtension.NAMESPACE,
        DefaultPacketExtensionProvider(IceStatePacketExtension::class.java)
    )
    // bridge-session
    ProviderManager.addExtensionProvider(
        BridgeSessionPacketExtension.ELEMENT_NAME,
        BridgeSessionPacketExtension.NAMESPACE,
        DefaultPacketExtensionProvider(BridgeSessionPacketExtension::class.java)
    )
    // Jibri IQs
    ProviderManager.addIQProvider(JibriIq.ELEMENT_NAME, JibriIq.NAMESPACE, JibriIqProvider())
    JibriStatusPacketExt.registerExtensionProvider()
    JibriBusyStatusPacketExt.registerExtensionProvider()
    HealthStatusPacketExt.registerExtensionProvider()
    // User info
    ProviderManager.addExtensionProvider(
        UserInfoPacketExt.ELEMENT_NAME,
        UserInfoPacketExt.NAMESPACE,
        DefaultPacketExtensionProvider(UserInfoPacketExt::class.java)
    )
    ProviderManager.addExtensionProvider(
        RegionPacketExtension.ELEMENT_NAME,
        RegionPacketExtension.NAMESPACE,
        DefaultPacketExtensionProvider(RegionPacketExtension::class.java)
    )
    ProviderManager.addExtensionProvider(
        StatsId.ELEMENT_NAME,
        StatsId.NAMESPACE,
        StatsId.Provider()
    )

    // Add the extensions used for handling the inviting of transcriber
    ProviderManager.addExtensionProvider(
        TranscriptionRequestExtension.ELEMENT_NAME,
        TranscriptionRequestExtension.NAMESPACE,
        DefaultPacketExtensionProvider(TranscriptionRequestExtension::class.java)
    )
    ProviderManager.addExtensionProvider(
        TranscriptionStatusExtension.ELEMENT_NAME,
        TranscriptionStatusExtension.NAMESPACE,
        DefaultPacketExtensionProvider(TranscriptionStatusExtension::class.java)
    )

    // Register Colibri
    ProviderManager.addIQProvider(
        ColibriConferenceIQ.ELEMENT_NAME,
        ColibriConferenceIQ.NAMESPACE,
        ColibriIQProvider()
    )
    // register Jingle
    ProviderManager.addIQProvider(
        JingleIQ.ELEMENT_NAME,
        JingleIQ.NAMESPACE,
        JingleIQProvider()
    )
}
