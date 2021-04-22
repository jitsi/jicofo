/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021 - present 8x8, Inc.
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
package org.jitsi.jicofo

import org.jxmpp.jid.EntityBareJid

interface ConferenceStore {
    /** Get a list of all conferences. */
    fun getAllConferences(): List<JitsiMeetConference>
    /** Get a conference for a specific [Jid] (i.e. name). */
    fun getConference(jid: EntityBareJid): JitsiMeetConference?
}

class EmptyConferenceStore : ConferenceStore {
    override fun getAllConferences() = emptyList<JitsiMeetConference>()
    override fun getConference(jid: EntityBareJid): JitsiMeetConference? = null
}
