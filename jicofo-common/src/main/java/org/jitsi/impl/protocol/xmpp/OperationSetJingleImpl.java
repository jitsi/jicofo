/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

import org.jitsi.protocol.xmpp.*;
import org.jivesoftware.smack.*;

/**
 * Implementation of {@link OperationSetJingleImpl} for
 * {@link XmppProviderImpl}.
 *
 * @author Pawel Domas
 */
public class OperationSetJingleImpl
    extends AbstractOperationSetJingle
{
    private final XmppProviderImpl xmppProvider;

    OperationSetJingleImpl(XmppProviderImpl xmppProvider)
    {
        this.xmppProvider = xmppProvider;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected AbstractXMPPConnection getConnection()
    {
        return xmppProvider.getXmppConnection();
    }
}
