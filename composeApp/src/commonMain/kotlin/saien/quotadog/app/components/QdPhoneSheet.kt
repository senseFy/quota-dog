package saien.quotadog.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import saien.quotadog.app.theme.QdTheme

/** A phone modal owns focus and Back, with a fixed header above its own scroll area. */
@Composable
internal fun QdPhoneSheet(
    closeLabel: String,
    onClose: () -> Unit,
    header: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            val sheetHeight = maxHeight * .90f
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(
                    Modifier.fillMaxWidth().weight(1f)
                        .clickable(interactionSource = null, indication = null, onClick = onClose),
                )
                Column(
                    Modifier.widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .heightIn(max = sheetHeight)
                        .clip(QdTheme.shapes.sheet)
                        .background(QdTheme.colors.backgroundElevated)
                        .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp),
                ) {
                    Spacer(
                        Modifier.align(Alignment.CenterHorizontally)
                            .size(32.dp, 3.dp)
                            .clip(QdTheme.shapes.pill)
                            .background(QdTheme.colors.borderStrong),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        header()
                        QdIconButton(onClose, Modifier.semantics { contentDescription = closeLabel }, diameter = 48.dp) {
                            QdCloseIcon(size = 18.dp, tint = QdTheme.colors.textSecondary)
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) { content() }
                }
            }
        }
    }
}
