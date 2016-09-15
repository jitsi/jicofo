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
package mock;

import mock.muc.*;
import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.jabber.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.osgi.framework.*;

import java.util.*;

/**
 * Registers mock protocol provider factories for SIP and XMPP.
 *
 * @author Pawel Domas
 */
public class MockActivator
    implements BundleActivator
{
    private ServiceRegistration<?> xmppRegistration;

    private ServiceRegistration<?> sipRegistration;

    private MockProtocolProviderFactory sipFactory;

    private MockProtocolProviderFactory xmppFactory;

    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        // FIXME: make sure that we're using interoperability layer
        AbstractSmackInteroperabilityLayer.setImplementationClass(
            SmackV3InteroperabilityLayer.class);

        XmppProtocolActivator.registerXmppExtensions();

        sipFactory
            = new MockProtocolProviderFactory(
                    bundleContext, ProtocolNames.SIP);

        xmppFactory
            = new MockProtocolProviderFactory(
                    bundleContext, ProtocolNames.JABBER);

        Hashtable<String, String> hashtable = new Hashtable<String, String>();

        // Register XMPP
        hashtable.put(ProtocolProviderFactory.PROTOCOL, ProtocolNames.JABBER);

        xmppRegistration = bundleContext.registerService(
            ProtocolProviderFactory.class.getName(),
            xmppFactory,
            hashtable);

        // Register SIP
        hashtable.put(ProtocolProviderFactory.PROTOCOL, ProtocolNames.SIP);

        sipRegistration = bundleContext.registerService(
            ProtocolProviderFactory.class.getName(),
            sipFactory,
            hashtable);
    }

    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        xmppFactory.stop();

        sipFactory.stop();

        if (xmppRegistration != null)
            xmppRegistration.unregister();

        if (sipRegistration != null)
            sipRegistration.unregister();

        MockMultiUserChatOpSet.cleanMucSharing();
    }
}
