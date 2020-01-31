package org.jitsi.jicofo.bridge;

import org.jitsi.utils.logging.*;

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
    private final static Logger logger
            = Logger.getLogger(BridgeSelectionStrategy.class);

    /**
     * Default constructor.
     */
    SingleBridgeSelectionStrategy()
    {}

    /**
     * {@inheritDoc}
     * </p>
     * Always selects the bridge already used by the conference.
     */
    @Override
    public Bridge doSelect(
            List<Bridge> bridges,
            List<Bridge> conferenceBridges,
            String participantRegion)
    {
        if (conferenceBridges.size() == 0)
        {
            return super.doSelect(bridges, conferenceBridges, participantRegion);
        }
        else if (conferenceBridges.size() != 1)
        {
            logger.error("Unexpected number of bridges with "
                             + "SingleBridgeSelectionStrategy: "
                             + conferenceBridges.size());
            return null;
        }

        Bridge bridge = conferenceBridges.get(0);
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
