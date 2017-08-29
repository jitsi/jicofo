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

import mock.xmpp.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

import static org.junit.Assert.*;

/**
 *
 */
@RunWith(JUnit4.class)
public class CapsMockTest
{
    @Test
    public void testMockCaps()
            throws XmppStringprepException
    {
        MockSetSimpleCapsOpSet mockCaps = new MockSetSimpleCapsOpSet(
                JidCreate.domainBareFrom("root"));

        MockCapsNode node = new MockCapsNode(
            JidCreate.from("node1"),
            new String[]{ "featureA", "featureB"});

        mockCaps.addChildNode(node);

        mockCaps.addChildNode(
            new MockCapsNode(
                    JidCreate.from("node2"),
                new String[]{ "featureC"}));

        mockCaps.addChildNode(
            new MockCapsNode(
                    JidCreate.from("node3"),
                new String[]{ "featureC"}));

        assertTrue(
            mockCaps.hasFeatureSupport(
                    JidCreate.from("node1"),
                new String[]{ "featureA", "featureB"}));

        Set<Jid> nodes = mockCaps.getItems(JidCreate.domainBareFrom("root"));
        assertEquals(3, nodes.size());
    }
}
