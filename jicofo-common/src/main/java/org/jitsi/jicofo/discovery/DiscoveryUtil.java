/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
package org.jitsi.jicofo.discovery;

import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smackx.disco.*;
import org.jivesoftware.smackx.disco.packet.*;
import org.jxmpp.jid.*;

import java.util.*;
import java.util.stream.*;

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
    private final static Logger logger = new LoggerImpl(DiscoveryUtil.class.getName());

    /**
     * Audio RTP feature name.
     */
    public final static String FEATURE_AUDIO = "urn:xmpp:jingle:apps:rtp:audio";

    /**
     * Video RTP feature name.
     */
    public final static String FEATURE_VIDEO = "urn:xmpp:jingle:apps:rtp:video";

    /**
     * DTLS/SCTP feature name.
     */
    public final static String FEATURE_SCTP = "urn:xmpp:jingle:transports:dtls-sctp:1";

    /**
     * RTX (RFC4588) support.
     */
    public final static String FEATURE_RTX = "urn:ietf:rfc:4588";

    /**
     * Support for RTCP REMB.
     */
    public final static String FEATURE_REMB = "http://jitsi.org/remb";

    /**
     * Support for transport-cc.
     */
    public final static String FEATURE_TCC = "http://jitsi.org/tcc";

    /**
     * Source name signalling required for the multi-stream mode.
     */
    public final static String FEATURE_SOURCE_NAMES = "http://jitsi.org/source-name";

    /**
     * Rewrite SSRCs to limit the number of transceivers required.
     */
    public final static String FEATURE_SSRC_REWRITING = "http://jitsi.org/ssrc-rewriting";

    /**
     * Supports receiving multiple video streams.
     */
    public final static String FEATURE_RECEIVE_MULTIPLE_STREAMS = "http://jitsi.org/receive-multiple-video-streams";

    /**
     * Jingle sources encoded as JSON.
     */
    public final static String FEATURE_JSON_SOURCES = "http://jitsi.org/json-encoded-sources";

    public final static String FEATURE_OPUS_RED = "http://jitsi.org/opus-red";

    /**
     * A namespace for detecting whether a participant (jigasi users) can be
     * muted.
     */
    public final static String FEATURE_AUDIO_MUTE = "http://jitsi.org/protocol/audio-mute";

    private static final List<String> defaultFeatures = List.of(
            FEATURE_AUDIO,
            FEATURE_VIDEO,
            FEATURE_SCTP);

    /**
     * Returns default participant feature set(all features).
     */
    static public List<String> getDefaultParticipantFeatureSet()
    {
        return new ArrayList<>(defaultFeatures);
    }

    /**
     * Gets the list of features supported by participant. If we fail to
     * obtain it due to network failure default feature list is returned.
     * @param address XMPP address of the participant.
     */
    public static @NotNull List<String> discoverParticipantFeatures(@NotNull XmppProvider xmppProvider,
        EntityFullJid address)
    {
        XMPPConnection xmppConnection = xmppProvider.getXmppConnection();
        if (!xmppConnection.isConnected())
        {
            logger.error("XMPP not connected " + xmppProvider);
            return getDefaultParticipantFeatureSet();
        }
        ServiceDiscoveryManager discoveryManager = ServiceDiscoveryManager.getInstanceFor(xmppConnection);
        if (discoveryManager == null)
        {
            logger.error("Service discovery not supported by " + xmppProvider);
            return getDefaultParticipantFeatureSet();
        }

        long start = System.currentTimeMillis();


        List<String> participantFeatures = null;
        try
        {
            DiscoverInfo info = discoveryManager.discoverInfo(address);
            if (info != null)
            {
                participantFeatures = info.getFeatures()
                        .stream()
                        .map(DiscoverInfo.Feature::getVar)
                        .collect(Collectors.toList());
            }
        }
        catch (Exception e)
        {
            logger.warn(String.format( "Failed to discover features for %s: %s", address, e.getMessage()));
        }

        long tookMillis = System.currentTimeMillis() - start;
        if (participantFeatures == null)
        {
            logger.warn("Failed to discover features for "+ address + " assuming default feature set. Took " +
                    "" + tookMillis + " ms.");

            return getDefaultParticipantFeatureSet();
        }
        logger.info("Discovered features for " + address + " in " + tookMillis + " ms.");
        if (logger.isDebugEnabled())
        {
            StringBuilder sb
                    = new StringBuilder(address)
                    .append(", features: ")
                    .append(String.join(", ", participantFeatures))
                    .append(", in: ")
                    .append(tookMillis);
            logger.debug(sb);
        }

        return participantFeatures;
    }
}
