/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc
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
package org.jitsi.jicofo.jigasi

import org.jitsi.metaconfig.optionalconfig
import org.jitsi.config.JitsiConfig.Companion.legacyConfig
import org.jitsi.config.JitsiConfig.Companion.newConfig
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

class JigasiConfig {
    val breweryJid: Jid? by optionalconfig {
        "org.jitsi.jicofo.jigasi.BREWERY".from(legacyConfig).convertFrom<String> {
            JidCreate.bareFrom(it)
        }
        "jicofo.jigasi.brewery-jid".from(newConfig).convertFrom<String> {
            JidCreate.bareFrom(it)
        }
    }
    fun breweryEnabled() = breweryJid != null

    companion object {
        @JvmField
        val config = JigasiConfig()
    }
}
