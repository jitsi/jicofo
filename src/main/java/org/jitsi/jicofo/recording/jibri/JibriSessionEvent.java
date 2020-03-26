/*
 * Copyright @ 2018 - present 8x8, Inc.
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
 *
 */
package org.jitsi.jicofo.recording.jibri;

import org.jitsi.eventadmin.*;

import java.util.*;

/**
 * Events emitted for {@link JibriSession}.
 */
public class JibriSessionEvent extends Event
{
    /**
     * Event emitted when a {@link JibriSession} fails to start.
     */
    public static final String FAILED_TO_START
        = "org/jitsi/jicofo/jibri/session/FAILED_TO_START";

    /**
     * A key holding the {@link Type}.
     */
    private static final String TYPE_KEY = "TYPE";

    /**
     * Creates new {@link JibriSessionEvent} with the {@link #FAILED_TO_START}
     * topic.
     *
     * @param type - Tells which {@link Type} of Jibri session is the new event
     *               for.
     */
    static public JibriSessionEvent newFailedToStartEvent(Type type)
    {
        Dictionary<String, Object> props =  new Hashtable<>(1);

        props.put(TYPE_KEY, type);

        return new JibriSessionEvent(FAILED_TO_START, props);
    }

    /**
     * Creates new instance.
     * @param topic - The event's topic.
     * @param properties - The event's properties.
     */
    public JibriSessionEvent(String topic, Dictionary properties)
    {
        super(topic, properties);
    }

    /**
     * @return {@code true} if the event comes for a SIP Jibri session or
     * {@code false} otherwise.
     */
    public Type getType()
    {
        Object type = getProperty(TYPE_KEY);

        return type instanceof Type ? (Type) type : null;
    }

    /**
     * A Jibri session type.
     */
    public enum Type {
        /**
         * SIP Jibri call.
         */
        SIP_CALL,
        /**
         * Jibri live streaming session.
         */
        LIVE_STREAMING,
        /**
         * Jibri recording session.
         */
        RECORDING
    }
}
