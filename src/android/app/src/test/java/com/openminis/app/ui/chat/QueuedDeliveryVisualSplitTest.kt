package com.openminis.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.openminis.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-queued-delivery-visual-split] request.md:172 — 「以插队模式发送和排队模式
 * 发送的，他们两个也要有差别，就是他们两个的**显示的样式也要有差别**，就是**一眼能让我们
 * 分辨出来**，哪一个是插队」.
 *
 * Before this, a STEER and a QUEUE bubble differed only in the word shown: same
 * position, same type scale, same colour, no glyph, and a border whose dash
 * pattern was keyed on `isQueued` so both deliveries got the identical stroke.
 * "显示的样式也要有差别" was therefore not met.
 *
 * The assertions below are on the DISCRIMINATORS, and the important one is that
 * they are not colour. Colour is the only discriminator that fails outright for a
 * colour-blind reader, and it also fails in a greyscale screenshot or a
 * monochrome theme — hence an icon, a distinct label resource AND a border-shape
 * change, each asserted separately so that removing any one of them turns a test
 * red.
 */
class QueuedDeliveryVisualSplitTest {

    @Test
    fun `steer and queue resolve to different label resources`() {
        val steer = QueuedPromptUiPolicy.badgeStyle(QueuedPromptDelivery.STEER)
        val queue = QueuedPromptUiPolicy.badgeStyle(QueuedPromptDelivery.QUEUE)

        assertNotEquals(
            "the two deliveries must not share a label resource",
            steer.labelRes,
            queue.labelRes,
        )
        assertEquals(R.string.chat_queue_steer_badge, steer.labelRes)
        assertEquals(R.string.chat_queue_queue_badge, queue.labelRes)
    }

    /**
     * The recognition path that survives colour blindness, greyscale and a screen
     * reader: two different glyphs.
     */
    @Test
    fun `steer and queue use different icons`() {
        val steer = QueuedPromptUiPolicy.badgeStyle(QueuedPromptDelivery.STEER)
        val queue = QueuedPromptUiPolicy.badgeStyle(QueuedPromptDelivery.QUEUE)

        assertNotEquals(steer.icon, queue.icon)
        assertEquals(Icons.Filled.Bolt, steer.icon)
        assertEquals(Icons.Filled.Schedule, queue.icon)
        // The glyphs must differ in SHAPE, not merely be two references to the
        // same drawing — comparing the vectors' own paths is what makes this
        // survive a future refactor that aliases one to the other.
        assertNotEquals(steer.icon.pathSignature(), queue.icon.pathSignature())
    }

    /**
     * The requirement's own suggested placement — 「你可以设计在边框上或者说整个边线上」 —
     * because a border marker costs no space inside the bubble.
     */
    @Test
    fun `steer and queue draw a different border`() {
        assertFalse(
            "a steered message has cut the line, so its border is solid",
            QueuedPromptUiPolicy.badgeStyle(QueuedPromptDelivery.STEER).borderDashed,
        )
        assertTrue(
            "a queued message is still waiting its turn, so its border is dashed",
            QueuedPromptUiPolicy.badgeStyle(QueuedPromptDelivery.QUEUE).borderDashed,
        )
    }

    /**
     * The three discriminators must move together: a row written before
     * `queuedDelivery` existed has no delivery recorded, and it must render as
     * exactly one determinate thing rather than as a half-styled third state.
     */
    @Test
    fun `an unknown delivery falls back to one consistent style`() {
        val queue = QueuedPromptUiPolicy.badgeStyle(QueuedPromptDelivery.QUEUE)
        val unknown = QueuedPromptUiPolicy.badgeStyle(null)

        assertEquals(queue.labelRes, unknown.labelRes)
        assertEquals(queue.icon, unknown.icon)
        assertEquals(queue.borderDashed, unknown.borderDashed)
    }
}

/**
 * Every drawing command in the icon, in tree order.
 *
 * [ImageVector] exposes no flattened `pathData` of its own — only [ImageVector.root],
 * a [VectorGroup] that is publicly iterable and whose [VectorPath] leaves expose the
 * real `pathData`. Collecting through those public members keeps the shape assertion
 * honest without reaching into internals.
 */
private fun ImageVector.pathSignature(): String {
    val out = StringBuilder()
    fun walk(node: VectorNode) {
        when (node) {
            is VectorPath -> out.append(node.pathData.joinToString(",")).append(';')
            is VectorGroup -> node.forEach { walk(it) }
        }
    }
    walk(root)
    return out.toString()
}
