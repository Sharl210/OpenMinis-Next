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
    val title: String,
    val description: String,
) {
    HIDDEN("关闭", "不显示工作步骤更新。"),
    SUMMARY("摘要", "显示阶段变化和简短进度摘要。"),
    CURRENT_STEP("当前步骤", "显示正在执行的步骤及最新状态。"),
    DETAILED("详细", "显示每个步骤，并在可用时显示执行结果。");

    companion object {
        fun fromStored(value: String?): WorkStepDisplay =
            value?.let { runCatching { valueOf(it) }.getOrNull() } ?: SUMMARY
    }
}

/** Agent 准备发送消息或动作时的默认策略。 */
enum class DefaultSendStrategy(
    val title: String,
    val description: String,
) {
    QUEUE("Queue（排队）", "排到下一个回合发送，不打断当前工作。"),
    STEER("Steer（引导）", "将内容作为当前 Agent 下一步的引导发送。");

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
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Agent 行为") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
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
                title = "工作步骤展示",
                description = "选择 Agent 工作时显示多少进度信息。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkStepDisplay.entries.forEach { option ->
                        ChoiceRow(
                            title = option.title,
                            description = option.description,
                            selected = workStepDisplay == option,
                            onClick = { onWorkStepDisplayChange(option) },
                        )
                    }
                }
            }

            SettingsChoiceSection(
                title = "递归深度",
                description = "限制子 Agent 继续委派的层数，默认值为 2。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0 to "深度 0：不委派，任务留在当前 Agent。",
                        1 to "深度 1：当前 Agent 可以直接委派子 Agent。",
                        2 to "深度 2：子 Agent 还可以再委派一层。",
                    ).forEach { (depth, description) ->
                        ChoiceRow(
                            title = "深度 $depth",
                            description = description,
                            selected = recursionDepth == depth,
                            onClick = { onRecursionDepthChange(depth) },
                        )
                    }
                }
            }

            SettingsChoiceSection(
                title = "并行 Agent 上限",
                description = "同时运行的子 Agent 数量，默认值为 5。",
            ) {
                val displayedLimit = parallelAgentLimit.coerceIn(
                    AgentBehaviorSettingsPrefs.MIN_PARALLEL_AGENTS,
                    AgentBehaviorSettingsPrefs.MAX_PARALLEL_AGENTS,
                )
                ValueSliderRow(
                    title = "同时运行的子 Agent",
                    valueLabel = displayedLimit.toString(),
                    value = displayedLimit.toFloat(),
                    valueRange = AgentBehaviorSettingsPrefs.MIN_PARALLEL_AGENTS.toFloat()..
                        AgentBehaviorSettingsPrefs.MAX_PARALLEL_AGENTS.toFloat(),
                    steps = AgentBehaviorSettingsPrefs.MAX_PARALLEL_AGENTS -
                        AgentBehaviorSettingsPrefs.MIN_PARALLEL_AGENTS - 1,
                    onValueChange = { onParallelAgentLimitChange(it.roundToInt()) },
                )
            }

            SettingsChoiceSection(
                title = "默认发送策略",
                description = "消息或动作准备好发送时采用的默认方式。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DefaultSendStrategy.entries.forEach { option ->
                        ChoiceRow(
                            title = option.title,
                            description = option.description,
                            selected = defaultSendStrategy == option,
                            onClick = { onDefaultSendStrategyChange(option) },
                        )
                    }
                }
            }

            SettingsChoiceSection(
                title = "全局压缩阈值",
                description = "上下文达到该 Token 数时允许自动压缩；设为 0 表示关闭。",
            ) {
                val displayedThreshold = compactThresholdTokens.coerceIn(
                    AgentBehaviorSettingsPrefs.MIN_COMPACT_THRESHOLD_TOKENS,
                    AgentBehaviorSettingsPrefs.MAX_COMPACT_THRESHOLD_TOKENS,
                )
                ValueSliderRow(
                    title = "触发阈值",
                    valueLabel = if (displayedThreshold == 0) "关闭" else "$displayedThreshold tokens",
                    value = displayedThreshold.toFloat(),
                    valueRange = AgentBehaviorSettingsPrefs.MIN_COMPACT_THRESHOLD_TOKENS.toFloat()..
                        AgentBehaviorSettingsPrefs.MAX_COMPACT_THRESHOLD_TOKENS.toFloat(),
                    steps = AgentBehaviorSettingsPrefs.MAX_COMPACT_THRESHOLD_TOKENS -
                        AgentBehaviorSettingsPrefs.MIN_COMPACT_THRESHOLD_TOKENS - 1,
                    onValueChange = { onCompactThresholdTokensChange(it.roundToInt()) },
                )
            }

            SettingsChoiceSection(
                title = "自动重试",
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
