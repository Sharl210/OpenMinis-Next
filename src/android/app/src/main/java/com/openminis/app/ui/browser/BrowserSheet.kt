package com.openminis.app.ui.browser

import android.widget.Toast
import com.openminis.app.R
import org.json.JSONObject
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.browser.BrowserHistoryStore
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.browser.UserAgentProfile
import com.openminis.app.ui.chat.StandardChatSheet
import kotlinx.coroutines.launch


private val START_ELEMENT_PICKER_JS = """
(function(){
  if(window.__openminisPickerActive)return;
  window.__openminisPickerActive=true; window.__openminisPickedElement=null;
  function cssPath(el){
    if(el.id && document.querySelectorAll('#'+CSS.escape(el.id)).length===1)return '#'+CSS.escape(el.id);
    var bits=[]; var n=el;
    while(n && n.nodeType===1 && n!==document.documentElement){
      var tag=n.tagName.toLowerCase();
      var stable=n.getAttribute('data-testid')||n.getAttribute('name')||n.getAttribute('aria-label');
      if(stable){bits.unshift(tag+'['+(n.hasAttribute('data-testid')?'data-testid':n.hasAttribute('name')?'name':'aria-label')+'="'+CSS.escape(stable)+'"]');break;}
      var ix=1, sib=n; while((sib=sib.previousElementSibling))if(sib.tagName===n.tagName)ix++;
      bits.unshift(tag+':nth-of-type('+ix+')'); n=n.parentElement;
    }
    return bits.join(' > ');
  }
  function onPick(ev){
    ev.preventDefault(); ev.stopPropagation(); if(ev.stopImmediatePropagation)ev.stopImmediatePropagation();
    var el=ev.target && ev.target.nodeType===1?ev.target:ev.target.parentElement; if(!el)return;
    var attrs={}; ['id','name','role','aria-label','data-testid','href','type','title'].forEach(function(k){var v=el.getAttribute(k);if(v)attrs[k]=v.slice(0,300)});
    var path=[]; var n=el; while(n&&n.nodeType===1&&n!==document.documentElement){var tag=n.tagName.toLowerCase(),i=1,s=n;while((s=s.previousElementSibling))if(s.tagName===n.tagName)i++;path.unshift(tag+':nth-of-type('+i+')');n=n.parentElement;}
    window.__openminisPickedElement={url:location.href,title:document.title,domPath:path.join(' > '),stableSelector:cssPath(el),attributes:attrs,visibleText:(el.innerText||el.textContent||'').trim().replace(/\s+/g,' ').slice(0,500)};
    window.__openminisPickerActive=false; document.removeEventListener('click',onPick,true); window.__openminisPickerHandler=null;
  }
  window.__openminisPickerHandler=onPick;
  document.addEventListener('click',onPick,true);
})();
""".trimIndent()
private const val READ_PICKED_ELEMENT_JS = "return JSON.stringify(window.__openminisPickedElement || null)"
private const val CANCEL_ELEMENT_PICKER_JS = "if(window.__openminisPickerHandler)document.removeEventListener('click',window.__openminisPickerHandler,true); window.__openminisPickerActive=false; true"

/**
 * Bottom sheet presenting the browser tab pool with tab bar, URL bar,
 * WebView, and navigation controls. Mirrors iOS BrowserSheetView.
 */
@Composable
fun BrowserSheet(
    tabPool: BrowserTabPool,
    onDismiss: () -> Unit,
    onElementSelected: (String) -> Unit = {},
) {
    val tabs by tabPool.tabs.collectAsState()
    val selectedTabId by tabPool.selectedTabId.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val selectedTab = tabs.find { it.id == selectedTabId }

    // [T-browser-user-viewing-tab] While this sheet is on screen the user is
    // looking at the selected tab, so the pool must not sleep it. The
    // requirement is explicit that a page may sleep only when the model isn't
    // executing AND the user hasn't opened it to look; without this the evictor
    // saw a user's page as idle and destroyed the WebView they were reading.
    DisposableEffect(tabPool, selectedTabId) {
        tabPool.setUserViewing(selectedTabId)
        onDispose { tabPool.setUserViewing(null) }
    }

    // [T-browser-user-viewing-tab] User-driven operations (typing a URL,
    // back/forward, reload, stop) are operations on the page too, so they
    // refresh activity just like an agent action does.
    val touchSelectedTab: () -> Unit = { selectedTab?.let { tabPool.touchTab(it.id) } }

    val currentURL = selectedTab?.manager?.currentURL?.collectAsState()?.value ?: ""
    val pageTitle = selectedTab?.manager?.pageTitle?.collectAsState()?.value ?: ""
    val isLoading = selectedTab?.manager?.isLoading?.collectAsState()?.value ?: false
    val canGoBack = selectedTab?.manager?.canGoBack?.collectAsState()?.value ?: false
    val canGoForward = selectedTab?.manager?.canGoForward?.collectAsState()?.value ?: false
    val isAgentBusy = tabPool.isAgentBusy
    val userAgentProfile = tabPool.currentUserAgentProfile.collectAsState().value

    var urlInput by remember(currentURL) { mutableStateOf(currentURL) }
    val historyStore = remember { BrowserHistoryStore.getInstance(context) }
    var showHistory by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val isBookmarked = remember(currentURL) {
        currentURL.isNotBlank() && currentURL != "about:blank" && historyStore.isBookmarked(currentURL)
    }
    var currentPageBookmarked by remember(currentURL) { mutableStateOf(isBookmarked) }
    LaunchedEffect(currentURL, showHistory) {
        if (!showHistory && currentURL.isNotBlank() && currentURL != "about:blank") {
            currentPageBookmarked = historyStore.isBookmarked(currentURL)
        }
    }
    val canBookmarkCurrentPage = currentURL.isNotBlank() && currentURL != "about:blank" && !isAgentBusy
    // [T-android-browser-download-ux] Downloads panel + badge state.
    var showDownloads by remember { mutableStateOf(false) }
    var elementPickerActive by remember { mutableStateOf(false) }
    var pickedElement by remember { mutableStateOf<BrowserElementSelectionPayload?>(null) }
    val downloadEntries by tabPool.downloads.collectAsState()

    val accent = MaterialTheme.colorScheme.primary
    val secondaryBg = MaterialTheme.colorScheme.surfaceContainer
    val tertiaryBg = MaterialTheme.colorScheme.surfaceContainerHigh

    // Mirror iOS onAppear: ensure at least one tab exists when the sheet opens.
    // Callers should also call `ensureTabForUI()` before flipping the sheet
    // visible so the first composition sees a non-empty tab list; this is a
    // defensive fallback.
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) tabPool.ensureTabForUI()
    }

    // Prevent the bottom sheet from swallowing WebView scroll gestures.
    // Returning the full delta as "consumed" on pre-scroll keeps the sheet's
    // nested-scroll dispatcher from dragging the sheet down when the user
    // scrolls inside a webpage.
    val webViewScrollGuard = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = available
        }
    }

    StandardChatSheet(
        title = pageTitle.ifEmpty { stringResource(R.string.browser_title) },
        onDismiss = onDismiss,
        leadingAction = {
            // UA-profile icon doubles as the entry point to settings, matching
            // the prior centered-title-with-icon affordance.
            IconButton(onClick = { showSettings = true }) {
                Icon(
                    when (userAgentProfile) {
                        UserAgentProfile.MOBILE_CHROME -> Icons.Default.PhoneAndroid
                        UserAgentProfile.DESKTOP_CHROME -> Icons.Default.Computer
                        UserAgentProfile.CUSTOM -> Icons.Default.Edit
                    },
                    contentDescription = stringResource(R.string.browser_settings_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Tab Bar with leading "+" and trailing History icon ──
            // (Add/History flank the tabs row so all tab-related controls
            // sit on one line, leaving the URL bar uncluttered below.)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tertiaryBg)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { scope.launch { tabPool.newTabFromUI() } },
                    enabled = tabs.size < BrowserTabPool.MAX_TABS && !isAgentBusy,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.browser_new_tab), modifier = Modifier.size(20.dp))
                }
                LazyRow(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        val title = tab.manager.pageTitle.collectAsState().value
                        val domain = tab.manager.currentURL.collectAsState().value
                            .let { url -> try { java.net.URI(url).host } catch (_: Exception) { null } }
                        val displayTitle = "${tab.id} · ${title.ifEmpty { domain ?: "Tab ${tab.id}" }}"
                            .take(28)
                        val isSelected = tab.id == selectedTabId

                        TabChip(
                            title = displayTitle,
                            isSelected = isSelected,
                            showClose = !isAgentBusy,
                            accent = accent,
                            onClick = { tabPool.selectTab(tab.id) },
                            onClose = { scope.launch { tabPool.closeTabFromUI(tab.id) } },
                        )
                    }
                    item {
                        Text(
                            "${tabs.size}/${BrowserTabPool.MAX_TABS}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            showBookmarks = false
                            showHistory = true
                        },
                        enabled = !isAgentBusy,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = stringResource(R.string.browser_history_action),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            if (canBookmarkCurrentPage) {
                                val bookmarkWrite = historyStore.toggleBookmark(currentURL, pageTitle)
                                // A failed write is rolled back by the store, so this
                                // value already describes what a restart would show.
                                // Keep the star honest and tell the user why it did
                                // nothing instead of showing a saved state that is not
                                // on disk.
                                currentPageBookmarked = bookmarkWrite.value
                                if (bookmarkWrite.failed) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.browser_library_write_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        enabled = canBookmarkCurrentPage,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            if (currentPageBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = stringResource(R.string.browser_bookmark_toggle),
                            modifier = Modifier.size(20.dp),
                            tint = if (currentPageBookmarked) Color(0xFFFFC107)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            showHistory = true
                            showBookmarks = true
                        },
                        enabled = !isAgentBusy,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.Bookmark,
                            contentDescription = stringResource(R.string.browser_bookmarks_title),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // ── URL Bar ── (compact 36dp pill — OutlinedTextField defaults to
            // ~56dp which dominates the sheet header; users want to spend the
            // space on the WebView.)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(secondaryBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(tertiaryBg, RoundedCornerShape(10.dp))
                        .border(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrowserAddressBarIcon(isLoading = isLoading, accent = accent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            enabled = !isAgentBusy,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                val trimmed = urlInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    val normalized = normalizeURLInput(trimmed)
                                    touchSelectedTab()
                                    selectedTab?.manager?.loadURL(normalized)
                                    urlInput = normalized
                                }
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (urlInput.isEmpty()) {
                            Text(
                                stringResource(R.string.browser_search_placeholder),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isLoading) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { touchSelectedTab(); selectedTab?.manager?.stopLoading() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.browser_stop),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }

            // ── WebView + overlays ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(webViewScrollGuard),
            ) {
                if (selectedTab != null && currentURL.isNotEmpty()) {
                    // Only mount the WebView once there's something to display.
                    // Mounting an empty WebView (no URL loaded) corrupts the
                    // OpenGL swap behavior for the hosting ModalBottomSheet —
                    // sibling Compose UI (top nav, URL bar, tab chips) draws
                    // blank white on the first-open case until the WebView
                    // has real content. Keep it out of the hierarchy until
                    // the user navigates somewhere.
                    BrowserWebView(
                        webView = selectedTab.manager.webView,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (selectedTab == null) stringResource(R.string.browser_no_tab_open)
                            else stringResource(R.string.browser_enter_url_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isAgentBusy) {
                    AgentBrowsingOverlay(
                        accent = accent,
                        onTakeover = { tabPool.releaseAllTabs() },
                    )
                }
            }

            // ── Download progress banner ──
            val activeDownload by tabPool.activeDownload.collectAsState()
            activeDownload?.let { dl ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(secondaryBg)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = accent,
                    )
                    Text(
                        dl.filename,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (dl.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { dl.progress },
                            modifier = Modifier.weight(1f).height(3.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.weight(1f).height(3.dp),
                        )
                    }
                }
            }

            // ── Bottom Toolbar ──
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(secondaryBg)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarIcon(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDesc = stringResource(R.string.browser_nav_back),
                    enabled = canGoBack && !isAgentBusy,
                    onClick = { touchSelectedTab(); selectedTab?.manager?.goBack() },
                )
                ToolbarIcon(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDesc = stringResource(R.string.browser_nav_forward),
                    enabled = canGoForward && !isAgentBusy,
                    onClick = { touchSelectedTab(); selectedTab?.manager?.goForward() },
                )
                // [T-android-browser-download-ux] Downloads entry: shows while
                // the session has ANY download records, disappears when the
                // user clears the last one; the badge counts in-flight +
                // unviewed terminal entries and hides at 0 (iOS v2 spec).
                if (downloadEntries.isNotEmpty()) {
                    val badgeCount = tabPool.downloadBadgeCount(downloadEntries)
                    androidx.compose.material3.BadgedBox(
                        badge = {
                            if (badgeCount > 0) {
                                androidx.compose.material3.Badge { Text("$badgeCount") }
                            }
                        },
                    ) {
                        ToolbarIcon(
                            icon = Icons.Default.Download,
                            contentDesc = stringResource(R.string.browser_downloads_title),
                            enabled = true,
                            tint = accent,
                            onClick = { showDownloads = true },
                        )
                    }
                }
                if (isLoading) {
                    ToolbarIcon(
                        icon = Icons.Default.Close,
                        contentDesc = stringResource(R.string.browser_stop),
                        enabled = !isAgentBusy,
                        tint = accent,
                        onClick = { touchSelectedTab(); selectedTab?.manager?.stopLoading() },
                    )
                } else {
                    ToolbarIcon(
                        icon = Icons.Default.Refresh,
                        contentDesc = stringResource(R.string.browser_reload),
                        enabled = !isAgentBusy,
                        tint = accent,
                        onClick = { touchSelectedTab(); selectedTab?.manager?.reload() },
                    )
                }
                ToolbarIcon(
                    icon = Icons.Default.Edit,
                    contentDesc = stringResource(R.string.browser_pick_element),
                    enabled = selectedTab != null && !isAgentBusy && !elementPickerActive,
                    tint = if (elementPickerActive) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = {
                        val tab = selectedTab ?: return@ToolbarIcon
                        val manager = tab.manager
                        scope.launch {
                            val armed = manager.execute(BrowserActionInput(action = BrowserAction.EXECUTE_JS, script = START_ELEMENT_PICKER_JS))
                            if (!armed.success) {
                                // The picker never armed, so nothing on the page is listening. This used to
                                // return silently and the tap looked like it did nothing at all.
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.browser_element_pick_unavailable),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@launch
                            }
                            elementPickerActive = true
                            repeat(ELEMENT_PICK_POLLS) { attempt ->
                                delay(ELEMENT_PICK_POLL_MILLIS)
                                if (tabPool.selectedTabId.value != tab.id) {
                                    // The user moved to another tab, so the pick is void by their own action.
                                    // This is the one exit that stays silent on purpose.
                                    manager.execute(BrowserActionInput(action = BrowserAction.EXECUTE_JS, script = CANCEL_ELEMENT_PICKER_JS))
                                    elementPickerActive = false
                                    return@launch
                                }
                                val result = manager.execute(BrowserActionInput(action = BrowserAction.EXECUTE_JS, script = READ_PICKED_ELEMENT_JS))
                                when (val poll = elementPickPoll(result.success, result.text, ELEMENT_PICK_POLLS - 1 - attempt)) {
                                    is ElementPickPoll.Picked -> {
                                        val picked = poll.json
                                        pickedElement = BrowserElementSelectionPayload(
                                            pageId = tab.pageId,
                                            runtimeTabId = tab.id,
                                            url = picked.optString("url", currentURL),
                                            title = picked.optString("title", pageTitle),
                                            domPath = picked.optString("domPath"),
                                            stableSelector = picked.optString("stableSelector"),
                                            attributes = picked.optJSONObject("attributes")?.let { attrs ->
                                                attrs.keys().asSequence().associateWith { key -> attrs.optString(key) }
                                            }.orEmpty(),
                                            visibleText = picked.optString("visibleText"),
                                        )
                                        elementPickerActive = false
                                        return@launch
                                    }
                                    ElementPickPoll.Waiting -> Unit
                                    ElementPickPoll.TimedOut -> {
                                        // The poll window closed with nothing picked. The page used to be
                                        // disarmed here and the user was told nothing, so the only way to find
                                        // out it had given up was to guess.
                                        manager.execute(BrowserActionInput(action = BrowserAction.EXECUTE_JS, script = CANCEL_ELEMENT_PICKER_JS))
                                        elementPickerActive = false
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.browser_element_pick_timeout),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        return@launch
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    pickedElement?.let { payload ->
        AlertDialog(
            onDismissRequest = { pickedElement = null },
            title = { Text(stringResource(R.string.browser_element_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${payload.title} · ${payload.runtimeTabId}", style = MaterialTheme.typography.labelMedium)
                    Text(payload.stableSelector, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (payload.visibleText.isNotBlank()) Text(payload.visibleText, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onElementSelected(payload.toPromptSnippet())
                    pickedElement = null
                }) { Text(stringResource(R.string.browser_element_send)) }
            },
            dismissButton = {
                TextButton(onClick = { pickedElement = null }) { Text(stringResource(R.string.browser_element_cancel)) }
            },
        )
    }

    if (showDownloads) {
        BrowserDownloadsSheet(
            tabPool = tabPool,
            onDismiss = { showDownloads = false },
        )
    }

    if (showHistory) {
        BrowserHistorySheet(
            historyStore = historyStore,
            initialBookmarks = showBookmarks,
            // [T-android-browser-history-no-tab] C1: with no tabs open,
            // selectedTab is null and the old safe-call silently dropped the
            // navigation. Create a tab first (same path as the "+" button);
            // if the pool refuses (MAX_TABS), just dismiss the sheet.
            onNavigate = { url ->
                scope.launch {
                    val tab = selectedTab ?: tabPool.newTabFromUI()
                    tab?.manager?.loadURL(url)
                    showHistory = false
                }
            },
            onDismiss = { showHistory = false },
        )
    }

    if (showSettings) {
        BrowserSettingsSheet(
            tabPool = tabPool,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun TabChip(
    title: String,
    isSelected: Boolean,
    showClose: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val bg = if (isSelected) accent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor = if (isSelected) accent.copy(alpha = 0.4f) else Color.Transparent
    val textColor = if (isSelected) accent else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .background(bg, CircleShape)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = if (showClose) 6.dp else 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showClose) {
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(16.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.browser_close_tab),
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolbarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon,
            contentDescription = contentDesc,
            modifier = Modifier.size(22.dp),
            tint = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                else tint ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Globe icon with a spinning arc border when loading. */
@Composable
private fun BrowserAddressBarIcon(isLoading: Boolean, accent: Color) {
    val transition = rememberInfiniteTransition(label = "addrIcon")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "angle",
    )
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Language,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (isLoading) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(22.dp)
                    .rotate(angle),
                color = accent,
                strokeWidth = 1.5.dp,
            )
        }
    }
}

/** Breathing-light overlay shown when the agent is controlling the browser. */
@Composable
private fun AgentBrowsingOverlay(accent: Color, onTakeover: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "breathing")
    val breathingAlpha by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "breathingAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accent.copy(alpha = breathingAlpha), CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.browser_minis_browsing),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(Color.White.copy(alpha = 0.3f)),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.browser_takeover),
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onTakeover),
            )
        }
    }
}

/** Normalize URL input: search terms → Google search, bare domains → https:// prefix. */
private fun normalizeURLInput(input: String): String {
    val trimmed = input.trim()
    if (trimmed.contains("://")) return trimmed
    if (trimmed.contains(' ') || !trimmed.contains('.')) {
        return "https://www.google.com/search?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
    }
    return "https://$trimmed"
}


/**
 * How many polls an armed element picker takes before it gives up.
 *
 * 240 x 250 ms is the 60 s window this picker has always used; naming the two numbers
 * is what lets a test pin the window instead of re-deriving it from the loop.
 */
internal const val ELEMENT_PICK_POLLS = 240
internal const val ELEMENT_PICK_POLL_MILLIS = 250L

/** What one poll of an armed element picker concluded. */
internal sealed interface ElementPickPoll {
    /** The page answered with an element to send. */
    data class Picked(val json: JSONObject) : ElementPickPoll

    /** Still armed and nothing picked yet. */
    data object Waiting : ElementPickPoll

    /** The poll window closed with nothing picked, so the user has to be told. */
    data object TimedOut : ElementPickPoll
}

/**
 * The rule an armed element picker polls on.
 *
 * Extracted out of the composable so a JVM test can drive it: this decides what counts
 * as a pick, and when the picker gives up. Before the extraction the give-up lived in
 * "the loop ended", which a test cannot reach and which is why the user was never told.
 *
 * @param readSucceeded the read script's own result; a failed read is not a pick.
 * @param readText its answer — the literal `null` while the page is armed and unclicked.
 * @param pollsLeftAfterThis how many polls remain after this one. This is what makes the
 *   last poll terminate: without it the loop cannot tell "the window closed" from "keep
 *   waiting", which is exactly how the silent timeout came about.
 */
internal fun elementPickPoll(
    readSucceeded: Boolean,
    readText: String,
    pollsLeftAfterThis: Int,
): ElementPickPoll {
    val picked = if (readSucceeded) parsePickedElement(readText) else null
    return when {
        picked != null -> ElementPickPoll.Picked(picked)
        pollsLeftAfterThis > 0 -> ElementPickPoll.Waiting
        else -> ElementPickPoll.TimedOut
    }
}

/**
 * The page's answer as an element to send, or null when there is nothing to send.
 *
 * A failed read, the literal `null` a still-armed page returns, and text that is not a
 * JSON object all mean the same thing here: nothing picked yet.
 */
internal fun parsePickedElement(readText: String): JSONObject? {
    val text = readText.trim()
    if (text.isEmpty() || text == "null") return null
    return runCatching { JSONObject(text) }.getOrNull()
}