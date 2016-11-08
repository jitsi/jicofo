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
package org.jitsi.jicofo.util;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.event.*;
import org.jitsi.osgi.*;
import org.osgi.framework.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Class listens for {@link EventFactory#CONFERENCE_ROOM_TOPIC} and exposes
 * the functionality of waiting for 'n' conference created events.
 *
 * @author Pawel Domas
 */
public class ConferenceRoomListener
    extends EventHandlerActivator
{
    private List<Event> rooms = new LinkedList<>();

    private CountDownLatch roomCounter;

    public ConferenceRoomListener()
    {
        super(new String[]{ EventFactory.CONFERENCE_ROOM_TOPIC });
    }

    public void await(BundleContext bc,
                      int roomCount, long timeout, TimeUnit timeUnit)
        throws Exception
    {
        roomCounter = new CountDownLatch(roomCount);

        start(bc);

        try
        {
            roomCounter.await(timeout, timeUnit);
        }
        finally
        {
            stop(bc);
        }
    }

    @Override
    public void handleEvent(Event event)
    {
        rooms.add(event);

        roomCounter.countDown();
    }
}
