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
package mock.xmpp;

import org.jitsi.protocol.xmpp.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 *
 */
public class MockSetSimpleCapsOpSet
    extends MockCapsNode
    implements OperationSetSimpleCaps
{
    private long discoveryDelay = 0;

    public MockSetSimpleCapsOpSet(Jid domain)
    {
        super(domain, new String[]{});
    }

    public void addDiscoveryDelay(long millis)
    {
        this.discoveryDelay = millis;
    }

    @Override
    public List<String> getFeatures(Jid node)
    {
        if (discoveryDelay > 0)
        {
            try
            {
                Thread.sleep(discoveryDelay);
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }

        MockCapsNode capsNode = findChild(node);
        if (capsNode == null)
        {
            return null;
        }

        return Arrays.asList(capsNode.getFeatures());
    }
}
