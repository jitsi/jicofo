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
 * The IQ send by the clients to the focus component in order to destroy
 * authentication session(logout).
 *
 * @author Pawel Domas
 */
public class LogoutIq
    extends AbstractIQ
{
    /**
     * XML element name of logout IQ.
     */
    public static final String ELEMENT_NAME = "logout";

    /**
     * XML namespace of logout IQ.
     */
    public static final String NAMESPACE = ConferenceIq.NAMESPACE;

    /**
     * The name of the attribute which holds the ID of authentication session.
     */
    public static final String SESSION_ID_ATTR = "session-id";

    /**
     * The name of the attribute which holds the URL which should be visited
     * to complete the logout process.
     */
    public static final String LOGOUT_URL_ATTR = "logout-url";

    /**
     * Authentication session ID.
     */
    private String sessionId;

    /**
     * The URL which should be visited by the user in order to complete the
     * logout process(optional).
     */
    private String logoutUrl;

    /**
     * Creates new instance of <tt>LogoutIq</tt>.
     */
    public LogoutIq()
    {
        super(NAMESPACE, ELEMENT_NAME);
    }

    /**
     * Returns the value of authentication session ID attribute.
     */
    public String getSessionId()
    {
        return sessionId;
    }

    /**
     * Sets the value of authentication session ID attribute.
     * @param sessionId the value which will be stored in session ID attribute.
     */
    public void setSessionId(String sessionId)
    {
        this.sessionId = sessionId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void printAttributes(StringBuilder out)
    {
        printStrAttr(out, SESSION_ID_ATTR, sessionId);

        if (!StringUtils.isNullOrEmpty(logoutUrl))
        {
            try
            {
                String encodedUrl = URLEncoder.encode(logoutUrl, "UTF-8");
                printStrAttr(out, LOGOUT_URL_ATTR, encodedUrl);
            }
            catch (UnsupportedEncodingException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Returns the value of logout URL attribute(optional).
     */
    public String getLogoutUrl()
    {
        return logoutUrl;
    }

    /**
     * Sets the value of logout URL attribute carried by this IQ(optional).
     * @param logoutUrl the URL which should be visited by the user in order
     *                  to complete the logout process.
     */
    public void setLogoutUrl(String logoutUrl)
    {
        try
        {
            this.logoutUrl = logoutUrl != null
                    ? URLDecoder.decode(logoutUrl,"UTF-8") : null;
        }
        catch (UnsupportedEncodingException e)
        {
            throw new RuntimeException(e);
        }
    }
}
