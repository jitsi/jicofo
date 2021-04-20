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
package org.jitsi.jicofo;

import mock.jvb.*;
import mock.xmpp.*;
import mock.xmpp.colibri.*;
import org.jitsi.jicofo.codec.*;
import org.jitsi.protocol.xmpp.colibri.exception.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Here we test the multithreading of colibri channels allocation. What we want
 * to have is to allow all threads to send their request at the same time and
 * wait for their responses independently. The only exception is when the
 * conference does not exist yet. In this case the first thread to send its
 * request is considered a conference creator and all other threads are
 * suspended until it finishes its job. Once we have the conference ID all
 * threads are allowed to go through.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class ColibriThreadingTest
{
    private JicofoHarness harness = new JicofoHarness();

    private static MockPeerAllocator findCreator(
            AllocThreadingTestColibriConference    colibriConf,
            List<MockPeerAllocator>                 allocators)
        throws InterruptedException
    {
        String conferenceCreator = colibriConf.obtainConferenceCreator();
        for (MockPeerAllocator allocator : allocators)
        {
            if (allocator.endpointId.equals(conferenceCreator))
            {
                return allocator;
            }
        }
        return null;
    }

    @After
    public void tearDown()
    {
        harness.shutdown();
    }

    /**
     * Here we test successful scenario of thread burst trying to allocate
     * channels at the same time.
     *
     * @throws InterruptedException
     */
    @Test
    public void testColibriMultiThreading()
        throws Exception
    {
        Jid mockBridgeJid = JidCreate.from("jvb.example.com");
        MockVideobridge mockBridge = new MockVideobridge(new MockXmppConnection(mockBridgeJid));
        mockBridge.start();

        AllocThreadingTestColibriConference colibriConf =
                new AllocThreadingTestColibriConference(
                        harness.jicofoServices.getXmppServices().getClientConnection().getXmppConnection());

        colibriConf.setJitsiVideobridge(mockBridgeJid);
        colibriConf.setName(JidCreate.entityBareFrom("foo@bar.com/zzz"));

        colibriConf.blockConferenceCreator(true);
        colibriConf.blockResponseReceive(true);

        MockPeerAllocator[] allocators = new MockPeerAllocator[20];

        List<String> endpointList = new ArrayList<>(allocators.length);

        for (int i=0; i < allocators.length; i++)
        {
            String endpointId = "peer" + i;
            allocators[i] = new MockPeerAllocator(endpointId, colibriConf);
            endpointList.add(endpointId);

            allocators[i].runChannelAllocation();
        }

        MockPeerAllocator creator = findCreator(colibriConf, Arrays.asList(allocators));

        assertNotNull(creator);
        assertEquals(0, colibriConf.allocRequestsSentCount());

        colibriConf.resumeConferenceCreate();


        creator.join();

        // All responses are blocked - here we make sure that all threads have
        // sent their requests
        List<String> requestsToBeSent = new ArrayList<>(endpointList);
        while (!requestsToBeSent.isEmpty())
        {
            String endpoint = colibriConf.nextRequestSent(5);
            if (endpoint == null)
            {
                fail("Endpoints that have failed to send their request: " + requestsToBeSent);
            }
            else
            {
                requestsToBeSent.remove(endpoint);
            }
        }

        colibriConf.resumeResponses();

        // Now wait for all responses to be received
        List<String> responsesToReceive = new ArrayList<>(endpointList);
        while (!responsesToReceive.isEmpty())
        {
            String endpoint = colibriConf.nextResponseReceived(5);
            if (endpoint == null)
            {
                fail("Endpoints that have failed to send their request: " + requestsToBeSent);
            }
            else
            {
                responsesToReceive.remove(endpoint);
            }
        }

        // Wait for all to finish
        for (MockPeerAllocator allocator : allocators)
        {
            allocator.join();
        }

        mockBridge.stop();
    }

    /**
     * Default config.
     */
    private static final JitsiMeetConfig config = new JitsiMeetConfig(new HashMap<>());

    static List<ContentPacketExtension> createContents()
    {
        OfferOptions offerOptions = new OfferOptions();
        OfferOptionsKt.applyConstraints(offerOptions, config);
        offerOptions.setRtx(false);
        offerOptions.setSctp(false);

        return JingleOfferFactory.INSTANCE.createOffer(offerOptions);
    }

    static class MockPeerAllocator
    {
        private final String endpointId;

        private final ColibriConference colibriConference;

        private Thread thread;

        private boolean working;

        public MockPeerAllocator(String            endpointId,
                                 ColibriConference colibriConference)
        {
            this.endpointId = endpointId;
            this.colibriConference = colibriConference;
        }

        synchronized public void runChannelAllocation()
        {
            working = true;

            this.thread = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        colibriConference.createColibriChannels(endpointId, null, true, createContents());
                    }
                    catch (ColibriException e)
                    {
                        e.printStackTrace();
                    }
                    finally
                    {
                        synchronized (MockPeerAllocator.this)
                        {
                            working = false;
                            MockPeerAllocator.this.notifyAll();
                        }
                    }
                }
            }, endpointId + "ChannelAllocatorThread");

            this.thread.start();
        }

        synchronized public void join()
        {
            while (working)
            {
                try
                {
                    MockPeerAllocator.this.wait();
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException("Interrupted");
                }
            }
        }
    }
}
