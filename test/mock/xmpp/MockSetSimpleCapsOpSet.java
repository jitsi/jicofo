package mock.xmpp;

import org.jitsi.protocol.xmpp.*;

import java.util.*;

/**
 *
 */
public class MockSetSimpleCapsOpSet
    extends MockCapsNode
    implements OperationSetSimpleCaps
{
    public MockSetSimpleCapsOpSet(String domain)
    {
        super(domain, new String[]{});
    }

    private MockCapsNode findFirstLevel(String name)
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
    public List<String> getItems(String nodeName)
    {
        ArrayList<String> result = new ArrayList<String>(childNodes.size());

        MockCapsNode node;
        if (nodeName.endsWith(getNodeName()))
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
    public boolean hasFeatureSupport(String contactAddress, String[] features)
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
    public List<String> getFeatures(String node)
    {
        MockCapsNode capsNode = findChild(node);
        if (capsNode == null)
        {
            return null;
        }

        return Arrays.asList(capsNode.getFeatures());
    }

    //@Override
    public boolean hasFeatureSupport(String node, String subnode,
                                     String[] features)
    {
        return false;
    }
}
