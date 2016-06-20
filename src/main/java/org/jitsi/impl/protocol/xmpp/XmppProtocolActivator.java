/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.impl.protocol.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.health.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jitsimeet.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.jabber.*;

import org.jitsi.impl.protocol.xmpp.extensions.*;

import org.jivesoftware.smack.*;

import org.osgi.framework.*;

import java.util.*;

/**
 * Bundle activator for {@link XmppProtocolProvider}.
 *
 * @author Pawel Domas
 */
public class XmppProtocolActivator
    implements BundleActivator
{
    private ServiceRegistration<?> focusRegistration;

    static BundleContext bundleContext;

    /**
     * Registers PacketExtension providers used by Jicofo
     */
    static public void registerXmppExtensions()
    {
        // FIXME: make sure that we're using interoperability layer
        AbstractSmackInteroperabilityLayer.setImplementationClass(
            SmackV3InteroperabilityLayer.class);
        AbstractSmackInteroperabilityLayer smackInterOp
            = AbstractSmackInteroperabilityLayer.getInstance();

        // Constructors called to register extension providers
        new ConferenceIqProvider();
        // Colibri
        new ColibriIQProvider();
        // HealthChecks
        HealthCheckIQProvider.registerIQProvider();
        // Jibri IQs
        smackInterOp.addIQProvider(
                JibriIq.ELEMENT_NAME, JibriIq.NAMESPACE, new JibriIqProvider());
        JibriStatusPacketExt.registerExtensionProvider();
        // User info
        smackInterOp.addExtensionProvider(
                UserInfoPacketExt.ELEMENT_NAME,
                UserInfoPacketExt.NAMESPACE,
                new DefaultPacketExtensionProvider<>(UserInfoPacketExt.class));
        // <videomuted> element from jitsi-meet presence
        smackInterOp.addExtensionProvider(
                VideoMutedExtension.ELEMENT_NAME,
                VideoMutedExtension.NAMESPACE,
                new DefaultPacketExtensionProvider<>(
                        VideoMutedExtension.class));

        // Override original Smack Version IQ class
        AbstractSmackInteroperabilityLayer.getInstance()
            .addIQProvider(
                    "query", "jabber:iq:version",
                    org.jitsi.jicofo.discovery.Version.class);
    }

    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        XmppProtocolActivator.bundleContext = bundleContext;

        SmackConfiguration.setPacketReplyTimeout(15000);

        registerXmppExtensions();

        XmppProviderFactory focusFactory
            = new XmppProviderFactory(
                    bundleContext, ProtocolNames.JABBER);
        Hashtable<String, String> hashtable = new Hashtable<>();

        // Register XMPP
        hashtable.put(ProtocolProviderFactory.PROTOCOL,
                      ProtocolNames.JABBER);

        focusRegistration = bundleContext.registerService(
            ProtocolProviderFactory.class.getName(),
            focusFactory,
            hashtable);
    }

    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (focusRegistration != null)
            focusRegistration.unregister();
    }
}
