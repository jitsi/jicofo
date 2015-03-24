/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.auth;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.*;
import org.jivesoftware.smack.packet.*;

/**
 * XMPP domain authentication authority that authorizes user who are logged
 * in on specified domain.
 *
 * FIXME move to separate package
 *
 * @author Pawel Domas
 */
public class XMPPDomainAuthAuthority
    extends AbstractAuthAuthority
{
    /**
     * Trusted domain for which users are considered authenticated.
     */
    private final String domain;

    public XMPPDomainAuthAuthority(String domain)
    {
        this.domain = domain;
    }

    private boolean verifyJid(String fullJid)
    {
        String bareJid = getBareJid(fullJid);

        return bareJid.endsWith("@" + domain);
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

        // Check for invalid session
        IQ error = verifySession(query);
        if (error != null)
        {
            return error;
        }

        // Create new session if JID is valid
        if (session == null && verifyJid(peerJid))
        {
            // Create new session
            String bareJid = getBareJid(peerJid);
            String machineUID = query.getMachineUID();
            if (StringUtils.isNullOrEmpty(machineUID))
            {
                return ErrorFactory.createNotAcceptableError(query,
                        "Missing mandatory attribute '"
                                + ConferenceIq.MACHINE_UID_ATTR_NAME + "'");
            }
            session = createNewSession(machineUID, bareJid, query.getRoom());
        }

        // Authenticate JID with session(if it exists)
        if (session != null)
        {
            authenticateJidWithSession(session, peerJid, response);
        }

        return null;
    }

    @Override
    public boolean isUserAuthenticated(String jabberID)
    {
        return findSessionForJabberId(jabberID) != null || verifyJid(jabberID);
    }

    @Override
    public String createLoginUrl(
            String machineUID, String peerFullJid, String roomName, boolean popup)
    {
        roomName = MucUtil.extractName(roomName);

        return "./" + roomName + "?login=true";
    }

    @Override
    public boolean isExternal()
    {
        return false;
    }

    @Override
    protected String createLogoutUrl(String sessionId)
    {
        return null;
    }
}
