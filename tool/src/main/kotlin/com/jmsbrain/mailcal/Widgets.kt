package com.jmsbrain.mailcal

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.thelightphone.sdk.ui.LightIconConfiguration

@Composable
fun TextButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    BasicText(
        label,
        style = buttonStyle().copy(color = if (enabled) LightStyle.content else LightStyle.secondary),
        modifier = Modifier
            .flatClickable { if (enabled) onClick() }
            .padding(vertical = designDp(6f)),
    )
}

/** A Light SDK icon as a button, in the app's colours. [mirror] flips it, so the back chevron also points right. */
@Composable
fun IconButton(icon: LightIconConfiguration, label: String, sizePx: Float = 34f, mirror: Boolean = false, onClick: () -> Unit) {
    Image(
        painterResource(icon.drawableResource),
        contentDescription = label,
        colorFilter = ColorFilter.tint(LightStyle.content),
        modifier = Modifier
            .flatClickable(onClick)
            .padding(designDp(4f))
            .size(designDp(sizePx))
            .graphicsLayer { if (mirror) scaleX = -1f },
    )
}

/** Top row of buttons, left and right. */
@Composable
fun TopBar(left: @Composable () -> Unit, right: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = designDp(24f), vertical = designDp(12f))) {
        left()
        Spacer(Modifier.weight(1f))
        right()
    }
}

@Composable
fun Gap(px: Float) = Spacer(Modifier.height(designDp(px)))

@Composable
fun HGap(px: Float) = Spacer(Modifier.width(designDp(px)))

/** Labelled, underlined text field. Tapping it opens the LightOS editor. */
@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    singleLine: Boolean = true,
    password: Boolean = false,
    caps: Boolean = true,
) {
    val editor = LocalTextEditor.current
    val style = paragraphStyle()
    val title = label.lowercase().replaceFirstChar { it.uppercase() }
    Column(
        Modifier
            .fillMaxWidth()
            .flatClickable { editor.edit(title, value, singleLine, caps && !password, onValueChange) }
            .padding(vertical = designDp(8f)),
    ) {
        BasicText(label, style = labelStyle())
        Gap(4f)
        BasicText(
            when {
                value.isEmpty() -> placeholder
                password -> "•".repeat(value.length)
                else -> value
            },
            style = if (value.isEmpty()) style.copy(color = LightStyle.secondary) else style,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        )
        Gap(4f)
        Box(Modifier.fillMaxWidth().height(1.dp).background(LightStyle.secondary))
    }
}

/** A label over a tappable value, e.g. STARTS / Tue, Oct 14. */
@Composable
fun ValueRow(label: String, value: String, onClick: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.flatClickable(onClick) else it }
            .padding(vertical = designDp(8f)),
    ) {
        BasicText(label, style = labelStyle())
        Gap(2f)
        BasicText(value, style = paragraphStyle())
    }
}

/** Text button with a dropdown menu. */
@Composable
fun MenuButton(label: String, items: List<Pair<String, () -> Unit>>) = MenuAnchor(items) { open -> TextButton(label, onClick = open) }

/** A menu opened by tapping [anchor]. */
@Composable
fun MenuAnchor(items: List<Pair<String, () -> Unit>>, anchor: @Composable (open: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        anchor { expanded = true }
        if (expanded) {
            Popup(
                popupPositionProvider = DropdownPosition(alignEnd = true),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                MenuList(items.map { (text, action) -> text to { expanded = false; action() } })
            }
        }
    }
}

/** Menu below its anchor, or above if it doesn't fit. */
class DropdownPosition(private val alignEnd: Boolean) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val left = if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val x = left.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val fitsBelow = anchorBounds.bottom + popupContentSize.height <= windowSize.height
        val y = if (fitsBelow) anchorBounds.bottom else maxOf(0, anchorBounds.top - popupContentSize.height)
        return IntOffset(x, y)
    }
}

@Composable
fun MenuList(items: List<Pair<String, () -> Unit>>) {
    Column(
        Modifier
            .background(LightStyle.background)
            .border(1.dp, LightStyle.content)
            .heightIn(max = designDp(440f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = designDp(16f), vertical = designDp(6f)),
    ) {
        items.forEach { (text, onClick) ->
            BasicText(
                text,
                style = buttonStyle(),
                modifier = Modifier.flatClickable(onClick).padding(vertical = designDp(8f)),
            )
        }
    }
}

@Composable
fun Note(text: String) = BasicText(text, style = paragraphStyle().copy(color = LightStyle.secondary))
