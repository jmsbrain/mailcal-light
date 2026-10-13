package com.jmsbrain.mailcal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Which calendars show, and where new events go. */
@Composable
fun CalendarsScreen(s: UiState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(left = { TextButton("BACK", onClick = onBack) })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = designDp(24f)),
        ) {
            BasicText("Calendars", style = headingStyle())
            Gap(8f)
            Note("Tap a calendar to show or hide it.")
            Gap(12f)
            s.calendars.forEach { c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .flatClickable { Repo.setHidden(c.info.url, !c.hidden) }
                        .padding(vertical = designDp(10f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(c.info.name, style = copyStyle().copy(color = if (c.hidden) LightStyle.secondary else LightStyle.content))
                        if (c.info.readOnly) BasicText("READ ONLY", style = labelStyle())
                    }
                    BasicText(if (c.hidden) "HIDDEN" else "SHOWN", style = buttonStyle().copy(color = if (c.hidden) LightStyle.secondary else LightStyle.content))
                }
            }
            val writable = s.calendars.filter { !it.info.readOnly }
            if (writable.size > 1) {
                Gap(28f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        ValueRow("NEW EVENTS GO TO", writable.firstOrNull { it.info.url == s.defaultCal }?.info?.name ?: "", null)
                    }
                    MenuButton("CHANGE", writable.map { c -> c.info.name.uppercase() to { Repo.setDefault(c.info.url) } })
                }
            }
            Gap(40f)
        }
    }
}
