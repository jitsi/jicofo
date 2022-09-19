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

import org.jxmpp.jid.*;

import java.time.*;

/**
 * Special case of <tt>XMPPDomainAuthAuthority</tt> where the user is
 * authenticated in Prosody with JWT token authentication method. The name of
 * XMPP domain should be passed to the constructor, which will happen when
 * {@link AuthConfig#getLoginUrl()} is set to
 * "EXT_JWT:auth.server.net", where 'auth.server.net' is the Prosody domain with
 * JWT token authentication enabled.
 * In order to obtain JWT, the user visits external "login" service from where
 * is redirected back to the app with the token. That's why
 * {@link #isExternal()} is overridden to return <tt>true</tt>.
 *
 * @author Pawel Domas
 */
public class ExternalJWTAuthority
    extends XMPPDomainAuthAuthority
{
    /**
     * Creates new instance of <tt>{@link ExternalJWTAuthority}</tt>.
     * @param domain the name of the Prosody domain with JWT authentication
     * enabled.
     */
    public ExternalJWTAuthority(DomainBareJid domain)
    {
        // For external JWT type of authentication we do not want to persist
        // the session IDs longer than the duration of the conference.
        // Also session duration is limited to 1 minuted. This is how long it
        // can be used for "on the fly" user role upgrade. That is the case when
        // the user starts from anonymous domain and then authenticates in
        // the popup window.
        super(false /* enable auto login */,
                Duration.ofMinutes(1) /* limit session duration to 1 minute */,
              domain);
    }

    @Override
    public String createLoginUrl(
            String machineUID, EntityFullJid peerFullJid,
            EntityBareJid roomName, boolean popup)
    {
        // Login URL is configured/generated in the client
        return null;
    }

    @Override
    public boolean isExternal()
    {
        return true;
    }
}
