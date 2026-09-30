package com.openminis.app.backup

import com.openminis.app.backup.remote.RcloneBackendCatalog
import com.openminis.app.backup.remote.RcloneRemoteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which destination fields the form asks for, and which it insists on.
 *
 * The shape here is a product decision that came out of real failures, not a
 * mirror of rclone's config schema — see the comments in the catalog — so it
 * is pinned against accidental "let's just expose what rclone takes" drift.
 */
class RcloneBackendCatalogFieldsTest {

    private fun fields(type: String) =
        RcloneBackendCatalog.backend(type)?.fields ?: error("no backend $type")

    private fun key(type: String, key: String) = fields(type).firstOrNull { it.key == key }

    /**
     * SMB is configured share-less: the share is the folder browser's first
     * level. Asking for it as text put a flat network name in front of users
     * whose NAS shows a disk path, and SMB reports a missing share as a LOGON
     * error — so the app blamed perfectly good credentials.
     */
    @Test
    fun `smb does not ask for a share`() {
        assertFalse(
            "the Share field must not come back",
            fields("smb").any { it.key == "share" },
        )
    }

    @Test
    fun `smb credentials are optional for guest shares`() {
        assertTrue("smb user must be optional", key("smb", "user")!!.isOptional)
        assertTrue("smb pass must be optional", key("smb", "pass")!!.isOptional)
    }

    @Test
    fun `webdav and ftp credentials are optional for anonymous servers`() {
        for (type in listOf("webdav", "ftp")) {
            assertTrue("$type user must be optional", key(type, "user")!!.isOptional)
            assertTrue("$type pass must be optional", key(type, "pass")!!.isOptional)
        }
    }

    /**
     * The task's explicit boundary: these effectively always need credentials,
     * so relaxing them would only produce configs that cannot connect.
     */
    @Test
    fun `sftp and s3 keep their credentials required`() {
        assertFalse("sftp user stays required", key("sftp", "user")!!.isOptional)
        assertFalse("sftp pass stays required", key("sftp", "pass")!!.isOptional)
        assertFalse("s3 access key stays required", key("s3", "access_key_id")!!.isOptional)
        assertFalse("s3 secret stays required", key("s3", "secret_access_key")!!.isOptional)
    }

    @Test
    fun `every backend still has its connection target field`() {
        assertNotNull(key("smb", "host"))
        assertNotNull(key("webdav", "url"))
        assertNotNull(key("sftp", "host"))
        assertNotNull(key("s3", "endpoint"))
        assertNotNull(key("ftp", "host"))
    }

    /** The secret field the store looks up must exist and be marked secret. */
    @Test
    fun `each backend exposes exactly one secret field`() {
        for (type in listOf("smb", "webdav", "sftp", "s3", "ftp")) {
            val secrets = fields(type).filter { it.isSecret }
            assertEquals("$type should have one secret field", 1, secrets.size)
        }
        assertEquals("secret_access_key", fields("s3").first { it.isSecret }.key)
    }

    /**
     * CONNECTIVITY: "which field is the secret" has exactly ONE answer, and the
     * code that acts on it reads that answer instead of restating it.
     *
     * The failure this exists because of: the catalog declared the secret field
     * per backend, while the two places that actually move a secret into the
     * encrypted store carried their own inline copy of the same table
     * (`if (backend == "s3") "secret_access_key" else "pass"`). Every assertion
     * above walks `fields(...)` and stops there, so the copies could drift —
     * and the `: "pass"` guess would send an S3 secret to the wrong rclone
     * parameter, or a renamed secret field would be persisted in plaintext.
     *
     * [RcloneBackendCatalog.secretField] and [RcloneRemoteStore.secretKeyFor]
     * are the two production lookups; this asserts both agree with the declared
     * field for every backend, so a change on one side alone turns this red.
     * The s3 name is spelled out rather than derived: it is a product fact
     * (rclone's own parameter name), not a restatement of the lookup.
     */
    @Test
    fun `the catalog is the only place that decides which field is the secret`() {
        assertEquals("s3's secret is rclone's own parameter name", "secret_access_key", RcloneBackendCatalog.secretField("s3"))
        for (type in listOf("smb", "webdav", "sftp", "s3", "ftp")) {
            val declared = fields(type).single { it.isSecret }.key
            assertEquals(
                "secretField must name the field the form marks secret ($type)",
                declared,
                RcloneBackendCatalog.secretField(type),
            )
            assertEquals(
                "the store must encrypt the field the form marks secret ($type)",
                declared,
                RcloneRemoteStore.secretKeyFor(type),
            )
        }
    }

    /**
     * The fallback path, pinned on purpose: a backend string that is not in the
     * catalog (a remote restored from another platform) must still get a key
     * name, so its secret reaches the encrypted store instead of being dropped.
     */
    @Test
    fun `an unknown backend still gets a usable secret key name`() {
        assertNull(RcloneBackendCatalog.secretField("b2"))
        assertEquals("pass", RcloneRemoteStore.secretKeyFor("b2"))
    }
}
