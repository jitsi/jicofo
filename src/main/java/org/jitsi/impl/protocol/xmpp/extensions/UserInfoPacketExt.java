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
package org.jitsi.impl.protocol.xmpp.extensions;

import net.java.sip.communicator.impl.protocol.jabber.extensions.*;
import org.jitsi.util.*;

/**
 * Packet extension included in Jitsi-Meet MUC presence to signal extra
 * information about the participant.
 *
 * @author Pawel Domas
 */
public class UserInfoPacketExt
    extends AbstractPacketExtension
{
    /**
     * XML element name of this packet extension.
     */
    public static final String ELEMENT_NAME = "userinfo";

    /**
     * Name space of start muted packet extension.
     */
    public static final String NAMESPACE = "http://jitsi.org/jitmeet/userinfo";

    /**
     * The name of the "robot" attribute which indicates whether or not given
     * user is a robot(SIP gateway, recorder component etc.).
     */
    public static final String ROBOT_ATTRIBUTE_NAME = "robot";

    /**
     * Creates an {@link UserInfoPacketExt} instance.
     *
     */
    public UserInfoPacketExt()
    {
        super(NAMESPACE, ELEMENT_NAME);
    }

    /**
     * Returns <tt>true</tt> if the user is considered a "robot"(recorder
     * component, SIP gateway etc.), <tt>false</tt> if it's not and
     * <tt>null</tt> if the attribute value is not defined.
     */
    public Boolean isRobot()
    {
        String isRobotStr = getAttributeAsString(ROBOT_ATTRIBUTE_NAME);
        if (!StringUtils.isNullOrEmpty(isRobotStr))
        {
            return Boolean.parseBoolean(isRobotStr);
        }
        else
        {
            return null;
        }
    }

    /**
     * Sets new value for the "robot" attribute.
     * @param isRobot <tt>true</tt> if the user is considered a robot or
     * <tt>false</tt> otherwise. Pass <tt>null</tt> to remove the attribute.
     * @see {@link #ROBOT_ATTRIBUTE_NAME}
     */
    public void setIsRobot(Boolean isRobot)
    {
        setAttribute(ROBOT_ATTRIBUTE_NAME, isRobot);
    }
}
