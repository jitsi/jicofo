package org.jitsi.jicofo.bridge;

import org.jitsi.utils.logging.*;

import java.util.*;

/**
 * Represents an algorithm for bridge selection.
 */
abstract class BridgeSelectionStrategy
{
    /**
     * The logger.
     */
    private final static Logger logger
            = Logger.getLogger(BridgeSelectionStrategy.class);

    /**
     * The local region of the jicofo instance.
     */
    private String localRegion = null;

    /**
     * Selects a bridge to be used for a new participant in a conference.
     *
     * @param bridges the list of bridges to select from.
     * @param conferenceBridges the bridges already in use by the conference
     * for which for which a bridge is to be selected.
     * @param participantRegion the region of the participant for which
     * a bridge is to be selected.
     * @return the selected bridge, or {@code null} if no bridge is
     * available.
     */
    public Bridge select(
            List<Bridge> bridges,
            List<Bridge> conferenceBridges,
            String participantRegion,
            boolean allowMultiBridge)
    {
        if (conferenceBridges.isEmpty())
        {
            Bridge bridge
                = selectInitial(bridges, participantRegion);
            if (logger.isDebugEnabled())
            {
                logger.debug("Selected initial bridge " + bridge);
            }
            return bridge;
        }
        else
        {
            if (!allowMultiBridge
                || conferenceBridges.get(0).getRelayId() == null)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug(
                        "Existing bridge does not have a relay, will not " +
                            "consider other bridges.");
                }

                return conferenceBridges.get(0);
            }

            return doSelect(
                    bridges, conferenceBridges, participantRegion);
        }
    }

    /**
     * Selects a bridge to be used for a new participant in a conference,
     * assuming that no other bridge is used by the conference (i.e. this
     * is the initial selection of a bridge for the conference).
     *
     * @param bridges the list of bridges to select from.
     * @param participantRegion the region of the participant for which a
     * bridge is to be selected.
     * @return the selected bridge, or {@code null} if no bridge is
     * available.
     */
    private Bridge selectInitial(List<Bridge> bridges,
                                 String participantRegion)
    {
        Bridge bridge = null;

        // Prefer a bridge in the participant's region.
        if (participantRegion != null)
        {
            bridge = findFirstOperationalInRegion(bridges, participantRegion);
        }

        // Otherwise, prefer a bridge in the local region.
        if (bridge == null)
        {
            bridge = findFirstOperationalInRegion(bridges, localRegion);
        }

        // Otherwise, just find the first operational bridge.
        if (bridge == null)
        {
            bridge = findFirstOperationalInRegion(bridges, null);
        }

        return bridge;
    }

    /**
     * Returns the first operational bridge in the given list which matches
     * the given region (if the given regio is {@code null} the region is
     * not matched).
     *
     * @param bridges
     * @param region
     * @return
     */
    private Bridge findFirstOperationalInRegion(
            List<Bridge> bridges,
            String region)
    {
        return bridges.stream()
                .filter(Bridge::isOperational)
                .filter(
                    b -> region == null || region.equals(b.getRegion()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Selects a bridge to be used for a new participant in a conference.
     *
     * @param bridges the list of bridges to select from.
     * @param conferenceBridges the list of bridges currently used by the
     * conference.
     * @param participantRegion the region of the participant for which a
     * bridge is to be selected.
     * @return the selected bridge, or {@code null} if no bridge is
     * available.
     */
    abstract Bridge doSelect(
            List<Bridge> bridges,
            List<Bridge> conferenceBridges,
            String participantRegion);

    String getLocalRegion()
    {
        return localRegion;
    }

    void setLocalRegion(String localRegion)
    {
        this.localRegion = localRegion;
    }
}
