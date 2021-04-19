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

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.jitsi.jicofo.xmpp.muc.MemberRole.ADMINISTRATOR
import org.jitsi.jicofo.xmpp.muc.MemberRole.GUEST
import org.jitsi.jicofo.xmpp.muc.MemberRole.MODERATOR
import org.jitsi.jicofo.xmpp.muc.MemberRole.OWNER
import org.jivesoftware.smackx.muc.MUCAffiliation
import org.jivesoftware.smackx.muc.MUCRole

class MemberRoleTest : ShouldSpec() {
    init {
        context("Order") {
            (GUEST > MODERATOR) shouldBe true
            (MODERATOR > ADMINISTRATOR) shouldBe true
            (ADMINISTRATOR > OWNER) shouldBe true

            GUEST.compareTo(MODERATOR) shouldBeGreaterThan 0
            MODERATOR.compareTo(ADMINISTRATOR) shouldBeGreaterThan 0
            ADMINISTRATOR.compareTo(OWNER) shouldBeGreaterThan 0
        }
        context("hasModeratorRights") {
            GUEST.hasModeratorRights().shouldBeFalse()
            MODERATOR.hasModeratorRights().shouldBeTrue()
            ADMINISTRATOR.hasModeratorRights().shouldBeTrue()
            OWNER.hasModeratorRights().shouldBeTrue()
        }
        context("hasAdministratorRights") {
            GUEST.hasAdministratorRights().shouldBeFalse()
            MODERATOR.hasAdministratorRights().shouldBeFalse()
            ADMINISTRATOR.hasAdministratorRights().shouldBeTrue()
            OWNER.hasAdministratorRights().shouldBeTrue()
        }
        context("hasOwnerRights") {
            GUEST.hasOwnerRights().shouldBeFalse()
            MODERATOR.hasOwnerRights().shouldBeFalse()
            ADMINISTRATOR.hasOwnerRights().shouldBeFalse()
            OWNER.hasOwnerRights().shouldBeTrue()
        }
        context("From Smack role and affiliation") {
            enumPairsWithNull<MUCRole, MUCAffiliation>().forEach { (mucRole, mucAffiliation) ->
                withClue("mucRole=$mucRole, mucAffiliation=$mucAffiliation") {
                    MemberRole.fromSmack(mucRole, mucAffiliation) shouldBe when (mucAffiliation) {
                        MUCAffiliation.admin -> ADMINISTRATOR
                        MUCAffiliation.owner -> OWNER
                        else -> when (mucRole) {
                            MUCRole.moderator -> MODERATOR
                            else -> GUEST
                        }
                    }
                }
            }
        }
    }

    private inline fun <reified T : Enum<T>, reified U : Enum<U>> enumPairsWithNull(): List<Pair<T?, U?>> =
        enumValuesAndNull<T>().flatMap { l -> enumValuesAndNull<U>().map { r -> l to r } }
    private inline fun <reified T : Enum<T>> enumValuesAndNull(): List<T?> =
        mutableListOf<T?>(null).apply { addAll(enumValues<T>()) }
}
