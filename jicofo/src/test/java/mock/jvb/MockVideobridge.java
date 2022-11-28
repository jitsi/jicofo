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

import mock.xmpp.*;
import mock.xmpp.MockXmppConnection;
import org.jitsi.jicofo.mock.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.colibri2.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;

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

    private final MockXmppConnection connection;

    private MockColibri2Server bridge;

    private final ConferenceModifyIqHandler conferenceModifyIqGetHandler
            = new ConferenceModifyIqHandler(IQ.Type.get);
    private final ConferenceModifyIqHandler conferenceModifyIqSetHandler
            = new ConferenceModifyIqHandler(IQ.Type.set);

    public MockVideobridge(MockXmppConnection connection)
    {
        this.connection = connection;
    }

    public void start()
    {
        bridge = new MockColibri2Server();
        connection.registerIQRequestHandler(conferenceModifyIqGetHandler);
        connection.registerIQRequestHandler(conferenceModifyIqSetHandler);
    }

    public void stop()
    {
        connection.unregisterIQRequestHandler(conferenceModifyIqGetHandler);
        connection.unregisterIQRequestHandler(conferenceModifyIqSetHandler);

        bridge.stop();
    }

    private class ConferenceModifyIqHandler extends AbstractIqRequestHandler
    {
        ConferenceModifyIqHandler(IQ.Type type)
        {
            super(ConferenceModifyIQ.ELEMENT, ConferenceModifyIQ.NAMESPACE, type, Mode.sync);
        }

        @Override
        public IQ handleIQRequest(IQ iqRequest)
        {
            try
            {
                IQ confResult = bridge.handleConferenceModifyIq((ConferenceModifyIQ) iqRequest);
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
}
