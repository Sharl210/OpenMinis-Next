package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.ThinkingLevel

/**
 * [T-android-assistant-attribution] Per-message model attribution.
 *
 * The requirement is explicit about the why: the user switches models mid-thread
 * and needs to see, for THIS reply, which model produced it and at which
 * thinking level — "我需要它这个是我能够就是它能够具体去反映我这一句会话，这一句回复
 * 是哪一个模型输出的，哪一个模型的用的哪一个推理档位进行输出的，这样我就能看得清楚".
 * The requested presentation is the one already used under the chat title
 * ("就把那一个搬下来就行"), and the size/colour of the existing header text
 * ("字体大小也给它改大一点，就是跟这个minis默认的这个字体大小同步").
 *
 * CORRECTION [T-android-thinking-level-persist] — this block used to claim that
 * "all of this data was already persisted per message (`model_display_name`,
 * `provider_type`, `thinking_level`)". That was false twice over, and the false
 * part is what kept the bug alive:
 *
 *  - there was no `thinking_level` column at all (only three of the four
 *    attribution columns existed, all added by MIGRATION_11_12), so the level
 *    was persisted nowhere and `historicalLevel != null` below could only ever
 *    be true for a message still in memory;
 *  - and the three columns that DID exist never reached `ChatMessage`: the
 *    DB → UI mapping (`ChatViewModel.toChatMessages`) dropped all of them, so
 *    `assistantHeaderSnapshot` was null for every row restored from disk and the
 *    header fell back to the Soul name — on restart the model name was gone too,
 *    not just the level.
 *
 * Both halves are now real: `messages.thinking_level` (MIGRATION_13_14, written
 * by `ChatRepository.appendMessage` from the turn's own level) and
 * `ChatMessage.withPersistedAttribution`, which the load path applies. The
 * sentence above is kept only as the reason this section exists, not as a claim
 * about the schema; the assertions that pin it live in
 * MessageThinkingLevelPersistenceTest.
 *
 * The other half of the story is unchanged: the header was `AssistantHeader()`
 * with no parameters, so it always rendered the Soul name ("Minis") and the
 * attribute was dead. The user could not tell which model answered.
 *
 * The two pure helpers below are split out from the composables on purpose:
 * the text that reaches the user is then testable on the JVM, without a
 * Compose runtime.
 */

/**
 * Human-readable provider name for a persisted `provider_type`.
 *
 * The DB stores the enum NAME (`ProviderType.name`, written by
 * `ProviderConfigMapping`), so it must be mapped back through the enum to reach
 * the display form ("Anthropic", "Google Gemini", …). An unrecognised value is
 * returned as-is rather than dropped: a provider added on another platform (or
 * a future enum entry) should still show *something* identifiable, because
 * showing nothing is exactly the failure this whole change fixes.
 */
internal fun providerDisplayName(providerType: String?): String? {
    val raw = providerType?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return ProviderType.entries.firstOrNull { it.name == raw }?.displayName ?: raw
}

/**
 * `"Provider · Model"` with the middle dot, matching the chat title's subtitle.
 *
 * Degrades rather than disappearing: with only a model name it shows the model,
 * with only a provider it shows the provider. Returns null only when there is no
 * identity at all, which is the caller's signal to fall back to the legacy
 * (Soul name) header.
 */
internal fun formatAssistantAttribution(snapshot: AssistantHeaderSnapshot?): String? {
    val provider = providerDisplayName(snapshot?.providerType)
    val model = snapshot?.modelDisplayName?.trim().orEmpty()
    return when {
        !provider.isNullOrEmpty() && model.isNotEmpty() -> "$provider · $model"
        model.isNotEmpty() -> model
        !provider.isNullOrEmpty() -> provider
        else -> null
    }
}

/**
 * Thinking-level capsule, shared by the chat title and the per-message header.
 *
 * Extracted rather than copied: a second hand-rolled capsule is how the
 * title/message variants of this concept would drift apart, which is the defect
 * class this codebase keeps paying for. [onClick] is nullable because the two
 * surfaces mean different things — under the title the badge is a CONTROL for
 * the current session, while on a past message it is a historical RECORD of the
 * level that produced that reply. A record that silently retargets the session
 * on tap would be a lie, so those callers pass null and get a static capsule.
 */
@Composable
internal fun ThinkingLevelBadge(
    level: ThinkingLevel,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // Secondary grey for icon + label (iOS secondaryText parity) — no accent.
    val badgeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val rowModifier = Modifier
        .clip(RoundedCornerShape(50))
        // Faint translucent-grey capsule (iOS Color.secondary.opacity(0.10)).
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = if (onClick != null) {
            // Own clickable → consumes the tap, opens the thinking sheet.
            rowModifier.clickable(onClick = onClick).padding(horizontal = 5.dp, vertical = 1.dp)
        } else {
            rowModifier.padding(horizontal = 5.dp, vertical = 1.dp)
        },
    ) {
        Icon(
            imageVector = Icons.Default.Lightbulb,
            contentDescription = null,
            // Dimmed in the Off state (sheet Off-row convention) so "Off" reads
            // as "thinking disabled" at a glance.
            tint = if (level.isEnabled) badgeColor else badgeColor.copy(alpha = 0.4f),
            modifier = Modifier.size(9.dp),
        )
        Text(
            text = level.localizedName(context),
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Medium,
            color = badgeColor,
            maxLines = 1,
        )
    }
}
