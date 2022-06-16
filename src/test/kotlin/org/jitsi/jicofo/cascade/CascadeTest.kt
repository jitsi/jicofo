package org.jitsi.jicofo.cascade

class TestCascade : Cascade {
    override val bridges = HashMap<String, CascadeNode>()
}

class TestCascadeNode(override val relayId: String) : CascadeNode {
    override val links = HashSet<CascadeLink>()
    override fun addLink(node: CascadeNode, meshId: String) {
        links.add(TestCascadeLink(node.relayId, meshId))
    }

    override fun toString(): String = "$relayId: [${links.joinToString()}]"
}

class TestCascadeLink(override val relayId: String, override val meshId: String) : CascadeLink {
    override fun toString(): String = "$meshId -> $relayId"
}

class CascadeTest
