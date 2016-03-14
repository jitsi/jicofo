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
package org.jitsi.jicofo.recording.jibri;

/**
 * The interface used to notify about the availability and busy/idle status
 * updates of Jibri instances which exist in the current Jicofo session.
 *
 * @author Pawel Domas
 */
public interface JibriListener
{
    /**
     * Notifies about Jibri busy/idle status changes.
     * @param jibriJid the XMPP address of subject Jibri instance.
     * @param idle <tt>true</tt> if the Jibri is idle or <tt>false</tt> when
     *        it's busy(with recording).
     */
    void onJibriStatusChanged(String jibriJid, boolean idle);

    /**
     * Methods called when particular Jibri instance goes offline(disconnects).
     * @param jibriJid the XMPP address of Jibri instance which just went
     *        offline.
     */
    void onJibriOffline(String jibriJid);
}
