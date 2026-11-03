/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2026-Present 8x8, Inc.
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
package org.jitsi.jicofo.util

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import org.jitsi.xmpp.extensions.TraceParent
import org.jitsi.xmpp.extensions.rayo.DialIq

class TracingUtilTest : ShouldSpec() {
    init {
        context("remoteContextFromW3CHeader") {
            should("Return the root context for invalid values") {
                listOf(
                    null,
                    "",
                    "   ",
                    "00-$TRACE_ID-$SPAN_ID",
                    "00-$TRACE_ID",
                    "00-00000000000000000000000000000000-$SPAN_ID-01",
                    "00-$TRACE_ID-0000000000000000-01",
                    "00-${TRACE_ID.uppercase()}-$SPAN_ID-01",
                    "00-$TRACE_ID-${SPAN_ID.uppercase()}-01",
                    "00-${TRACE_ID.drop(1)}-$SPAN_ID-01",
                    "00-$TRACE_ID-$SPAN_ID-01-extra",
                    "ff-$TRACE_ID-$SPAN_ID-01",
                    "0-$TRACE_ID-$SPAN_ID-01",
                    "zz-$TRACE_ID-$SPAN_ID-01"
                ).forEach { value ->
                    TracingUtil.remoteContextFromW3CHeader(value) shouldBe Context.root()
                }
            }
            should("Parse a valid value") {
                val span = Span.fromContextOrNull(TracingUtil.remoteContextFromW3CHeader("00-$TRACE_ID-$SPAN_ID-01"))
                span.shouldNotBeNull()
                span.spanContext.isValid shouldBe true
                span.spanContext.isRemote shouldBe true
                span.spanContext.traceId shouldBe TRACE_ID
                span.spanContext.spanId shouldBe SPAN_ID
                span.spanContext.isSampled shouldBe true
            }
            should("Ignore surrounding whitespace") {
                val span = Span.fromContextOrNull(
                    TracingUtil.remoteContextFromW3CHeader("  00-$TRACE_ID-$SPAN_ID-00 ")
                )
                span.shouldNotBeNull()
                span.spanContext.traceId shouldBe TRACE_ID
                span.spanContext.isSampled shouldBe false
            }
            should("Accept a later version with extra fields") {
                val span = Span.fromContextOrNull(
                    TracingUtil.remoteContextFromW3CHeader("01-$TRACE_ID-$SPAN_ID-01-extra")
                )
                span.shouldNotBeNull()
                span.spanContext.traceId shouldBe TRACE_ID
            }
            should("Use the default flags when the flags are malformed") {
                listOf("zz", "1", "001", "", "0A").forEach { flags ->
                    val span = Span.fromContextOrNull(
                        TracingUtil.remoteContextFromW3CHeader("00-$TRACE_ID-$SPAN_ID-$flags")
                    )
                    span.shouldNotBeNull()
                    span.spanContext.traceId shouldBe TRACE_ID
                    span.spanContext.traceFlags shouldBe TraceFlags.getDefault()
                }
            }
        }
        context("toW3CHeader") {
            should("Format a span context") {
                val spanContext = SpanContext.create(
                    TRACE_ID,
                    SPAN_ID,
                    TraceFlags.getSampled(),
                    TraceState.getDefault()
                )
                TracingUtil.toW3CHeader(spanContext) shouldBe "00-$TRACE_ID-$SPAN_ID-01"
            }
            should("Round trip through remoteContextFromW3CHeader") {
                listOf(TraceFlags.getSampled(), TraceFlags.getDefault()).forEach { flags ->
                    val spanContext = SpanContext.create(TRACE_ID, SPAN_ID, flags, TraceState.getDefault())
                    val parsed = Span.fromContextOrNull(
                        TracingUtil.remoteContextFromW3CHeader(TracingUtil.toW3CHeader(spanContext))
                    )
                    parsed.shouldNotBeNull()
                    parsed.spanContext.traceId shouldBe spanContext.traceId
                    parsed.spanContext.spanId shouldBe spanContext.spanId
                    parsed.spanContext.traceFlags shouldBe spanContext.traceFlags
                }
            }
        }
        context("remoteContextFromIq") {
            should("Return the root context when the IQ has no traceparent extension") {
                TracingUtil.remoteSpanFromIq(DialIq()).shouldBeNull()
                TracingUtil.remoteContextFromIq(DialIq()) shouldBe Context.root()
            }
            should("Return the root context when the traceparent extension is invalid") {
                val iq = DialIq().apply {
                    addExtension(TraceParent("00000000000000000000000000000000", SPAN_ID, "01"))
                }
                TracingUtil.remoteContextFromIq(iq) shouldBe Context.root()
            }
            should("Parse a valid traceparent extension") {
                val iq = DialIq().apply { addExtension(TraceParent(TRACE_ID, SPAN_ID, "01")) }
                val span = Span.fromContextOrNull(TracingUtil.remoteContextFromIq(iq))
                span.shouldNotBeNull()
                span.spanContext.traceId shouldBe TRACE_ID
                span.spanContext.spanId shouldBe SPAN_ID
                span.spanContext.isSampled shouldBe true
            }
        }
    }

    companion object {
        private const val TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
        private const val SPAN_ID = "b7ad6b7169203331"
    }
}
