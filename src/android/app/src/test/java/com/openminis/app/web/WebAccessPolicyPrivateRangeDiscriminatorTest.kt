package com.openminis.app.web

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ssrf-reserved-range] Discriminators for the reserved-address set in
 * [WebAccessPolicy.validateResolvedAddresses].
 *
 * ## Why this file exists
 *
 * `PublicSearchRoutingTest` already asserts that the SSRF gate refuses
 * `127.0.0.1` — and only that one reserved address. Measured against widened
 * copies of the rule (production source copied to /tmp, one clause weakened per
 * run, `K2JVMCompiler` + JUnitCore):
 *
 * | widened rule | `PublicSearchRoutingTest` (3 tests) | this file |
 * |---|---|---|
 * | loopback kept, link-local/site-local/multicast clauses deleted | 3 green, 0 red | 2 red |
 * | `isSiteLocalAddress` clause deleted (10/8, 172.16/12, 192.168/16 arrive) | 3 green, 0 red | 2 red |
 * | `isLinkLocalAddress` clause deleted (169.254.169.254 arrives) | 3 green, 0 red | 1 red |
 * | `addresses.forEach` → `addresses.take(1).forEach` | 3 green, 0 red | 1 red |
 *
 * So the existing assertion is a real refusal test and blind in all four widening
 * directions: its single reserved sample is loopback, and loopback is refused by
 * the narrow rule and by every one of the wider ones. The rule's whole point —
 * "reduce DNS rebinding/SSRF risk" — is the address classes this file pins, since
 * the addresses that actually matter to a resolver loop are the private and
 * link-local ones a name can be rebound to.
 *
 * ## The refusal is asserted by its REASON, not by "it failed"
 *
 * Every case below names the exact clause that must refuse, because `isFailure`
 * alone is satisfied by an unrelated earlier clause — `validateResolvedAddresses`
 * rejects loopback before it ever looks at site-local, so a bare "was refused"
 * assertion stays green even with the site-local clause deleted.
 */
class WebAccessPolicyPrivateRangeDiscriminatorTest {

    private fun refusalOf(literal: String): String? =
        WebAccessPolicy.validateResolvedAddresses(literal, listOf(InetAddress.getByName(literal)))
            .exceptionOrNull()?.message

    @Test
    fun `every reserved address class is refused, each for its own reason`() {
        // Premises: the literals really are the classes the rule names. Without these,
        // a JDK classification change would degrade this case into "the fixture is
        // wrong" and it would keep passing while testing nothing.
        assertTrue(
            "premise: 0.0.0.0 is the unspecified address",
            InetAddress.getByName("0.0.0.0").isAnyLocalAddress,
        )
        assertTrue(
            "premise: 127.0.0.1 is loopback",
            InetAddress.getByName("127.0.0.1").isLoopbackAddress,
        )
        assertTrue(
            "premise: 169.254.169.254 is link-local — the cloud metadata endpoint",
            InetAddress.getByName("169.254.169.254").isLinkLocalAddress,
        )
        assertTrue(
            "premise: 10.0.0.1 is site-local (RFC1918)",
            InetAddress.getByName("10.0.0.1").isSiteLocalAddress,
        )
        assertTrue(
            "premise: 192.168.1.10 is site-local (RFC1918)",
            InetAddress.getByName("192.168.1.10").isSiteLocalAddress,
        )
        assertTrue(
            "premise: 172.16.0.5 is site-local (RFC1918)",
            InetAddress.getByName("172.16.0.5").isSiteLocalAddress,
        )
        assertTrue(
            "premise: 224.0.0.1 is multicast",
            InetAddress.getByName("224.0.0.1").isMulticastAddress,
        )

        assertEquals("Unspecified address is not allowed", refusalOf("0.0.0.0"))
        assertEquals("Loopback address is not allowed", refusalOf("127.0.0.1"))
        assertEquals("Link-local address is not allowed", refusalOf("169.254.169.254"))
        assertEquals("Private address is not allowed", refusalOf("10.0.0.1"))
        assertEquals("Private address is not allowed", refusalOf("192.168.1.10"))
        assertEquals("Private address is not allowed", refusalOf("172.16.0.5"))
        assertEquals("Multicast address is not allowed", refusalOf("224.0.0.1"))

        // Control — holds under BOTH the narrow rule and every widened copy above, so
        // it cannot stand in for the assertions: a public address resolves either way.
        assertTrue(
            "control: a public address must still resolve",
            WebAccessPolicy
                .validateResolvedAddresses("dns.google", listOf(InetAddress.getByName("8.8.8.8")))
                .isSuccess,
        )
    }

    @Test
    fun `one reserved address in a multi-address answer sinks the whole host`() {
        // DNS rebinding hands back a public address and a private one in the same
        // answer; checking only the first would let the second through.
        val addresses = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("192.168.1.10"))
        assertTrue("premise: the second address really is site-local", addresses[1].isSiteLocalAddress)

        assertEquals(
            "every resolved address must be checked, not just the first",
            "Private address is not allowed",
            WebAccessPolicy.validateResolvedAddresses("rebinding.example", addresses)
                .exceptionOrNull()?.message,
        )
    }
}
