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
package mock.jvb;

import org.jitsi.osgi.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.health.*;

import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.*;

import org.jitsi.nlj.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;

import org.jxmpp.jid.*;
import org.osgi.framework.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 *
 * @author Pawel Domas
 */
public class MockVideobridge
    implements BundleActivator
{
    /**
     * The logger
     */
    private static final Logger logger
        = Logger.getLogger(MockVideobridge.class);

    private final XmppConnection connection;

    private final Jid bridgeJid;

    private Videobridge bridge;

    private boolean returnServerError = false;

    private ColibriConferenceIqHandler confIqGetHandler
            = new ColibriConferenceIqHandler(IQ.Type.get);

    private ColibriConferenceIqHandler confIqSetHandler
            = new ColibriConferenceIqHandler(IQ.Type.set);

    private HealthCheckIqHandler healthCheckIqHandler
            = new HealthCheckIqHandler();

    public MockVideobridge(XmppConnection connection,
                           Jid bridgeJid)
    {
        this.connection = connection;
        this.bridgeJid = bridgeJid;
    }

    public void start(BundleContext bc)
    {
        bridge = new Videobridge();
        bridge.start(bc);

        connection.registerIQRequestHandler(confIqGetHandler);
        connection.registerIQRequestHandler(confIqSetHandler);
        connection.registerIQRequestHandler(healthCheckIqHandler);
    }

    @Override
    public void stop(BundleContext bundleContext)
    {
        connection.unregisterIQRequestHandler(confIqGetHandler);
        connection.unregisterIQRequestHandler(confIqSetHandler);
        connection.unregisterIQRequestHandler(healthCheckIqHandler);

        bridge.stop(bundleContext);
    }

    private class ColibriConferenceIqHandler extends AbstractIqRequestHandler
    {
        ColibriConferenceIqHandler(IQ.Type type)
        {
            super(ColibriConferenceIQ.ELEMENT_NAME,
                    ColibriConferenceIQ.NAMESPACE,
                    type,
                    Mode.sync);
        }

        @Override
        public IQ handleIQRequest(IQ iqRequest)
        {
            if (isReturnServerError())
            {
                return IQ.createErrorResponse(
                        iqRequest,
                        XMPPError.getBuilder(
                                XMPPError.Condition.internal_server_error));
            }

            try
            {
                IQ confResult = bridge.handleColibriConferenceIQ(
                        (ColibriConferenceIQ) iqRequest);
                confResult.setTo(iqRequest.getFrom());
                confResult.setStanzaId(iqRequest.getStanzaId());
                return confResult;
            }
            catch (Exception e)
            {
                logger.error("JVB internal error!", e);
                return null;
            }
        }
    }

    private class HealthCheckIqHandler extends AbstractIqRequestHandler
    {
        HealthCheckIqHandler()
        {
            super(HealthCheckIQ.ELEMENT_NAME,
                    HealthCheckIQ.NAMESPACE,
                    IQ.Type.get,
                    Mode.sync);
        }

        @Override
        public IQ handleIQRequest(IQ iqRequest)
        {
            if (isReturnServerError())
            {
                return IQ.createErrorResponse(
                        iqRequest,
                        XMPPError.getBuilder(
                                XMPPError.Condition.internal_server_error));
            }

            try
            {
                IQ healthResult = bridge.handleHealthCheckIQ(
                        (HealthCheckIQ) iqRequest);
                healthResult.setTo(iqRequest.getFrom());
                healthResult.setStanzaId(iqRequest.getStanzaId());
                return healthResult;
            }
            catch (Exception e)
            {
                logger.error("JVB internal error!", e);
                return null;
            }
        }
    }

    public List<RtpEncodingDesc> getSimulcastEncodings(
            String confId, String endpointId)
    {
        Conference conference = bridge.getConference(confId);
        AbstractEndpoint endpoint = conference.getEndpoint(endpointId);

        MediaSourceDesc[] sources = endpoint.getMediaSources();

        if (ArrayUtils.isNullOrEmpty(sources))
            return new ArrayList<>();

        RtpEncodingDesc[] encodings = sources[0].getRtpEncodings();
        if (ArrayUtils.isNullOrEmpty(encodings))
            return new ArrayList<>();

        return Arrays.asList(encodings);
    }

    /**
     * Return all conferences that were not created by health checks
     * @return a list of the currently active conferences that were not created by
     * health checks
     */
    public List<Conference> getNonHealthCheckConferences()
    {
        // Filter out conferences created for health checks
        return bridge.getConferences().stream()
                .filter(Conference::includeInStatistics)
                .collect(Collectors.toList());
    }

    public int getEndpointCount()
    {
        return bridge.getConferences().stream()
                .mapToInt(Conference::getEndpointCount)
                .sum();
    }

    public Jid getBridgeJid()
    {
        return bridgeJid;
    }

    public int getConferenceCount()
    {
        return getNonHealthCheckConferences().size();
    }

    public boolean isReturnServerError()
    {
        return returnServerError;
    }

    public void setReturnServerError(boolean returnServerError)
    {
        this.returnServerError = returnServerError;
    }
}
