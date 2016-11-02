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

import java.util.concurrent.*;

/**
 * A thread factory that creates daemon threads.
 *
 * @author Pawel Domas
 */
public class DaemonThreadFactory
    implements ThreadFactory
{
    /**
     * {@inheritDoc}
     */
    @Override
    public Thread newThread(Runnable r)
    {
        Thread t = Executors.defaultThreadFactory().newThread(r);
        if (!t.isDaemon())
        {
            t.setDaemon(true);
        }
        return t;
    }
}
