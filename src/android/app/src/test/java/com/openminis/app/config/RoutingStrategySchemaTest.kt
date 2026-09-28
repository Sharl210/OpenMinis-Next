package com.openminis.app.config

import org.junit.Assert.assertThrows
import org.junit.Test

class RoutingStrategySchemaTest {
    @Test
    fun `routing strategy schema accepts all declared wire values`() {
        val schema = ConfigSchema.StrEnum(listOf("none", "fallback", "loadBalance"))
        listOf("none", "fallback", "loadBalance").forEach { schema.validate(ConfigValue.Str(it)) }
        assertThrows(ConfigError.InvalidValue::class.java) {
            schema.validate(ConfigValue.Str("unsupported"))
        }
    }
}
