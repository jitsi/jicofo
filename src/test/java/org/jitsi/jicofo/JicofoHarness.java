/*
 * Jicofo, the Jitsi Conference Focus.
 *
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
package org.jitsi.jicofo;

import com.typesafe.config.*;
import edu.umd.cs.findbugs.annotations.*;
import mock.*;
import mock.muc.*;
import org.jitsi.config.*;
import org.jitsi.jicofo.xmpp.*;

/**
 * Start jicofo services in a test environment. Note that some of the services are still used through static references,
 * so at most one harness instance should be used at a time.
 *
 * @author Pawel Domas
 */
@SuppressFBWarnings(
    value = "ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD",
    justification = "We assume that only one instance is used at a time!"
)
public class JicofoHarness
{
    public JicofoTestServices jicofoServices;

    public JicofoHarness()
    {
        init();
    }

    @SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    private void init()
    {
        System.setProperty("org.jitsi.jicofo.PING_INTERVAL", "0");
        System.setProperty(XmppClientConnectionConfig.legacyXmppDomainPropertyName, "test.domain.net");
        System.setProperty(XmppClientConnectionConfig.legacyDomainPropertyName, "test.domain.net");
        System.setProperty(XmppClientConnectionConfig.legacyUsernamePropertyName, "focus");

        // We don't want the jitsi-videobridge we load to try to use SCTP.
        System.setProperty("videobridge.sctp.enabled", "false");

        JitsiConfig.Companion.reloadNewConfig();

        // Prevent jetty from starting.
        String disableRestConfig = "jicofo.rest.port=-1\njicofo.rest.tls-port=-1";
        JitsiConfig.Companion.useDebugNewConfig(
                new TypesafeConfigSource(
                        "test config",
                        ConfigFactory.parseString(disableRestConfig).withFallback(ConfigFactory.load())));

        SmackKt.initializeSmack();
        jicofoServices = new JicofoTestServices();
        JicofoServices.jicofoServicesSingleton = jicofoServices;
    }

    public MockXmppProvider getXmppProvider()
    {
        return (MockXmppProvider) jicofoServices.getXmppServices().getClientConnection();
    }

    public void shutdown()
    {
        JicofoServices.jicofoServicesSingleton = null;
        jicofoServices.shutdown();

        System.clearProperty("org.jitsi.jicofo.PING_INTERVAL");
        System.clearProperty(XmppClientConnectionConfig.legacyXmppDomainPropertyName);
        System.clearProperty(XmppClientConnectionConfig.legacyDomainPropertyName);
        System.clearProperty(XmppClientConnectionConfig.legacyUsernamePropertyName);
        System.clearProperty("videobridge.sctp.enabled");

        JitsiConfig.Companion.reloadNewConfig();

        MockMultiUserChatOpSet.cleanMucSharing();
    }
}

