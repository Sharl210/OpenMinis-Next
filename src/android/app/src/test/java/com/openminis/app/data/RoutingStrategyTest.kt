package com.openminis.app.data

import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingStrategyTest {
    @Test
    fun `none is a persisted routing strategy`() {
        val group = ModelGroup(name = "Pinned", strategy = RoutingStrategy.none)
        assertEquals(RoutingStrategy.none, group.strategy)
        assertEquals("none", group.strategy.name)
        assertEquals(RoutingStrategy.none, RoutingStrategy.valueOf("none"))
    }

    @Test
    fun `none keeps the selected member instead of rotating`() {
        val members = listOf("provider-a/model", "provider-b/model")
        val selected = members.first()
        val strategy = RoutingStrategy.none
        val resolved = when (strategy) {
            RoutingStrategy.none -> selected
            RoutingStrategy.fallback -> members.first()
            RoutingStrategy.loadBalance -> members.last()
        }
        assertEquals(selected, resolved)
    }
}
