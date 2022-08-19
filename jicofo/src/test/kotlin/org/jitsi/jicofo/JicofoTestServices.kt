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
package org.jitsi.jicofo

import mock.MockXmppProvider
import org.jitsi.impl.protocol.xmpp.XmppProvider
import org.jitsi.jicofo.xmpp.XmppConnectionConfig
import org.jitsi.jicofo.xmpp.XmppProviderFactory
import org.jitsi.utils.logging2.Logger
import org.jxmpp.jid.EntityFullJid

class JicofoTestServices : JicofoServices() {
    override fun createXmppProviderFactory(): XmppProviderFactory {
        return object : XmppProviderFactory {
            override fun createXmppProvider(
                config: XmppConnectionConfig,
                parentLogger: Logger
            ): XmppProvider {
                return MockXmppProvider(config)
            }
        }
    }

    val jicofoJid: EntityFullJid
        get() = (xmppServices.clientConnection as MockXmppProvider).ourJID
}
