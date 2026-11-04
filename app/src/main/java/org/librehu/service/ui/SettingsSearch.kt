package org.librehu.service.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.librehu.service.R
import org.librehu.service.bridge.BridgeItem
import org.librehu.service.bridge.BridgeSettings
import java.text.Normalizer
import java.util.Locale

/**
 * One thing the search can find: a menu (a page of the rail), a group of a page, or a setting. The settings come from
 * the pages the service describes to the phone app ([BridgeSettings]): the same labels and hints as on screen, and the
 * setting itself, changed right from the results.
 */
internal class SearchEntry(
    val tab: Tab,
    val title: String,
    /** Where it is: rail entry › page › group. */
    val path: String,
    val item: BridgeItem?,
    /** Title, path and hint without case nor accents, for the match. */
    val text: String,
    val key: String,
)

internal object SettingsIndex {
    /** Bridge page id → page of the service that shows it. */
    private val PAGE_TABS =
        mapOf(
            "audio" to Tab.AUDIO,
            "vehicle" to Tab.VEHICLE,
            "reverse" to Tab.REVERSE,
            "video" to Tab.VIDEO,
            "obd" to Tab.OBD,
            "bluetooth" to Tab.BLUETOOTH,
            "projection" to Tab.ANDROID_AUTO,
            "display" to Tab.DISPLAY,
            "lights" to Tab.LIGHTS,
            "system" to Tab.SYSTEM,
            "time" to Tab.GPS,
            "tweaks" to Tab.TWEAKS,
        )

    fun normalise(s: String): String =
        Normalizer
            .normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)

    /** Every menu, group and setting. Builds the phone pages (some read other apps): off the main thread. */
    fun build(context: Context): List<SearchEntry> {
        val r: (Int) -> String = context::getString
        val out = ArrayList<SearchEntry>()
        fun where(tab: Tab): String {
            val section = Section.of(tab)
            val rail = r(section.label)
            val page = r(tab.label)
            return if (rail == page) rail else "$rail › $page"
        }
        for (tab in Tab.entries) {
            val title = r(tab.label)
            out += SearchEntry(tab, title, r(Section.of(tab).label), null, normalise(title + " " + r(Section.of(tab).label)), "tab:${tab.name}")
        }
        // A group named like another page belongs to that page (the phone shows Phone, Network, Touch… together).
        val byLabel = Tab.entries.associateBy { r(it.label) } + (r(R.string.can_keys) to Tab.CAN)
        val pages = runCatching { BridgeSettings.pages(context) }.getOrDefault(emptyList())
        for (p in pages) {
            val pageTab = PAGE_TABS[p.id] ?: Tab.HOME
            var group = ""
            var tab = pageTab
            val items = runCatching { p.items }.getOrDefault(emptyList())
            for (item in items) {
                if (item.type == "section") {
                    group = item.label
                    tab = byLabel[group] ?: pageTab
                    out += SearchEntry(tab, group, where(tab), null, normalise(group + " " + where(tab)), "group:${p.id}/${item.id}")
                    continue
                }
                val path = listOf(where(tab), group).filter { it.isNotBlank() }.joinToString(" › ")
                out += SearchEntry(tab, item.label, path, item, normalise("${item.label} ${item.hint} $path"), "item:${p.id}/${item.id}")
            }
        }
        return out
    }

    /** Entries holding every word of [query], best first (title starting with it, then title, then the rest). */
    fun find(
        entries: List<SearchEntry>,
        query: String,
    ): List<SearchEntry> {
        val q = normalise(query.trim())
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\s+"))
        return entries
            .filter { e -> words.all { e.text.contains(it) } }
            .sortedBy { e ->
                val t = normalise(e.title)
                when {
                    t.startsWith(q) -> 0
                    t.contains(q) -> 1
                    words.all { t.contains(it) } -> 2
                    else -> 3
                } * 10 + (if (e.item == null) 0 else 1)
            }.take(MAX_RESULTS)
    }

    private const val MAX_RESULTS = 80
}

/** The search field at the top of the pages; [onQuery] gets what is typed. */
@Composable
internal fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(hint ?: stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = CarColors.TextDim) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, stringResource(R.string.search_clear), tint = CarColors.Text) }
            }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = CarColors.Text,
                unfocusedTextColor = CarColors.Text,
                focusedBorderColor = CarColors.Accent,
                unfocusedBorderColor = CarColors.TextDim,
                cursorColor = CarColors.Accent,
                focusedPlaceholderColor = CarColors.TextDim,
                unfocusedPlaceholderColor = CarColors.TextDim,
            ),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Results of [query]: menus and groups open their page; settings are shown with their control (switch, slider,
 * choice, action without confirmation) and an "Open" key to their page.
 */
@Composable
internal fun SearchResults(
    query: String,
    onTab: (Tab) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Index given ready (screenshots); null: built here, off the main thread. */
    preset: List<SearchEntry>? = null,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(preset) }
    LaunchedEffect(Unit) { if (entries == null) entries = withContext(Dispatchers.Default) { SettingsIndex.build(context.applicationContext) } }
    BackHandler(onBack = onClose)
    val all = entries
    if (all == null) {
        Hint(stringResource(R.string.search_loading))
        return
    }
    val hits = remember(all, query) { SettingsIndex.find(all, query) }
    if (hits.isEmpty()) {
        Hint(stringResource(R.string.search_empty))
        return
    }
    // A setting changed here: its value is read again.
    var version by remember { mutableIntStateOf(0) }
    val go: (Tab) -> Unit = { t ->
        onClose()
        onTab(t)
    }
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(hits, key = { it.key }) { e ->
            val item = e.item
            if (item == null) {
                ListRow(e.title, e.path, onClick = { go(e.tab) }) { Pill(stringResource(R.string.search_open)) { go(e.tab) } }
            } else {
                SearchedSetting(e, item, version, { version++ }) { go(e.tab) }
            }
        }
    }
}

@Composable
private fun SearchedSetting(
    e: SearchEntry,
    item: BridgeItem,
    version: Int,
    changed: () -> Unit,
    open: () -> Unit,
) {
    val value = remember(item, version) { runCatching { item.get() }.getOrNull() }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.path, color = CarColors.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Pill(stringResource(R.string.search_open)) { open() }
        }
        when (item.type) {
            "switch" -> {
                SwitchRow(item.label, value == true || value == "true", item.hint.ifBlank { null }) { on ->
                    item.set(on)
                    changed()
                }
            }

            "slider" -> {
                val v = (value as? Number)?.toInt() ?: item.min
                SliderRow(item.label, v.coerceIn(item.min, item.max), item.min..item.max, { item.display?.invoke(it) ?: "$it ${item.unit}".trim() }) { n ->
                    val step = item.step.coerceAtLeast(1)
                    item.set(item.min + (n - item.min + step / 2) / step * step)
                    changed()
                }
            }

            "choice" -> {
                Text(item.label, color = CarColors.Text, fontSize = 18.sp)
                Choices(item.options, value?.toString().orEmpty()) { v ->
                    item.set(v)
                    changed()
                }
            }

            "button" -> {
                if (item.confirm) {
                    ListRow(item.label, item.hint.ifBlank { null }, onClick = open)
                } else {
                    Actions {
                        Pill(item.label) {
                            item.set(null)
                            changed()
                        }
                    }
                }
            }

            "info" -> {
                ListRow(item.label, value?.toString())
            }

            else -> {
                ListRow(item.label, item.hint.ifBlank { null }, onClick = open)
            }
        }
    }
}
