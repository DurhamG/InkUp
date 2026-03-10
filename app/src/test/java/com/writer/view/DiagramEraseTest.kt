package com.writer.view

import android.graphics.RectF
import com.writer.model.DiagramEdge
import com.writer.model.DiagramModel
import com.writer.model.DiagramNode
import com.writer.model.StrokeType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that erasing a diagram shape removes it from the Mermaid output.
 *
 * Erase is modelled as removing node/edge entries from [DiagramModel] —
 * exactly what [WritingCoordinator.onScratchOut] does after finding overlapping strokes.
 * [DiagramMarkdown.buildMermaidBlock] is then used to verify the output changes.
 */
class DiagramEraseTest {

    private fun rect() = RectF(0f, 0f, 100f, 60f)

    // ── Nodes ─────────────────────────────────────────────────────────────────

    @Test fun erasingNode_removesItFromMermaid() {
        val diagram = DiagramModel()
        diagram.nodes["s1"] = DiagramNode("s1", StrokeType.RECTANGLE, rect(), "Process")

        val before = DiagramMarkdown.buildMermaidBlock(diagram)
        assertTrue("node should appear before erase", before.contains("Process"))

        // Simulate erase
        diagram.nodes.remove("s1")

        val after = DiagramMarkdown.buildMermaidBlock(diagram)
        assertTrue("mermaid should be empty after erasing sole node", after.isEmpty())
    }

    @Test fun erasingOneOfTwoNodes_keepsOtherNode() {
        val diagram = DiagramModel()
        diagram.nodes["s1"] = DiagramNode("s1", StrokeType.RECTANGLE, rect(), "Start")
        diagram.nodes["s2"] = DiagramNode("s2", StrokeType.ELLIPSE, rect(), "End")

        // Erase "Start"
        diagram.nodes.remove("s1")

        val mermaid = DiagramMarkdown.buildMermaidBlock(diagram)
        assertFalse("erased node label should not appear", mermaid.contains("Start"))
        assertTrue("surviving node label should still appear", mermaid.contains("End"))
    }

    @Test fun erasingNode_mermaidUsesCorrectShapeSyntax() {
        val diagram = DiagramModel()
        diagram.nodes["s1"] = DiagramNode("s1", StrokeType.DIAMOND, rect(), "Valid?")

        assertTrue(DiagramMarkdown.buildMermaidBlock(diagram).contains("{Valid?}"))

        diagram.nodes.remove("s1")

        assertFalse(DiagramMarkdown.buildMermaidBlock(diagram).contains("Valid?"))
    }

    // ── Edges ─────────────────────────────────────────────────────────────────

    @Test fun erasingEdge_removesItFromMermaid() {
        val diagram = DiagramModel()
        diagram.nodes["s1"] = DiagramNode("s1", StrokeType.RECTANGLE, rect(), "A")
        diagram.nodes["s2"] = DiagramNode("s2", StrokeType.RECTANGLE, rect(), "B")
        diagram.edges["e1"] = DiagramEdge("e1", "s1", "s2")

        val edgeTypes = mapOf("e1" to StrokeType.ARROW_HEAD)
        val before = DiagramMarkdown.buildMermaidBlock(diagram, edgeTypes)
        assertTrue("edge connector should appear before erase", before.contains("-->"))

        // Erase the arrow stroke
        diagram.edges.remove("e1")

        val after = DiagramMarkdown.buildMermaidBlock(diagram, emptyMap())
        // Nodes become orphans — they still appear, but no connector line
        assertFalse("edge connector should not appear after erase", after.contains("-->"))
        assertTrue("node A should remain", after.contains("A"))
        assertTrue("node B should remain", after.contains("B"))
    }

    @Test fun erasingConnectedNode_removesEdgeToo() {
        val diagram = DiagramModel()
        diagram.nodes["s1"] = DiagramNode("s1", StrokeType.RECTANGLE, rect(), "Alpha")
        diagram.nodes["s2"] = DiagramNode("s2", StrokeType.RECTANGLE, rect(), "Beta")
        diagram.edges["e1"] = DiagramEdge("e1", "s1", "s2")

        // Erase both the node and its arrow (as onScratchOut does when they overlap)
        diagram.nodes.remove("s1")
        diagram.edges.remove("e1")

        val mermaid = DiagramMarkdown.buildMermaidBlock(diagram, emptyMap())
        assertFalse("erased node label absent", mermaid.contains("Alpha"))
        assertFalse("edge connector absent", mermaid.contains("-->"))
        assertTrue("surviving node still present", mermaid.contains("Beta"))
    }

    // ── Empty diagram ──────────────────────────────────────────────────────────

    @Test fun eraseAllNodes_produceEmptyMermaid() {
        val diagram = DiagramModel()
        diagram.nodes["s1"] = DiagramNode("s1", StrokeType.RECTANGLE, rect(), "Only")

        diagram.nodes.remove("s1")

        assertTrue("empty diagram → empty string",
            DiagramMarkdown.buildMermaidBlock(diagram).isEmpty())
    }
}
