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

import org.jxmpp.jid.*;

import java.util.*;
import java.util.concurrent.*;

/**
 *
 */
public class MockCapsNode
{
    private final Jid nodeName;

    private final String[] features;

    protected List<MockCapsNode> childNodes
        = new CopyOnWriteArrayList<>();

    public MockCapsNode(Jid nodeName, String[] features)
    {
        this.nodeName = nodeName;
        this.features = features;
    }

    public Jid getNodeName()
    {
        return nodeName;
    }

    public String[] getFeatures()
    {
        return features;
    }

    public void addChildNode(MockCapsNode node)
    {
        childNodes.add(node);
    }

    public Collection<MockCapsNode> getChildNodes()
    {
        return Collections.unmodifiableCollection(childNodes);
    }

    public MockCapsNode findChild(Jid name)
    {
        for (MockCapsNode node : childNodes)
        {
            if (node.getNodeName().equals(name))
            {
                return node;
            }
            else
            {
                MockCapsNode child = node.findChild(name);
                if (child != null)
                {
                    return child;
                }
            }
        }

        return null;
    }
}
