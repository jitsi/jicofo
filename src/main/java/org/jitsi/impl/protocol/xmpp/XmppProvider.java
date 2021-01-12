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
package org.jitsi.impl.protocol.xmpp;

import java.util.concurrent.*;

import net.java.sip.communicator.service.protocol.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.protocol.xmpp.*;
import org.jivesoftware.smack.*;

/**
 * Based on Jitsi's {@code ProtocolProviderService}, simplified for the needs of jicofo.
 */
public interface XmppProvider
{
    /**
     * Starts the registration process. Connection details such as
     * registration server, user name/number are provided through the
     * configuration service through implementation specific properties.
     */
    void register(ScheduledExecutorService executorService);

    /**
     * Ends the registration of this protocol provider with the current
     * registration service.
     */
    void unregister();

    /**
     * Indicates whether or not this provider is registered
     * @return true if the provider is currently registered and false otherwise.
     */
    boolean isRegistered();

    /**
     * Registers the specified listener with this provider so that it would
     * receive notifications on changes of its state or other properties such
     * as its local address and display name.
     * @param listener the listener to register.
     */
    void addRegistrationListener(RegistrationListener listener);

    /**
     * Removes the specified listener.
     * @param listener the listener to remove.
     */
    void removeRegistrationListener(RegistrationListener listener);

    /**
     * Returns the operation set corresponding to the specified class or
     * <tt>null</tt> if this operation set is not supported by the provider
     * implementation.
     *
     * @param <T> the type which extends <tt>OperationSet</tt> and which is to
     * be retrieved
     * @param opsetClass the <tt>Class</tt>  of the operation set that we're
     * looking for.
     * @return returns an OperationSet of the specified <tt>Class</tt> if the
     * underlying implementation supports it or null otherwise.
     */
    <T extends OperationSet> T getOperationSet(Class<T> opsetClass);

    XmppConnectionConfig getConfig();

    XmppConnection getXmppConnection();
    XMPPConnection getXmppConnectionRaw();
}

