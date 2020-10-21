package org.jitsi.jicofo;

import org.junit.Assert;
import org.junit.Test;
import java.util.Map;
import java.util.HashMap;

public class JitsiMeetConfigTest {
    @Test
    public void testGetMinParticipants() {
        Map<String, String> properties = new HashMap<String, String> ();
        JitsiMeetConfig jitsiMeetConfig = new JitsiMeetConfig(properties);
        Assert.assertEquals(2, jitsiMeetConfig.getMinParticipants());
    }
}