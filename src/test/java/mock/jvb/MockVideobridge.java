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
package mock.jvb;

import org.jitsi.protocol.xmpp.*;
import org.jitsi.shutdown.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.version.*;
import org.jitsi.videobridge.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

/**
 *
 * @author Pawel Domas
 */
public class MockVideobridge
{
    /**
     * The logger
     */
    private static final Logger logger = new LoggerImpl(MockVideobridge.class.getName());

    private final XmppConnection connection;

    private final Jid bridgeJid;

    private Videobridge bridge;

    private final ColibriConferenceIqHandler confIqGetHandler = new ColibriConferenceIqHandler(IQ.Type.get);

    private final ColibriConferenceIqHandler confIqSetHandler = new ColibriConferenceIqHandler(IQ.Type.set);

    public MockVideobridge(XmppConnection connection, Jid bridgeJid)
    {
        this.connection = connection;
        this.bridgeJid = bridgeJid;
    }

    public void start()
    {
        bridge = new Videobridge(
                new org.jitsi.videobridge.xmpp.XmppConnection(),
                new ShutdownServiceImpl(),
                new VersionImpl("jvb", 2, 72));
        bridge.start();

        connection.registerIQRequestHandler(confIqGetHandler);
        connection.registerIQRequestHandler(confIqSetHandler);
    }

    public void stop()
    {
        connection.unregisterIQRequestHandler(confIqGetHandler);
        connection.unregisterIQRequestHandler(confIqSetHandler);

        bridge.stop();
    }

    private class ColibriConferenceIqHandler extends AbstractIqRequestHandler
    {
        ColibriConferenceIqHandler(IQ.Type type)
        {
            super(ColibriConferenceIQ.ELEMENT_NAME,
                ColibriConferenceIQ.NAMESPACE,
                type,
                Mode.sync
            );
        }

        @Override
        public IQ handleIQRequest(IQ iqRequest)
        {
            try
            {
                IQ confResult = bridge.handleColibriConferenceIQ((ColibriConferenceIQ) iqRequest);
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
        return bridge.getConferences().size();
    }
}
