package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import kotlin.math.roundToInt

/** 工作步骤进度的展示粒度。 */
enum class WorkStepDisplay(
    val titleRes: Int,
    val descriptionRes: Int,
) {
    HIDDEN(R.string.agent_behavior_work_step_hidden, R.string.agent_behavior_work_step_hidden_description),
    SUMMARY(R.string.agent_behavior_work_step_summary, R.string.agent_behavior_work_step_summary_description),
    CURRENT_STEP(R.string.agent_behavior_work_step_current, R.string.agent_behavior_work_step_current_description),
    DETAILED(R.string.agent_behavior_work_step_detailed, R.string.agent_behavior_work_step_detailed_description);

    companion object {
        fun fromStored(value: String?): WorkStepDisplay =
            value?.let { runCatching { valueOf(it) }.getOrNull() } ?: SUMMARY
    }
}

/** Agent 准备发送消息或动作时的默认策略。 */
enum class DefaultSendStrategy(
    val titleRes: Int,
    val descriptionRes: Int,
) {
    QUEUE(R.string.agent_behavior_send_queue, R.string.agent_behavior_send_queue_description),
    STEER(R.string.agent_behavior_send_steer, R.string.agent_behavior_send_steer_description);

    companion object {
        fun fromStored(value: String?): DefaultSendStrategy =
            value?.let { runCatching { valueOf(it) }.getOrNull() } ?: QUEUE
    }
}

/**
 * Agent 行为设置页。状态由调用方持有，回调负责保存并同步到运行时。
 * 页面本身不依赖 runtime 或 ModelsDevApi，方便从不同入口复用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBehaviorSettingsScreen(
    onBack: () -> Unit,
    workStepDisplay: WorkStepDisplay,
    onWorkStepDisplayChange: (WorkStepDisplay) -> Unit,
    recursionDepth: Int,
    onRecursionDepthChange: (Int) -> Unit,
    parallelAgentLimit: Int,
    onParallelAgentLimitChange: (Int) -> Unit,
    defaultSendStrategy: DefaultSendStrategy,
    onDefaultSendStrategyChange: (DefaultSendStrategy) -> Unit,
    compactThresholdTokens: Int,
    onCompactThresholdTokensChange: (Int) -> Unit,
    autoRetryEnabled: Boolean,
    onAutoRetryEnabledChange: (Boolean) -> Unit,
    maxRetryAttempts: Int,
    onMaxRetryAttemptsChange: (Int) -> Unit,
    webSearchMaxResults: Int,
    onWebSearchMaxResultsChange: (Int) -> Unit,
    webRequestTimeoutMs: Long,
    onWebRequestTimeoutMsChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_behavior_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.agent_behavior_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsChoiceSection(
                title = stringResource(R.string.agent_behavior_work_step_title),
                description = stringResource(R.string.agent_behavior_work_step_section_description),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkStepDisplay.entries.forEach { option ->
                        ChoiceRow(
                title = option.titleRes.let { stringResource(it) },
                            description = option.descriptionRes.let { stringResource(it) },
                            selected = workStepDisplay == option,
                            onClick = { onWorkStepDisplayChange(option) },
                        )
                    }
                }
            }

            SettingsChoiceSection(
                title = stringResource(R.string.agent_behavior_recursion_depth_title),
                description = stringResource(R.string.agent_behavior_recursion_depth_description),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0 to stringResource(R.string.agent_behavior_depth_zero),
                        1 to stringResource(R.string.agent_behavior_depth_one),
                        2 to stringResource(R.string.agent_behavior_depth_two),
                    ).forEach { (depth, description) ->
                        ChoiceRow(
                            title = stringResource(R.string.agent_behavior_depth_value, depth),
                            description = description,
                            selected = recursionDepth == depth,
                            onClick = { onRecursionDepthChange(depth) },
                        )
                    }
                }
            }

            SettingsChoiceSection(
                title = stringResource(R.string.agent_behavior_parallel_title),
                description = stringResource(R.string.agent_behavior_parallel_description),
            ) {
                val pendingParallelLimit = remember(parallelAgentLimit) {
                    mutableIntStateOf(parallelAgentLimit)
                }
                val displayedLimit = pendingParallelLimit.intValue.coerceIn(
                    AgentBehaviorSettingsPrefs.MIN_PARALLEL_AGENTS,
                    AgentBehaviorSettingsPrefs.MAX_PARALLEL_AGENTS,
                )
                ValueSliderRow(
                    title = stringResource(R.string.agent_behavior_parallel_value),
                    valueLabel = displayedLimit.toString(),
                    value = displayedLimit.toFloat(),
                    valueRange = AgentBehaviorSettingsPrefs.MIN_PARALLEL_AGENTS.toFloat()..
                        AgentBehaviorSettingsPrefs.MAX_PARALLEL_AGENTS.toFloat(),
                    steps = AgentBehaviorSettingsPrefs.MAX_PARALLEL_AGENTS -
                        AgentBehaviorSettingsPrefs.MIN_PARALLEL_AGENTS - 1,
                    onValueChange = { pendingParallelLimit.intValue = it.roundToInt() },
                    onValueChangeFinished = { onParallelAgentLimitChange(displayedLimit) },
                )
            }

            SettingsChoiceSection(
                title = stringResource(R.string.agent_behavior_send_strategy_title),
                description = stringResource(R.string.agent_behavior_send_strategy_description),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DefaultSendStrategy.entries.forEach { option ->
                        ChoiceRow(
                title = option.titleRes.let { stringResource(it) },
                            description = option.descriptionRes.let { stringResource(it) },
                            selected = defaultSendStrategy == option,
                            onClick = { onDefaultSendStrategyChange(option) },
                        )
                    }
                }
            }

            SettingsChoiceSection(
                // [T-android-compact-headroom-wording] This control sets a RESERVE
                // (headroom), not an absolute threshold, and the label has to say
                // so.
                //
                // It used to read "全局压缩阈值 / 上下文达到该 Token 数时允许自动
                // 压缩" with the slider marked "150 tokens". Three things were
                // wrong at once: the number is in THOUSANDS (150 means 150_000,
                // see the `times(1_000)` at both call sites), the quantity is the
                // space RESERVED at the end of the window rather than the point
                // compaction fires, and because it is a reserve, a LARGER value
                // compacts EARLIER — the opposite of what "threshold" tells a
                // reader. A user following the old text set the slider up to
                // delay compaction and got the reverse.
                //
                // The reserve model is also the one that works: 150_000 as an
                // absolute threshold would exceed a 128K window entirely, while
                // as a reserve it degrades sensibly via the clamp in
                // `ContextPolicy.forContextWindow`. So the wiring stays and the
                // wording is corrected.
                title = stringResource(R.string.agent_behavior_compact_title),
                description = stringResource(R.string.agent_behavior_compact_description),
            ) {
                val displayedThreshold = compactThresholdTokens.coerceIn(
                    AgentBehaviorSettingsPrefs.MIN_COMPACT_THRESHOLD_TOKENS,
                    AgentBehaviorSettingsPrefs.MAX_COMPACT_THRESHOLD_TOKENS,
                )
                ValueSliderRow(
                    title = stringResource(R.string.agent_behavior_compact_value),
                    valueLabel = if (displayedThreshold == 0) {
                        stringResource(R.string.agent_behavior_off)
                    } else {
                        // The stored value is thousands of tokens; show the real
                        // figure so the label and the behaviour agree.
                        stringResource(R.string.agent_behavior_compact_value_tokens, displayedThreshold)
                    },
                    value = displayedThreshold.toFloat(),
                    valueRange = AgentBehaviorSettingsPrefs.MIN_COMPACT_THRESHOLD_TOKENS.toFloat()..
                        AgentBehaviorSettingsPrefs.MAX_COMPACT_THRESHOLD_TOKENS.toFloat(),
                    steps = AgentBehaviorSettingsPrefs.MAX_COMPACT_THRESHOLD_TOKENS -
                        AgentBehaviorSettingsPrefs.MIN_COMPACT_THRESHOLD_TOKENS - 1,
                    onValueChange = { onCompactThresholdTokensChange(it.roundToInt()) },
                )
            }

            SettingsChoiceSection(
                title = stringResource(R.string.agent_behavior_web_search),
                description = stringResource(R.string.agent_behavior_web_search_description),
            ) {
                val displayedResults = webSearchMaxResults.coerceIn(
                    AgentBehaviorSettingsPrefs.MIN_WEB_SEARCH_MAX_RESULTS,
                    AgentBehaviorSettingsPrefs.MAX_WEB_SEARCH_MAX_RESULTS,
                )
                ValueSliderRow(
                    title = stringResource(R.string.agent_behavior_web_search_max_results),
                    valueLabel = stringResource(R.string.agent_behavior_web_search_max_results_value, displayedResults),
                    value = displayedResults.toFloat(),
                    valueRange = AgentBehaviorSettingsPrefs.MIN_WEB_SEARCH_MAX_RESULTS.toFloat()..
                        AgentBehaviorSettingsPrefs.MAX_WEB_SEARCH_MAX_RESULTS.toFloat(),
                    steps = AgentBehaviorSettingsPrefs.MAX_WEB_SEARCH_MAX_RESULTS -
                        AgentBehaviorSettingsPrefs.MIN_WEB_SEARCH_MAX_RESULTS - 1,
                    onValueChange = { onWebSearchMaxResultsChange(it.roundToInt()) },
                )
                val displayedTimeoutMs = webRequestTimeoutMs.coerceIn(
                    AgentBehaviorSettingsPrefs.MIN_WEB_REQUEST_TIMEOUT_MS,
                    AgentBehaviorSettingsPrefs.MAX_WEB_REQUEST_TIMEOUT_MS,
                )
                ValueSliderRow(
                    title = stringResource(R.string.agent_behavior_web_request_timeout),
                    valueLabel = stringResource(
                        R.string.agent_behavior_web_request_timeout_value,
                        displayedTimeoutMs / 1_000L,
                    ),
                    value = (displayedTimeoutMs / 1_000L).toFloat(),
                    valueRange = (AgentBehaviorSettingsPrefs.MIN_WEB_REQUEST_TIMEOUT_MS / 1_000L).toFloat()..
                        (AgentBehaviorSettingsPrefs.MAX_WEB_REQUEST_TIMEOUT_MS / 1_000L).toFloat(),
                    steps = ((AgentBehaviorSettingsPrefs.MAX_WEB_REQUEST_TIMEOUT_MS -
                        AgentBehaviorSettingsPrefs.MIN_WEB_REQUEST_TIMEOUT_MS) / 1_000L - 1).toInt(),
                    onValueChange = { onWebRequestTimeoutMsChange(it.roundToInt() * 1_000L) },
                )
            }

            SettingsChoiceSection(
                title = stringResource(R.string.agent_behavior_auto_retry_title),
                description = stringResource(R.string.agent_behavior_retry_description),
            ) {
                ToggleRow(
                    title = stringResource(R.string.agent_behavior_retry_enabled),
                    description = stringResource(R.string.agent_behavior_retry_enabled_description),
                    checked = autoRetryEnabled,
                    onCheckedChange = onAutoRetryEnabledChange,
                )
                val displayedAttempts = AgentBehaviorSettingsPrefs.normalizeMaxRetryAttempts(maxRetryAttempts)
                val attemptsLabel = when {
                    displayedAttempts == -1 -> stringResource(R.string.agent_behavior_retry_unlimited)
                    displayedAttempts == 0 -> stringResource(R.string.agent_behavior_retry_disabled)
                    else -> stringResource(R.string.agent_behavior_retry_bounded, displayedAttempts)
                }
                ValueSliderRow(
                    title = stringResource(R.string.agent_behavior_retry_max_attempts),
                    valueLabel = attemptsLabel,
                    value = displayedAttempts.toFloat(),
                    valueRange = AgentBehaviorSettingsPrefs.MIN_RETRY_ATTEMPTS.toFloat()..
                        AgentBehaviorSettingsPrefs.MAX_RETRY_ATTEMPTS.toFloat(),
                    steps = AgentBehaviorSettingsPrefs.MAX_RETRY_ATTEMPTS -
                        AgentBehaviorSettingsPrefs.MIN_RETRY_ATTEMPTS - 1,
                    onValueChange = { onMaxRetryAttemptsChange(it.roundToInt()) },
                    enabled = autoRetryEnabled,
                )
            }
        }
    }
}

@Composable
private fun SettingsChoiceSection(
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ValueSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            valueLabel,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(valueRange.start.toInt().toString(), style = MaterialTheme.typography.labelSmall)
        Text(valueRange.endInclusive.toInt().toString(), style = MaterialTheme.typography.labelSmall)
    }
}
