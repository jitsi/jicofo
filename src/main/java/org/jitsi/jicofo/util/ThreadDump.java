/*
 * Copyright @ 2019-present 8x8, Inc.
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

import java.lang.management.*;

/**
 * Utility for dumping threads status to a string.
 */
public class ThreadDump
{
    /**
     * @return a string with the current threads state.
     */
    static public String takeThreadDump()
    {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos
            = threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds(), 100);
        StringBuilder dbg = new StringBuilder();

        for (ThreadInfo threadInfo : threadInfos)
        {
            dbg.append('"')
                .append(threadInfo.getThreadName())
                .append('"');

            Thread.State state = threadInfo.getThreadState();

            dbg.append("\n   java.lang.Thread.State: ")
                .append(state);

            if (threadInfo.getLockName() != null)
            {
                dbg.append(" on ")
                    .append(threadInfo.getLockName());
            }
            dbg.append('\n');

            StackTraceElement[] stackTraceElements
                = threadInfo.getStackTrace();
            for (int i = 0; i < stackTraceElements.length; i++)
            {
                StackTraceElement ste = stackTraceElements[i];
                dbg.append("\tat ")
                    .append(ste.toString())
                    .append('\n');
                if (i == 0 && threadInfo.getLockInfo() != null)
                {
                    Thread.State ts = threadInfo.getThreadState();
                    if (ts == Thread.State.BLOCKED
                            || ts == Thread.State.WAITING
                            || ts == Thread.State.TIMED_WAITING)
                    {
                        dbg.append("\t-  ")
                            .append(ts)
                            .append(" on ")
                            .append(threadInfo.getLockInfo())
                            .append('\n');
                    }
                }

                for (MonitorInfo mi
                        : threadInfo.getLockedMonitors())
                {
                    if (mi.getLockedStackDepth() == i) {
                        dbg.append("\t-  locked ")
                            .append(mi)
                            .append('\n');
                    }
                }
            }

            dbg.append("\n\n");
        }

        return dbg.toString();
    }
}
