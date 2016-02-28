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
package org.jitsi.jicofo;

import org.jitsi.jicofo.auth.*;
import org.jitsi.jicofo.log.*;
import org.jitsi.jicofo.reservation.*;
import org.jitsi.jicofo.xmpp.*;
import org.junit.runner.*;
import org.junit.runners.*;

/**
 * FIXME: tests work when launched individually, there are still init/deinit
 *        problems to be fixed when they run one after another
 */
@RunWith(Suite.class)
@Suite.SuiteClasses(
    {
        AuthenticationAuthorityTest.class,
        AuthEventsTest.class,
        ConferenceJsonTest.class,
        ConferenceIqProviderTest.class,
        JireconIqProviderTest.class,
        JvbDoctorTest.class,
        MuteIqProviderTest.class,
        AdvertiseSSRCsTest.class,
        BridgeSelectorTest.class,
        BundleTest.class,
        ColibriTest.class,
        ColibriThreadingTest.class,
        LeakingRoomsTest.class,
        MediaSSRCGroupMapTest.class,
        MediaSSRCMapTest.class,
        MockTest.class,
        PubSubBridgeSelectorTest.class,
        RolesTest.class,
        XmppTest.class,
        ShutdownTest.class
    })
public class FocusTestSuite
{
}
