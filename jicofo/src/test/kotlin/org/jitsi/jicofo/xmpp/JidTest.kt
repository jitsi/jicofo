/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2024-Present 8x8, Inc.
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
package org.jitsi.jicofo.xmpp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestCase
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jxmpp.JxmppContext
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.stringprep.XmppStringprepException
import org.jxmpp.stringprep.rocksxmppprecis.RocksXmppPrecisStringprep

/**
 * Test JID parsing. The lists below are based on the jxmpp corpora here, plus a couple additional ones:
 * https://github.com/igniterealtime/jxmpp/tree/master/jxmpp-strings-testframework/src/main/resources/xmpp-strings/jids/valid/main
 * https://github.com/igniterealtime/jxmpp/blob/master/jxmpp-strings-testframework/src/main/resources/xmpp-strings/jids/invalid/main
 */
class JidTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode {
        return IsolationMode.SingleInstance
    }
    override suspend fun beforeAny(testCase: TestCase) {
        super.beforeAny(testCase)
        initializeSmack()
    }

    init {
        context("Parsing valid JIDs") {
            JxmppContext.getDefaultContext().xmppStringprep.shouldBeInstanceOf<RocksXmppPrecisStringprep>()
            validJids.forEach {
                withClue(it) {
                    JidCreate.from(it) shouldNotBe null
                }
            }
        }
        context("Parsing invalid JIDs") {
            JxmppContext.getDefaultContext().xmppStringprep.shouldBeInstanceOf<RocksXmppPrecisStringprep>()
            invalidJids.forEach {
                withClue(it) {
                    shouldThrow<XmppStringprepException> {
                        JidCreate.from((it))
                    }
                }
            }
        }
    }
}

val validJids = listOf(
    "juliet@example.com",
    "juliet@example.com/foo",
    "juliet@example.com/foo bar",
    "juliet@example.com/foo@bar",
    "foo\\20bar@example.com",
    "fussball@example.com",
    "fußball@example.com",
    "π@example.com",
    "Σ@example.com",
    "ς@example.com",
    "king@example.com/♚",
    "example.com",
    "example.com/foobar",
    "a.example.com/b@example.net",
    "server/resource@foo",
    "server/resource@foo/bar",
    "user@CaSe-InSeNsItIvE",
    "user@192.168.1.1",
    // "user@[2001:638:a000:4134::ffff:40]",
    // "user@[2001:638:a000:4134::ffff:40%eno1]",
    // "user@averylongdomainpartisstillvalideventhoughitexceedsthesixtyfourbytelimitofdnslabels",
    "long-conference-name-1245c711a15e466687b6333577d83e0b@" +
        "conference.vpaas-magic-cookie-a32a0c3311ee432eab711fa1fdf34793.8x8.vc",
    "user@example.org/🍺"
)

val invalidJids = listOf(
    "jul\u0001iet@example.c",
    "\"juliet\"@example.com",
    "foo bar@example.com",
    // This fails due to a corner case in JidCreate when "example.com" is already cached as a DomainpartJid
    // "@example.com/",
    "henryⅣ@example.com",
    "♚@example.com",
    "juliet@",
    "/foobar",
    "node@/server",
    "@server",
    "@server/resource",
    "@/resource",
    "@/",
    "/",
    "@",
    "user@",
    "user@@",
    "user@@host",
    "user@@host/resource",
    "user@@host/",
    "xsf@muc.xmpp.org/؜x",
    "username@example.org@example.org",
    "foo\u0000bar@example.org",
    "foobar@ex\u0000ample.org",
    "user@conference..example.org"
)
