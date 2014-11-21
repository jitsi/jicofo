package mock.xmpp;

import java.util.*;

/**
 *
 */
public class MockCapsNode
{
    private final String nodeName;

    private final String[] features;

    protected List<MockCapsNode> childNodes = new ArrayList<MockCapsNode>();

    public MockCapsNode(String nodeName, String[] features)
    {
        this.nodeName = nodeName;
        this.features = features;
    }

    public String getNodeName()
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

    public MockCapsNode findChild(String name)
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
