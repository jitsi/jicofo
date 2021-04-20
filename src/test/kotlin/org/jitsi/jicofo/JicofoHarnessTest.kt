package org.jitsi.jicofo

import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.ShouldSpec

abstract class JicofoHarnessTest : ShouldSpec() {
    protected val harness = JicofoHarness()

    override fun afterSpec(spec: Spec) = super.afterSpec(spec).also { harness.shutdown() }
}
