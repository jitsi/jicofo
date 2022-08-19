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
 */
package org.jitsi.jicofo.bridge;

import org.jitsi.utils.logging2.*;

import java.util.*;

/**
 * A {@link BridgeSelectionStrategy} implementation which keeps all
 * participants in a conference on the same bridge.
 */
public class SingleBridgeSelectionStrategy
    extends BridgeSelectionStrategy
{
    /**
     * The logger.
     */
    private final static Logger logger = new LoggerImpl(SingleBridgeSelectionStrategy.class.getName());

    /**
     * Default constructor.
     */
    public SingleBridgeSelectionStrategy()
    {}

    /**
     * {@inheritDoc}
     * </p>
     * Always selects the bridge already used by the conference.
     */
    @Override
    public Bridge doSelect(
            List<Bridge> bridges,
            Map<Bridge, Integer> conferenceBridges,
            String participantRegion)
    {
        if (conferenceBridges.size() == 0)
        {
            return leastLoadedInRegion(bridges, Collections.emptyMap(), participantRegion).orElseGet(
                    () -> leastLoaded(bridges, Collections.emptyMap(), participantRegion).orElse(null));
        }
        else if (conferenceBridges.size() != 1)
        {
            logger.error("Unexpected number of bridges with "
                             + "SingleBridgeSelectionStrategy: "
                             + conferenceBridges.size());
            return null;
        }

        Bridge bridge = conferenceBridges.keySet().stream().findFirst().get();
        if (!bridge.isOperational())
        {
            logger.error(
                "The conference already has a bridge, but it is not "
                    + "operational: bridge=" + bridge);
            return null;
        }

        return bridge;
    }
}
