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

    private MockCapsNode findFirstLevel(Jid name)
    {
        for (MockCapsNode node : childNodes)
        {
            if (node.getNodeName().equals(name))
            {
                return node;
            }
        }

        return null;
    }

    @Override
    public Set<Jid> getItems(Jid nodeName)
    {
        Set<Jid> result = new HashSet<>(childNodes.size());

        MockCapsNode node;
        if (nodeName.toString().endsWith(getNodeName().toString()))
        {
            node = this;
        }
        else
        {
            node = findFirstLevel(nodeName);
        }
        if (node != null)
        {
            for (MockCapsNode child : node.getChildNodes())
            {
                result.add(child.getNodeName());
            }
        }

        return result;
    }

    @Override
    public boolean hasFeatureSupport(Jid contactAddress, String[] features)
    {
        MockCapsNode node = findChild(contactAddress);
        if (node == null)
        {
            return false;
        }

        String[] nodeFeatures = node.getFeatures();

        for (String feature : features)
        {
            boolean found = false;
            for (String toCheck : nodeFeatures)
            {
                if (toCheck.equals(feature))
                {
                    found = true;
                    break;
                }
            }
            if (!found)
            {
                return false;
            }
        }
        return true;
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

    //@Override
    public boolean hasFeatureSupport(String Jid, String subnode,
                                     String[] features)
    {
        return false;
    }
}
