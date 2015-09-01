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
package org.jitsi.protocol.xmpp;

import net.java.sip.communicator.service.protocol.*;

import java.util.*;

/**
 * Operation set exposes the functionality of node capabilities discovery.
 *
 * @author Pawel Domas
 */
public interface OperationSetSimpleCaps
    extends OperationSet
{
    /**
     * Returns the list of sub-nodes of given <tt>node</tt>.
     *
     * @param node the node for which child nodes will be discovered.
     *
     * @return the list of sub-nodes of given <tt>node</tt>.
     */
    Set<String> getItems(String node);

    /**
     * Check if given node supports specified feature set.
     *
     * @param node the node to be checked.
     * @param features the array of feature names to be checked.
     *
     * @return <tt>true</tt> if given node support all features specified in
     *         <tt>features</tt> array or <tt>false</tt> if at least on of
     *         the features is not supported.
     */
    boolean hasFeatureSupport(String node, String[] features);

    /**
     * Returns the list of features supported by given <tt>node</tt>. 
     * @param node XMPP address of the entity for which features wil be 
     *        discovered.
     * @return the list of features supported by given <tt>node</tt> or
     *         <tt>null</tt> if we have failed to obtain the list due to some
     *         errors.
     */
    List<String> getFeatures(String node);

    //boolean hasFeatureSupport(String node, String subnode, String[] features);
}
