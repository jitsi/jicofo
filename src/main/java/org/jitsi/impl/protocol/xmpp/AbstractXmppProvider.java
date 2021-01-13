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

import net.java.sip.communicator.service.protocol.*;
import org.jetbrains.annotations.*;
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

    /**
     * The hashtable with the operation sets that we support locally.
     */
    private final Map<String, OperationSet> supportedOperationSets = new Hashtable<>();

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

    /**
     * Adds a specific <tt>OperationSet</tt> implementation to the set of
     * supported <tt>OperationSet</tt>s of this instance. Serves as a type-safe
     * wrapper around {@link #supportedOperationSets} which works with class
     * names instead of <tt>Class</tt> and also shortens the code which performs
     * such additions.
     *
     * @param <T> the exact type of the <tt>OperationSet</tt> implementation to
     * be added
     * @param opsetClass the <tt>Class</tt> of <tt>OperationSet</tt> under the
     * name of which the specified implementation is to be added
     * @param opset the <tt>OperationSet</tt> implementation to be added
     */
    protected <T extends OperationSet> void addOperationSet(Class<T> opsetClass, T opset)
    {
        supportedOperationSets.put(opsetClass.getName(), opset);
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
     * Returns the operation set corresponding to the specified class or null if
     * this operation set is not supported by the provider implementation.
     *
     * @param <T> the exact type of the <tt>OperationSet</tt> that we're looking
     * for
     * @param opsetClass the <tt>Class</tt> of the operation set that we're
     * looking for.
     * @return returns an <tt>OperationSet</tt> of the specified <tt>Class</tt>
     * if the underlying implementation supports it; <tt>null</tt>, otherwise.
     */
    @SuppressWarnings("unchecked")
    public <T extends OperationSet> T getOperationSet(Class<T> opsetClass)
    {
        return (T) supportedOperationSets.get(opsetClass.getName());
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
}
