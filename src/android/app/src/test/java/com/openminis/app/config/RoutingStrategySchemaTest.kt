package com.openminis.app.config

import com.openminis.app.config.collections.routingStrategySchema
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.shared.KotlinSourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoutingStrategy]'s wire values in the config schema — asserted against the
 * value set `GroupsCollection.strategyField` actually installs.
 *
 * ## Why this file calls production instead of restating it
 *
 * It used to build its own `ConfigSchema.StrEnum(listOf("none", "fallback",
 * "loadBalance"))` and validate against that: every assertion was true of a list
 * the test had just written, so the schema the app enforces could have lost a
 * case (or gained one) with this file still green — and the *third* copy of the
 * same three strings sat in the production field. The value set is now derived
 * in production from the enum by `routingStrategySchema()`, and the assertions
 * below run that function.
 */
class RoutingStrategySchemaTest {

    @Test
    fun `routing strategy schema accepts all declared wire values`() {
        val schema = routingStrategySchema()
        RoutingStrategy.entries.forEach { schema.validate(ConfigValue.Str(it.name)) }
        assertThrows(ConfigError.InvalidValue::class.java) {
            schema.validate(ConfigValue.Str("unsupported"))
        }
    }

    /**
     * A copy of the wire values cannot be asked this: theirs is a fixed list, so
     * adding a `RoutingStrategy` constant leaves it silently incomplete. Derived
     * values track the enum by construction, which is the property that keeps the
     * field's own reader/writer (`RoutingStrategy.name` / `.valueOf`) and its
     * schema from disagreeing.
     */
    @Test
    fun `the accepted values are exactly the enum constants`() {
        val cases = (routingStrategySchema() as? ConfigSchema.StrEnum)?.cases
            ?: throw AssertionError("groups.<id>.strategy must be a closed value set")
        assertEquals(
            "the schema's cases must be exactly RoutingStrategy's names, in order",
            RoutingStrategy.entries.map { it.name },
            cases,
        )
        // …and nothing else: an off-by-one in the derivation would otherwise pass.
        assertTrue("no extra value may be accepted", cases.none { it == "unsupported" })
    }

    /**
     * The schema under test must be the one the config surface installs — a
     * second, hand-kept copy of the three strings is what this whole file exists
     * to keep out.
     */
    @Test
    fun `the group strategy field installs the derived schema`() {
        val source = KotlinSourceText.noComments(source("config/collections/GroupsCollection.kt"))
        assertTrue(
            "groups.<id>.strategy must install routingStrategySchema()",
            source.contains("valueSchema = routingStrategySchema(),"),
        )
        assertFalse(
            "a hand-kept copy of the wire values came back — that copy is the defect",
            source.contains("StrEnum(listOf(\"none\""),
        )
    }

    private fun source(relativePath: String): String =
        sequenceOf(
            File("app/src/main/java/com/openminis/app/$relativePath"),
            File("src/main/java/com/openminis/app/$relativePath"),
        ).firstOrNull { it.isFile }?.readText()
            ?: error("cannot locate $relativePath from ${File(".").absolutePath}")
}
