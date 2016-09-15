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
package org.jitsi.jicofo.auth;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.util.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * Shibboleth implementation of {@link AuthenticationAuthority} interface.
 *
 * Authentication servlet {@link ShibbolethHandler} must be deployed under
 * the location secured by Shibboleth(called *login location*). When user wants
 * to login, the application retrieves login URL and redirects user to it(see
 * {@link #createLoginUrl(String, String, String, boolean)}. When user
 * attempts to access it will be asked for Shibboleth credentials. Once user
 * logs-in, request attributes will be filled by Shibboleth system including
 * 'email' which is treated as users identity. The servlet will bind
 * identity to new session ID which will be returned to the user. After user
 * has his session id it is stored in a cookie which can be used for
 * authenticating future requests.
 *
 * FIXME move to Shibboleth 'impl' package
 *
 * @author Pawel Domas
 */
public class ShibbolethAuthAuthority
    extends AbstractAuthAuthority
    implements AuthenticationAuthority
{
    /**
     * Value constant which should be passed as {@link
     * AuthBundleActivator#LOGIN_URL_PNAME} and {@link
     * AuthBundleActivator#LOGOUT_URL_PNAME} in order to use default
     * Shibboleth URLs for login and logout. It can not be skipped, because
     * Shibboleth will not be enabled otherwise.
     */
    public static final String DEFAULT_URL_CONST = "shibboleth:default";

    /**
     * Authentication URL pattern which uses {@link String#format(String,
     * Object...)} to insert string arguments into the request.<br/>
     * Arguments exposed to the service are:<br/>
     * %1$s - *machineUID* that is identifier of users machine which can be
     * used by the system to distinguish between session for the same login
     * on different machines.<br/>
     * %2$s - *usersJID* Jabber ID of the user who has requested the URL<br/>
     * %3$s - *roomName* full name of the conference MUC in the form of
     * "room@muc.server.net"<br/>
     * %4$s - *popup* indicates if this URL will be opened in a popup. In
     * this case session-id can be immediately passed to parent window using
     * CORS window messages.<br/>
     * Example:<br/>
     * 'https://external-authentication.server.net/login/?machineUID=%1$s
     * &room=%3$s&popup=%4$s'<br/>
     *
     */
    private String loginUrlPattern
        = "login/?machineUID=%1$s&room=%3$s&close=%4$s";

    /**
     * URL for logout location. Optionally session-id argument is
     * available:<br/>
     * %1$s - *session ID* authentication session identifier which will be
     * terminated.
     */
    private String logoutUrlPattern = "../Shibboleth.sso/Logout";

    /**
     * Creates new instance of <tt>ShibbolethAuthAuthority</tt> with default
     * login and logout URL locations.
     * @param disableAutoLogin disables auto login feature. Authentication
     * sessions are destroyed immediately when the conference ends.
     * @param authenticationLifetime specifies how long authentication sessions
     * will be stored in Jicofo's memory. Interval in milliseconds.

     */
    public ShibbolethAuthAuthority(boolean    disableAutoLogin,
                                   long       authenticationLifetime)
    {
        this(disableAutoLogin, authenticationLifetime,
            DEFAULT_URL_CONST, DEFAULT_URL_CONST);
    }

    /**
     * Creates new instance of {@link ShibbolethAuthAuthority}.
     * @param loginUrlPattern the pattern used for constructing external
     *        authentication URLs. See {@link #loginUrlPattern} for more info.
     *
     */
    public ShibbolethAuthAuthority(boolean    disableAutoLogin,
                                   long       authenticationLifetime,
                                   String     loginUrlPattern,
                                   String     logoutUrlPattern)
    {
        super(disableAutoLogin, authenticationLifetime);
        // Override authenticate URL ?
        if (!StringUtils.isNullOrEmpty(loginUrlPattern)
                && !DEFAULT_URL_CONST.equals(loginUrlPattern))
        {
            this.loginUrlPattern = loginUrlPattern;
        }
        // Override or disable logout URL
        if (!DEFAULT_URL_CONST.equals(logoutUrlPattern))
        {
            this.logoutUrlPattern = logoutUrlPattern;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isExternal()
    {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    public String createLoginUrl(String machineUID, String  userJid,
                                 String roomName,   boolean popup)
    {
        return String.format(
                loginUrlPattern, machineUID, userJid, roomName, popup);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String createLogoutUrl(String sessionId)
    {
        if (logoutUrlPattern == null)
        {
            return null;
        }
        // By default: "../Shibboleth.sso/Logout"
        return String.format(logoutUrlPattern, sessionId);
    }

    /**
     * Method called by the servlet in order to create new authentication
     * session.
     *
     * @param machineUID user's machine identifier that wil be used to
     *                   distinguish between the sessions for the same login
     *                   name on different machines.
     * @param authIdentity the identity obtained from external authentication
     *                     system that will be bound to the user's JID.
     * @param roomName the name of the conference room.
     * @param properties the map of Shibboleth attributes/headers to be logged.
     * @return <tt>true</tt> if user has been authenticated successfully or
     *         <tt>false</tt> if given token is invalid.
     */
    String authenticateUser(String machineUID, String authIdentity,
                            String roomName,   Map<String, String> properties)
    {
        synchronized (syncRoot)
        {
            AuthenticationSession session
                = findSessionForIdentity(machineUID, authIdentity);

            if (session == null)
            {
                session = createNewSession(
                    machineUID, authIdentity, roomName, properties);
            }

            return session.getSessionId();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected IQ processAuthLocked(ConferenceIq query, ConferenceIq response)
    {
        // FIXME this now looks like it could be merged with XMPP or moved to
        // abstract
        String room = query.getRoom();
        String peerJid = query.getFrom();

        String sessionId = query.getSessionId();
        AuthenticationSession session = getSession(sessionId);

        // Check for invalid session
        IQ error = verifySession(query);
        if (error != null)
        {
            return error;
        }

        // Authenticate JID with session
        if (session != null)
        {
            authenticateJidWithSession(session, peerJid, response);
        }

        return null;
    }
}
