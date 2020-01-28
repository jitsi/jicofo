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
package org.jitsi.jicofo.rest;

import org.glassfish.hk2.utilities.binding.*;
import org.glassfish.jersey.server.*;
import org.glassfish.jersey.test.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.testutils.*;
import org.jitsi.jicofo.util.*;
import org.json.simple.*;
import org.json.simple.parser.*;
import org.junit.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;
import org.mockito.internal.stubbing.answers.*;

import javax.servlet.http.*;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.*;
import java.time.*;
import java.util.*;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static org.mockito.Mockito.*;

public class HealthTest extends JerseyTest
{
    private FocusManagerProvider focusManagerProvider;
    private FocusManager focusManager;
    private FakeClock clock;
    private JitsiMeetServices jitsiMeetServices;
    private BridgeSelector bridgeSelector;

    @Override
    protected Application configure()
    {
        focusManagerProvider = mock(FocusManagerProvider.class);
        focusManager = mock(FocusManager.class);
        when(focusManagerProvider.get()).thenReturn(focusManager);

        jitsiMeetServices = mock(JitsiMeetServices.class);
        bridgeSelector = mock(BridgeSelector.class);
        when(focusManager.getJitsiMeetServices()).thenReturn(jitsiMeetServices);
        when(jitsiMeetServices.getBridgeSelector()).thenReturn(bridgeSelector);

        clock = spy(FakeClock.class);

        enable(TestProperties.LOG_TRAFFIC);
        enable(TestProperties.DUMP_ENTITY);
        return new ResourceConfig() {
            {
                register(new AbstractBinder()
                {
                    @Override
                    protected void configure()
                    {
                        bind(focusManagerProvider).to(FocusManagerProvider.class);
                        bind(clock).to(Clock.class);
                    }
                });
                register(Health.class);
            }
        };
    }

    @Test
    public void noCachedResponse() throws Exception
    {
        setupSuccessfulHealthCheck();
        Response resp = target("/about/health").request().get();
        verify(focusManager).conferenceRequest(any(), any(), any(), eq(false));
        assertEquals(HttpServletResponse.SC_OK, resp.getStatus());
    }

    @Test
    public void cachedResponse() throws Exception
    {
        setupSuccessfulHealthCheck();
        target("/about/health").request().get();
        clock.elapse(Duration.ofSeconds(5));
        Response resp = target("/about/health").request().get();
        // We should have only called the check method the first time
        verify(focusManager, times(1)).conferenceRequest(any(), any(), any(), eq(false));
        assertEquals(HttpServletResponse.SC_OK, resp.getStatus());
    }

    @Test
    public void cachedResponseExpired() throws Exception
    {
        setupSuccessfulHealthCheck();
        target("/about/health").request().get();
        // Long enough for the cached response to expire
        clock.elapse(Duration.ofSeconds(30));
        Response resp = target("/about/health").request().get();
        verify(focusManager, times(2)).conferenceRequest(any(), any(), any(), eq(false));
        assertEquals(HttpServletResponse.SC_OK, resp.getStatus());
    }

    @Test
    public void healthCheckTimeout() throws XmppStringprepException
    {
        when(focusManager.isHealthChecksDebugEnabled()).thenReturn(true);
        setupHealthCheckTimeout();
        Response resp = target("/about/health").request().get();
        assertEquals(HttpServletResponse.SC_OK, resp.getStatus());
    }

    @Test
    public void requestActiveJvbs() throws Exception
    {
        setupActiveJvbs("one@one.com/jvb", "two@two.com/jvb");
        setupSuccessfulHealthCheck();
        Response resp = target("/about/health").queryParam("list_jvb", true).request().get();
        // Should still do the health check
        verify(focusManager).conferenceRequest(any(), any(), any(), eq(false));
        assertEquals(HttpServletResponse.SC_OK, resp.getStatus());
        JSONObject respJson
            = (JSONObject)new JSONParser().parse(resp.readEntity(String.class));
        assertNotNull(respJson.get("jvbs"));
        List jvbs = (List)respJson.get("jvbs");
        assertEquals(2, jvbs.size());
    }

    @Test
    public void requestActiveJvbsHealthCheckError() throws Exception
    {
        setupActiveJvbs("one@one.com/jvb", "two@two.com/jvb");
        setupHealthCheckError();
        Response resp = target("/about/health").queryParam("list_jvb", true).request().get();
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getStatus());
        JSONObject respJson
            = (JSONObject)new JSONParser().parse(resp.readEntity(String.class));
        assertNotNull(respJson.get("jvbs"));
        List jvbs = (List)respJson.get("jvbs");
        assertEquals(2, jvbs.size());
    }

    private void setupActiveJvbs(String... ids) throws XmppStringprepException
    {
        List<Jid> activeJvbs = new ArrayList<>();
        for (String id : ids)
        {
            activeJvbs.add(JidCreate.from(id));
        }

        when(bridgeSelector.listActiveJVBs()).thenReturn(activeJvbs);
    }

    private void setupHealthCheckError()
    {
        when(jitsiMeetServices.getMucService()).thenThrow(new RuntimeException());
    }

    private void setupSuccessfulHealthCheck()
    {
        Jid mucService = null;
        try
        {
            mucService = JidCreate.from("test@domain.com");
        } catch (XmppStringprepException e)
        {
            e.printStackTrace();
        }
        when(jitsiMeetServices.getMucService()).thenReturn(mucService);
        when(focusManager.getConference(any())).thenReturn(null);
        try
        {
            when(focusManager.conferenceRequest(any(), any(), any(), eq(false))).thenReturn(true);
        } catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void setupHealthCheckTimeout() throws XmppStringprepException
    {
        final Jid mucService = JidCreate.from("test@domain.com");
        when(jitsiMeetServices.getMucService())
            .thenAnswer(new AnswersWithDelay(4000L, new Returns(mucService)));
        when(focusManager.getConference(any())).thenReturn(null);
        try
        {
            when(focusManager.conferenceRequest(any(), any(), any(), eq(false))).thenReturn(true);
        } catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
