/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.time.*;

import static org.apache.commons.lang3.StringUtils.*;

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
    private final DomainBareJid domain;

    /**
     * Creates new instance of <tt>XMPPDomainAuthAuthority</tt>.
     *
     * @param enableAutoLogin disables auto login feature. Authentication
     * sessions are destroyed immediately when the conference ends.
     * @param authenticationLifetime specifies how long authentication sessions
     * will be stored in Jicofo's memory. Interval in milliseconds.
     * @param domain a string with XMPP domain name for which users will be
     *               considered authenticated.
     */
    public XMPPDomainAuthAuthority(boolean enableAutoLogin,
                                   Duration authenticationLifetime,
                                   DomainBareJid domain)
    {
        super(enableAutoLogin, authenticationLifetime);

        this.domain = domain;
    }

    private boolean verifyJid(Jid fullJid)
    {
        return fullJid.asDomainBareJid().equals(domain);
    }

    @Override
    protected IQ processAuthLocked(ConferenceIq query, ConferenceIq response)
    {
        Jid peerJid = query.getFrom();
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
            BareJid bareJid = peerJid.asBareJid();
            String machineUID = query.getMachineUID();
            if (isBlank(machineUID))
            {
                return ErrorFactory.createNotAcceptableError(query,
                        "Missing mandatory attribute '"
                                + ConferenceIq.MACHINE_UID_ATTR_NAME + "'");
            }
            session = createNewSession(machineUID, bareJid.toString(), query.getRoom());
        }

        // Authenticate JID with session(if it exists)
        if (session != null)
        {
            authenticateJidWithSession(session, peerJid, response);
        }

        return null;
    }

    @Override
    public String createLoginUrl(
            String machineUID,
            EntityFullJid peerFullJid,
            EntityBareJid roomName,
            boolean popup)
    {
        return "./" + roomName.getLocalpartOrThrow() + "?login=true";
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
