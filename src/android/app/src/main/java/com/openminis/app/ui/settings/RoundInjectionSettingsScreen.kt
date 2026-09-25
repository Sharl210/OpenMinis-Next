package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.feature.runtime.RoundInjectionSettingsPrefs
import com.openminis.app.feature.runtime.RoundInjectionSettings

/**
 * Settings UI for DSH-compatible round prompt injection.
 *
 * Persistence stays with the caller so this screen remains independent from
 * ChatViewModel and the runtime provider. Each accepted edit is reported to
 * [onSettingsChange] immediately, matching the other execution settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundInjectionSettingsScreen(
    onBack: () -> Unit,
    settings: RoundInjectionSettings,
    onSettingsChange: (RoundInjectionSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var intervalText by remember(settings.interval) {
        mutableStateOf(settings.interval.toString())
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.round_injection_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsSection(
                header = stringResource(R.string.round_injection_section_general),
                footer = stringResource(R.string.round_injection_footer),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.round_injection_enabled),
                    subtitle = stringResource(R.string.round_injection_enabled_description),
                    checked = settings.enabled,
                    onCheckedChange = { onSettingsChange(settings.copy(enabled = it)) },
                )
            }

            SettingsSection(
                header = stringResource(R.string.round_injection_section_start),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.round_injection_start_enabled),
                    subtitle = stringResource(R.string.round_injection_start_enabled_description),
                    checked = settings.injectOnStart,
                    enabled = settings.enabled,
                    onCheckedChange = { onSettingsChange(settings.copy(injectOnStart = it)) },
                )
                SettingsCardBlock {
                    OutlinedTextField(
                        value = settings.startPrompt,
                        onValueChange = { onSettingsChange(settings.copy(startPrompt = it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        enabled = settings.enabled && settings.injectOnStart,
                        label = { Text(stringResource(R.string.round_injection_start_prompt)) },
                        placeholder = { Text(stringResource(R.string.round_injection_start_prompt_placeholder)) },
                    )
                }
            }

            SettingsSection(
                header = stringResource(R.string.round_injection_section_periodic),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.round_injection_periodic_enabled),
                    subtitle = stringResource(R.string.round_injection_periodic_enabled_description),
                    checked = settings.periodicEnabled,
                    enabled = settings.enabled,
                    onCheckedChange = { onSettingsChange(settings.copy(periodicEnabled = it)) },
                )
                SettingsCardBlock {
                    OutlinedTextField(
                        value = settings.periodicPrompt,
                        onValueChange = { onSettingsChange(settings.copy(periodicPrompt = it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        enabled = settings.enabled && settings.periodicEnabled,
                        label = { Text(stringResource(R.string.round_injection_periodic_prompt)) },
                        placeholder = { Text(stringResource(R.string.round_injection_periodic_prompt_placeholder)) },
                    )
                }
                SettingsCardBlock {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { value ->
                            intervalText = value.filter(Char::isDigit)
                            intervalText.toIntOrNull()?.let { parsed ->
                                onSettingsChange(
                                    settings.copy(
                                        interval = parsed.coerceIn(
                                            RoundInjectionSettingsPrefs.MIN_INTERVAL,
                                            RoundInjectionSettingsPrefs.MAX_INTERVAL,
                                        ),
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = settings.enabled && settings.periodicEnabled,
                        label = { Text(stringResource(R.string.round_injection_interval)) },
                        supportingText = {
                            Text(stringResource(R.string.round_injection_interval_description))
                        },
                        suffix = {
                            Text(stringResource(R.string.round_injection_interval_suffix))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        }
    }
}
