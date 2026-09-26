package com.openminis.app.backup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Identity and strict parser for the opt-in Next backup boundary.
 *
 * This is intentionally separate from [BackupFormat]. The existing `minisbak`
 * reader remains compatible with already shipped packages; a Next caller must
 * opt into this parser and cannot silently downgrade to the legacy namespace or
 * numeric timestamp migration rules.
 */
object NextBackupFormat {
    const val NAMESPACE = BackupFormat.NEXT_NAMESPACE
    const val VERSION = 1
    const val CURRENT = BackupFormat.NEXT_CURRENT
    const val FILE_EXTENSION = BackupFormat.NEXT_FILE_EXTENSION
    const val MIME_TYPE = BackupFormat.NEXT_MIME_TYPE

    /** Tolerant for additive fields, but not coercive for legacy value types. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = false
    }

    fun decodeManifest(raw: String): BackupManifest {
        val manifest = try {
            json.decodeFromString(BackupManifest.serializer(), raw)
        } catch (e: Exception) {
            throw BackupException("The Next backup manifest could not be read.", e)
        }
        requireIdentity(manifest.format)
        return manifest
    }

    fun requireIdentity(format: String) {
        val namespace = format.substringBefore('/', missingDelimiterValue = "")
        if (namespace != NAMESPACE) {
            throw BackupException(
                "This package is not a Next backup (namespace: $namespace)."
            )
        }
        val major = format.substringAfter('/', missingDelimiterValue = "")
            .substringBefore('.')
        if ("$namespace/$major" != CURRENT) {
            throw BackupException(
                "This Next backup version is not supported ($format). Please update the app."
            )
        }
    }

    /**
     * Next metadata timestamps are ISO-8601 strings only. Numeric epoch
     * milliseconds are deliberately rejected instead of migrating legacy data.
     */
    fun decodeEnvVarMeta(raw: String): NextEnvVarMeta = try {
        json.decodeFromString(NextEnvVarMeta.serializer(), raw)
    } catch (e: Exception) {
        throw BackupException(
            "Next backup metadata must use ISO-8601 timestamps; legacy numeric timestamps are unsupported.",
            e,
        )
    }
}

@Serializable
data class NextEnvVarMeta(
    val id: String,
    val key: String,
    val note: String = "",
    @Serializable(with = NextIso8601MillisSerializer::class)
    val createdAt: Long = 0,
)

/** Strict ISO-8601 serializer used only by Next records. */
object NextIso8601MillisSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NextIso8601Millis", PrimitiveKind.STRING)

    private fun formatter() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(formatter().format(value))
    }

    override fun deserialize(decoder: Decoder): Long {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("Next timestamp requires JSON string input")
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive
            ?: throw SerializationException("Next timestamp must be an ISO-8601 string")
        if (!primitive.isString) {
            throw SerializationException("Next timestamp must not be a numeric epoch value")
        }
        return try {
            formatter().parse(primitive.content)?.time
                ?: throw ParseException("empty timestamp", 0)
        } catch (e: Exception) {
            throw SerializationException("invalid Next ISO-8601 timestamp", e)
        }
    }
}
