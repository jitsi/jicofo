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
package org.jitsi.jicofo.event;

import org.jitsi.eventadmin.*;

import java.util.*;

/**
 * A utility class with static methods which initialize <tt>Event</tt> instances
 * with pre-determined fields.
 *
 * @author Boris Grozev
 * @author Pawel Domas
 */
public class EventFactory
    extends AbstractEventFactory
{
    /**
     * The name of the key for authentication session ID.
     */
    public static final String AUTH_SESSION_ID_KEY = "auth_session_id";

    /**
     * The name of the topic of an "authentication session destroyed" event.
     */
    public static final String AUTH_SESSION_DESTROYED_TOPIC
        = "org/jitsi/jicofo/AUTH_SESSION_DESTROYED";

    /**
     * Creates new "authentication session destroyed" event.
     *
     * @param sessionId authentication session identifier string.
     *
     * @return created "authentication session destroyed" <tt>Event</tt>.
     */
    public static Event authSessionDestroyed(String sessionId)
    {
        Dictionary<String, Object> props = new Hashtable<>(1);

        props.put(AUTH_SESSION_ID_KEY, sessionId);

        return new Event(AUTH_SESSION_DESTROYED_TOPIC, props);
    }
}
