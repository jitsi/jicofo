/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.util;

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;
import org.jitsi.protocol.xmpp.*;

import java.util.*;

/**
 * Utility class for feature discovery.
 *
 * @author Pawel Domas
 */
public class DiscoveryUtil
{
    /**
     * The logger
     */
    private final static Logger logger 
        = Logger.getLogger(DiscoveryUtil.class);

    /**
     * Audio RTP feature name.  
     */
    public final static String FEATURE_AUDIO
            = "urn:xmpp:jingle:apps:rtp:audio";

    /**
     * Video RTP feature name.  
     */
    public final static String FEATURE_VIDEO
            = "urn:xmpp:jingle:apps:rtp:video";

    /**
     * ICE feature name.  
     */
    public final static String FEATURE_ICE
            = "urn:xmpp:jingle:transports:ice-udp:1";

    /**
     * DTLS/SCTP feature name.  
     */
    public final static String FEATURE_SCTP
            = "urn:xmpp:jingle:transports:dtls-sctp:1";

    /**
     * RTCP mux feature name.  
     */
    public final static String FEATURE_RTCP_MUX = "urn:ietf:rfc:5761";

    /**
     * RTP bundle feature name. 
     */
    public final static String FEATURE_RTP_BUNDLE = "urn:ietf:rfc:5888";

    /**
     * Gets the list of features supported by participant. If we fail to 
     * obtain it due to network failure default feature list is returned. 
     * @param protocolProvider protocol provider service instance that will 
     *        be used for discovery.
     * @param address XMPP address of the participant.
     */
    public static List<String> discoverParticipantFeatures
        (ProtocolProviderService protocolProvider, String address)
    {
        OperationSetSimpleCaps disco 
            = protocolProvider.getOperationSet(OperationSetSimpleCaps.class);
        if (disco == null)
        {
            logger.error(
                "Service discovery not supported by " + protocolProvider);
            return getDefaultParticipantFeatureSet();
        }
        
        // Discover participant feature set
        List<String> participantFeatures = disco.getFeatures(address);
        if (participantFeatures == null)
        {
            logger.error(
                "Failed to discover features for "+ address 
                        + " assuming default feature set.");
            
            return getDefaultParticipantFeatureSet();
        }

        logger.info(address +", features: ");
        for (String feature : participantFeatures)
        {
            logger.info(feature);
        }

        return participantFeatures;
    }

    /**
     * Returns default participant feature set.
     */
    static public List<String> getDefaultParticipantFeatureSet()
    {
        ArrayList<String> features = new ArrayList<String>(4);
        features.add(FEATURE_AUDIO);
        features.add(FEATURE_VIDEO);
        features.add(FEATURE_ICE);
        features.add(FEATURE_SCTP);
        return features;
    }
}
