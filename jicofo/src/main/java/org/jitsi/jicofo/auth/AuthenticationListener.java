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

/**
 * Interface used to listen to authentication notification fired by
 * {@link AuthenticationAuthority}.
 *
 * @author Pawel Domas
 */
public interface AuthenticationListener
{
    /**
     * Called by {@link AuthenticationAuthority} when the user identified by
     * given <tt>userJid</tt> gets confirmed identity by external authentication
     * component.
     *  @param userJid the real user JID(not MUC JID which can be faked).
     * @param authenticatedIdentity the identity of the user confirmed by
     */
    void jidAuthenticated(Jid userJid,
                          String authenticatedIdentity,
                          String sessionId);
}
