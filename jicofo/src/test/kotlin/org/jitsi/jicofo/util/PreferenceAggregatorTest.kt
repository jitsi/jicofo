package org.jitsi.jicofo.util

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jitsi.utils.logging2.createLogger

class PreferenceAggregatorTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val logger = createLogger()

    private val calledWith = mutableListOf<List<String>>()
    private val aggregator = PreferenceAggregator(logger) {
        calledWith.add(it)
    }

    init {
        context("An aggregator with no values added") {
            should("Not call its callback") {
                calledWith shouldBe emptyList()
            }
        }
        context("An aggregator called once") {
            aggregator.addPreference(listOf("vp9", "vp8", "h264"))
            should("Call the callback exactly once with that set of values") {
                calledWith shouldContainExactly listOf(listOf("vp9", "vp8", "h264"))
            }
        }
        context("An aggregator called twice with the same values") {
            aggregator.addPreference(listOf("vp9", "vp8", "h264"))
            aggregator.addPreference(listOf("vp9", "vp8", "h264"))
            should("Call the callback exactly once") {
                calledWith shouldContainExactly listOf(listOf("vp9", "vp8", "h264"))
            }
        }
        context("An aggregator with all preferences removed") {
            aggregator.addPreference(listOf("vp9", "vp8", "h264"))
            aggregator.removePreference(listOf("vp9", "vp8", "h264"))
            should("Have its final output be the empty set") {
                calledWith.last() shouldBe emptyList()
            }
        }
        context("Aggregating preferences with disparate values (subset)") {
            aggregator.addPreference(listOf("vp9", "vp8", "h264"))
            aggregator.addPreference(listOf("vp8", "h264"))
            should("Output the minimal agreed set") {
                calledWith.last().shouldContainExactly("vp8", "h264")
            }
        }
        context("Aggregating preferences with disparate values (non-subset)") {
            aggregator.addPreference(listOf("vp9", "vp8", "h264"))
            aggregator.addPreference(listOf("vp8", "h264"))
            aggregator.addPreference(listOf("vp9", "vp8"))
            should("Output the minimal agreed set") {
                calledWith.last().shouldContainExactly("vp8")
            }
        }
        context("Aggregating a new superset") {
            aggregator.addPreference(listOf("vp9", "vp8", "h264"))
            aggregator.addPreference(listOf("av1", "vp9", "vp8", "h264"))
            should("Not call the callback a second time") {
                calledWith shouldContainExactly listOf(listOf("vp9", "vp8", "h264"))
            }
        }
        context("Removing the only preference that does not support a value") {
            aggregator.addPreference(listOf("vp9", "vp8", "h264"))
            aggregator.addPreference(listOf("vp8", "h264"))
            aggregator.addPreference(listOf("vp9", "vp8"))

            aggregator.removePreference(listOf("vp8", "h264"))
            should("Return that value to the set of preferences") {
                calledWith.last().shouldContainExactly(listOf("vp9", "vp8"))
            }
        }
        context("Preferences that express different orders") {
            aggregator.addPreference(listOf("vp9", "vp8", "h264"))
            aggregator.addPreference(listOf("vp9", "vp8", "h264"))
            aggregator.addPreference(listOf("vp9", "h264", "vp8"))

            should("Reflect the majority preference") {
                calledWith shouldContainExactly listOf(listOf("vp9", "vp8", "h264"))
            }
        }
        context("Ties in preference order") {
            aggregator.addPreference(listOf("vp9", "vp8", "h264"))
            aggregator.addPreference(listOf("vp9", "h264", "vp8"))

            should("Result in the correct set, in some order, with consensus where it exists") {
                calledWith.last().shouldContainExactlyInAnyOrder("h264", "vp9", "vp8")
                calledWith.last().first() shouldBe "vp9"
            }
        }
        context("Repeated values in preferences") {
            aggregator.addPreference(listOf("vp9", "vp8"))
            aggregator.addPreference(listOf("vp9", "vp8", "vp9"))
            should("not confuse things") {
                calledWith shouldContainExactly listOf(listOf("vp9", "vp8"))
            }
            aggregator.removePreference(listOf("vp9", "vp8", "vp9"))
            aggregator.removePreference(listOf("vp9", "vp8"))
            should("not confuse things on removal") {
                calledWith.last().shouldContainExactly(emptyList())
            }
        }
    }
}
