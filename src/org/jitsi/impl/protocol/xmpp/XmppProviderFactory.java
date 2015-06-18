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

import net.java.sip.communicator.service.protocol.*;

import org.osgi.framework.*;

import java.util.*;

/**
 * Protocol provider factory implementation ofr {@link XmppProtocolProvider}.
 *
 * @author Pawel Domas
 */
public class XmppProviderFactory
    extends ProtocolProviderFactory
{
    /**
     * Creates a new <tt>ProtocolProviderFactory</tt>.
     *
     * @param bundleContext the bundle context reference of the service
     * @param protocolName  the name of the protocol
     */
    protected XmppProviderFactory(
            BundleContext bundleContext,
            String protocolName)
    {
        super(bundleContext, protocolName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AccountID installAccount(String s,
                                    Map<String, String> stringStringMap)
        throws IllegalArgumentException, IllegalStateException,
               NullPointerException
    {
        throw new RuntimeException("Not implemented");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void modifyAccount(ProtocolProviderService protocolProviderService,
                              Map<String, String> stringStringMap)
        throws NullPointerException
    {
        throw new RuntimeException("Not implemented");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected AccountID createAccountID(String userId,
                                        Map<String, String> accountProperties)
    {
        return new XmppAccountID(userId, accountProperties);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ProtocolProviderService createService(String userID,
                                                    AccountID accountID)
    {
        return new XmppProtocolProvider(accountID);
    }
}
