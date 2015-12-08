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

import com.auth0.jwt.*;

import net.java.sip.communicator.util.*;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.StringUtils;
import org.jivesoftware.smack.packet.*;

import java.security.*;
import java.util.*;


/**
 * Authentication authority implementation for JWT tokens. In order to activate
 * set the following config properties:<br/>
 * <li>org.jitsi.jicofo.auth.jwt.APP_ID</li> - specifies application ID which is
 * reflected in 'iss' JWT claim.
 * <li>org.jitsi.jicofo.auth.jwt.SECRET</li> - is shared secret used to
 * authenticate the token
 *
 * @author Pawel Domas
 */
public class JWTAuthAuthority
    extends AbstractAuthAuthority
{
    /**
     * The logger.
     */
    private final static Logger logger
        = Logger.getLogger(JWTAuthAuthority.class);

    /**
     * The name of config property that specifies the application ID('iss' JWT
     * claim).
     */
    public static final String JWT_APP_ID = "org.jitsi.jicofo.auth.jwt.APP_ID";

    /**
     * The name of the property that configures shared secret used to
     * authenticate the token.
     */
    public static final String JWT_SECRET = "org.jitsi.jicofo.auth.jwt.SECRET";

    /**
     * Shared JWT secret.
     */
    private final String secret;

    /**
     * Application id('issuer JWT claim).
     */
    private final String appId;

    /**
     * Creates new instance of <tt>JWTAuthAuthority</tt>.
     * @param appId the application ID(issuer JWT claim).
     * @param secret shared secret used to verify and authenticate JWT tokens.
     */
    public JWTAuthAuthority(String appId, String secret)
    {
        if (StringUtils.isNullOrEmpty(appId))
            throw new IllegalArgumentException("Invalid app ID: " + appId);

        if (StringUtils.isNullOrEmpty(secret))
            throw new IllegalArgumentException("Invalid secret: " + secret);

        this.appId = appId;
        this.secret = secret;
    }

    private String getBareJid(String fullJid)
    {
        int slashIdx = fullJid.indexOf("/");
        if (slashIdx != -1)
        {
            return fullJid.substring(0, slashIdx);
        }
        else
        {
            // Bare already ?
            return fullJid;
        }
    }

    @Override
    protected IQ processAuthLocked(ConferenceIq query, ConferenceIq response)
    {
        String peerJid = query.getFrom();
        String sessionId = query.getSessionId();

        AuthenticationSession session = getSession(sessionId);

        if (session != null)
        {
            IQ error = verifySession(query);
            if (error == null)
            {
                authenticateJidWithSession(session, peerJid, response);
            }
            return error;
        }

        String bareJid = getBareJid(peerJid);
        String machineUID = query.getMachineUID();
        if (StringUtils.isNullOrEmpty(machineUID))
        {
            return ErrorFactory.createNotAcceptableError(query,
                "Missing mandatory attribute '"
                    + ConferenceIq.MACHINE_UID_ATTR_NAME + "'");
        }

        // In JWT auth session is identified by the token
        try
        {
            byte[] secretBytes = secret.getBytes();

            JWTVerifier verifier
                = new JWTVerifier(secretBytes, null, appId);

            Map<String,Object> decodedPayload = verifier.verify(sessionId);

            String queryRoom = query.getRoom();
            if (StringUtils.isNullOrEmpty(queryRoom))
            {
                return ErrorFactory.createNotAcceptableError(
                    query, "no room in the query");
            }
            queryRoom = MucUtil.extractName(queryRoom);

            String room = (String) decodedPayload.get("room");
            if (!queryRoom.equals(room))
            {
                return ErrorFactory.createNotAuthorizedError(query,
                    "invalid room: " + room);
            }

            session
                = createNewSession(
                    machineUID, bareJid, query.getRoom(), null);

            authenticateJidWithSession(session, peerJid, response);

            return null;
        }
        catch (JWTVerifyException e)
        {
            return
                ErrorFactory.createNotAuthorizedError(
                        query, e.getMessage());
        }
        catch (SignatureException e)
        {
            return
                ErrorFactory.createNotAuthorizedError(
                        query, e.getMessage());
        }
        catch (Exception exc)
        {
            logger.error(exc, exc);

            XMPPError error
                = new XMPPError(
                        XMPPError.Condition.interna_server_error);

            return IQ.createErrorResponse(response, error);
        }
    }

    @Override
    protected String createLogoutUrl(String sessionId)
    {
        return null;
    }

    @Override
    public String createLoginUrl( String     machineUID,
                                  String     peerFullJid,
                                  String     roomName,
                                  boolean    popup )
    {
        return null;
    }

    /**
     * Always returns <tt>false</tt> as this authentication method does not use
     * external URLs.
     */
    @Override
    public boolean isExternal()
    {
        return false;
    }
}
