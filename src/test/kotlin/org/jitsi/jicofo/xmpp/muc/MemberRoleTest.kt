/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 - present 8x8, Inc.
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
package org.jitsi.jicofo.xmpp.muc

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.jitsi.impl.protocol.xmpp.ChatRoomMemberRole.ADMINISTRATOR
import org.jitsi.impl.protocol.xmpp.ChatRoomMemberRole.GUEST
import org.jitsi.impl.protocol.xmpp.ChatRoomMemberRole.MEMBER
import org.jitsi.impl.protocol.xmpp.ChatRoomMemberRole.MODERATOR
import org.jitsi.impl.protocol.xmpp.ChatRoomMemberRole.OWNER

class XmppUtilsTest : ShouldSpec() {
    init {
        context("Order") {
            (GUEST > MEMBER) shouldBe true
            (MEMBER > MODERATOR) shouldBe true
            (MODERATOR > ADMINISTRATOR) shouldBe true
            (ADMINISTRATOR > OWNER) shouldBe true

            GUEST.compareTo(MEMBER) shouldBeGreaterThan  0
            MEMBER.compareTo(MODERATOR) shouldBeGreaterThan  0
            MODERATOR.compareTo(ADMINISTRATOR) shouldBeGreaterThan  0
            ADMINISTRATOR.compareTo(OWNER) shouldBeGreaterThan  0
        }
    }
}