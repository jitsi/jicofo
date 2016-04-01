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
package org.jitsi.protocol.xmpp.util;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import java.util.*;

/**
 * Class contains utilities specific to transport signaling in Jitsi Meet
 * conferences.
 *
 * @author Pawel Domas
 */
public class TransportSignaling
{
    /**
     * Merges source transport into destination by copying information
     * important for Jitsi Meet transport signaling.
     * @param dst destination <tt>IceUdpTransportPacketExtension</tt>
     * @param src source <tt>IceUdpTransportPacketExtension</tt> from which
     *               all relevant information will be merged into <tt>dst</tt>
     */
    static public void mergeTransportExtension(
            IceUdpTransportPacketExtension    dst,
            IceUdpTransportPacketExtension    src)
    {
        Objects.requireNonNull(dst, "dst");
        Objects.requireNonNull(src, "src");

        // Attributes
        for (String attribute : src.getAttributeNames())
        {
            dst.setAttribute(attribute, src.getAttribute(attribute));
        }

        // RTCP-MUX
        if (src.isRtcpMux() && !dst.isRtcpMux())
        {
            dst.addChildExtension(new RtcpmuxPacketExtension());
        }

        // Candidates
        for (CandidatePacketExtension c : src.getCandidateList())
        {
            dst.addCandidate(c);
        }

        // DTLS fingerprint
        DtlsFingerprintPacketExtension srcDtls
            = src.getFirstChildOfType(DtlsFingerprintPacketExtension.class);
        if (srcDtls != null)
        {
            // Remove the current one if any
            DtlsFingerprintPacketExtension dstDtls
                = dst.getFirstChildOfType(
                        DtlsFingerprintPacketExtension.class);
            if (dstDtls != null)
            {
                dst.removeChildExtension(dstDtls);
            }
            // Set the fingerprint from the source
            dst.addChildExtension(srcDtls);
        }
    }
}
