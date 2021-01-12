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

import java.util.*;

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.recording.jibri.*;
import org.jitsi.utils.logging.*;

/**
 * Implements standard functionality of <tt>ProtocolProviderService</tt> in
 * order to make it easier for implementers to provide complete solutions while
 * focusing on protocol-specific details.
 *
 * @author Lyubomir Marinov
 */
public abstract class AbstractXmppProvider
    implements XmppProvider
{
    private static final Logger logger = Logger.getLogger(AbstractXmppProvider.class);

    /**
     * A list of all listeners registered for
     * <tt>RegistrationStateChangeEvent</tt>s.
     */
    private final List<RegistrationListener> registrationListeners = new ArrayList<>();

    private boolean registered = false;

    /**
     * Registers the specified listener with this provider so that it would
     * receive notifications on changes of its state or other properties such
     * as its local address and display name.
     *
     * @param listener the listener to register.
     */
    public void addRegistrationListener(@NotNull RegistrationListener listener)
    {
        synchronized(registrationListeners)
        {
            if (!registrationListeners.contains(listener))
            {
                registrationListeners.add(listener);
            }
        }
    }

    /**
     * Removes the specified registration state change listener so that it does
     * not receive any further notifications upon changes of the
     * RegistrationState of this provider.
     *
     * @param listener the listener to register for
     * <tt>RegistrationStateChangeEvent</tt>s.
     */
    public void removeRegistrationListener(@NotNull RegistrationListener listener)
    {
        synchronized(registrationListeners)
        {
            registrationListeners.remove(listener);
        }
    }

    public void fireRegistrationStateChanged(boolean registered)
    {
        RegistrationListener[] listeners;
        synchronized (registrationListeners)
        {
            listeners
                = registrationListeners.toArray(new RegistrationListener[registrationListeners.size()]);
        }

        for (RegistrationListener listener : listeners)
            try
            {
                listener.registrationChanged(registered);
            }
            catch (Throwable throwable)
            {
                logger.error(
                    "An error occurred while executing "
                        + "RegistrationStateChangeListener"
                        + "#registrationStateChanged"
                        + "(RegistrationStateChangeEvent) of "
                        + listener,
                    throwable);
            }
    }

    /**
     * Indicates whether or not this provider is registered
     *
     * @return <tt>true</tt> if the provider is currently registered and
     * <tt>false</tt> otherwise.
     */
    @Override
    public boolean isRegistered()
    {
        return registered;
    }

    protected void setRegistered(boolean registered)
    {
        if (this.registered != registered)
        {
            this.registered = registered;
            fireRegistrationStateChanged(registered);
        }
    }

    @Override
    public OperationSetJibri getJibriApi()
    {
        return null;
    }
}
