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

import org.jitsi.util.*;

import java.io.*;
import java.net.*;

/**
 * IQ sent to the focus in order to get the URL used for authentication with
 * external system.
 *
 * @author Pawel Domas
 */
public class LoginUrlIQ
    extends AbstractIQ
{
    public static final String NAMESPACE = ConferenceIq.NAMESPACE;

    public static final String ELEMENT_NAME = "login-url";

    /**
     * The name of the attribute that holds authentication URL value.
     */
    public static final String URL_ATTRIBUTE_NAME = "url";

    /**
     * The name of the attribute that carries the name of conference room
     * which will be used as authentication context.
     */
    public static final String ROOM_NAME_ATTR_NAME = "room";

    /**
     * The name of the attribute that indicates if carried URL is a popup one.
     * In other words if carried URL is intended to be opened in popup window.
     */
    public static final String POPUP_ATTR_NAME = "popup";

    /**
     * The name of the property that holds machine unique identifier.
     * It is used to distinguish sessions for the same user on different
     * machines.
     */
    public static final String MACHINE_UID_ATTR_NAME = "machine-uid";

    /**
     * The URL used for authentication with external system.
     */
    private String url;

    /**
     * The conference room name used as a context for authentication.
     * muc_room_name@muc.server.name
     */
    private String room;

    /**
     * Machine unique identifier used to distinguish sessions for the same
     * user on different machines.
     */
    private String machineUID;

    /**
     * Indicates if carried authentication URL should be opened in popup window.
     */
    private Boolean popup;

    /**
     * Creates new instance of {@link LoginUrlIQ}.
     */
    public LoginUrlIQ()
    {
        super(NAMESPACE, ELEMENT_NAME);
    }

    /**
     * Prints attributes in XML format to given <tt>StringBuilder</tt>.
     * @param out the <tt>StringBuilder</tt> instance used to construct XML
     *            representation of this element.
     */
    @Override
    protected void printAttributes(StringBuilder out)
    {
        if (!StringUtils.isNullOrEmpty(url))
        {
            try
            {
                out.append(URL_ATTRIBUTE_NAME)
                    .append("='").append(
                        URLEncoder.encode(url, "UTF-8")).append("' ");
            }
            catch (UnsupportedEncodingException e)
            {
                // If this happens will never work, so it's ok to crash the app
                throw new RuntimeException(e);
            }
        }

        // Room name
        printStrAttr(out, ROOM_NAME_ATTR_NAME, room);

        // Machine UID
        printStrAttr(out, MACHINE_UID_ATTR_NAME, machineUID);

        // Is it popup URL ?
        printObjAttr(out, POPUP_ATTR_NAME, popup);
    }

    /**
     * Returns the value of {@link #URL_ATTRIBUTE_NAME} attribute.
     */
    public String getUrl()
    {
        return url;
    }

    /**
     * Sets the value of {@link #URL_ATTRIBUTE_NAME} attribute.
     * @param url authentication URL value to be set on this IQ instance.
     */
    public void setUrl(String url)
    {
        try
        {
            this.url = URLDecoder.decode(url, "UTF-8");
        }
        catch (UnsupportedEncodingException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the value of {@link #ROOM_NAME_ATTR_NAME} attribute.
     */
    public String getRoom()
    {
        return room;
    }

    /**
     * Sets the value of {@link #ROOM_NAME_ATTR_NAME} attribute.
     * @param room the name of MUC room to be set on this instance.
     */
    public void setRoom(String room)
    {
        this.room = room;
    }

    /**
     * Returns nullable, <tt>Boolean</tt> value of
     * {@link LoginUrlIQ#POPUP_ATTR_NAME} attribute.
     */
    public Boolean getPopup()
    {
        return popup;
    }

    /**
     * Sets the value of {@link LoginUrlIQ#POPUP_ATTR_NAME}.
     * @param popup <tt>Boolean</tt> value for the popup attribute to be set
     *              or <tt>null</tt> if should be left unspecified.
     */
    public void setPopup(Boolean popup)
    {
        this.popup = popup;
    }

    /**
     * Returns machine unique identifier attribute value carried by this IQ
     * (if any).
     */
    public String getMachineUID()
    {
        return machineUID;
    }

    /**
     * Sets {@link #MACHINE_UID_ATTR_NAME} attribute value.
     * @param machineUID machine unique identifier value to set. It's used to
     *                   distinguish session for the same user on different
     *                   machines.
     */
    public void setMachineUID(String machineUID)
    {
        this.machineUID = machineUID;
    }
}
