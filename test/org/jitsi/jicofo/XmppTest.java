package org.jitsi.jicofo;

import mock.*;
import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.osgi.*;
import org.jitsi.jicofo.xmpp.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import org.xmpp.packet.IQ;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 *
 */
@RunWith(JUnit4.class)
public class XmppTest
{
    static OSGiHandler osgi = new OSGiHandler();

    @BeforeClass
    public static void setUpClass()
        throws InterruptedException
    {
        OSGi.setUseMockProtocols(true);

        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
    {
        osgi.shutdown();
    }

    @Test
    public void testAllocateConference()
        throws Exception
    {
        String roomName = "testRoom";

        FocusComponent focusComponent
            = MockMainMethodActivator.getFocusComponent();

        ConferenceIq conferenceIq = new ConferenceIq();

        conferenceIq.setRoom(roomName);

        IQ result
            = focusComponent.handleIQSet(
                IQUtils.convert(conferenceIq));

        assertNotNull(result);

        org.jivesoftware.smack.packet.IQ response =  IQUtils.convert(result);
        assertTrue(response instanceof ConferenceIq);


    }
}
