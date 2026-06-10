package shoaku

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.ui.JBColor
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.component.styling.LocalDefaultTabStyle

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab(MyMessageBundle.message("toolwindow.stripe.MyToolWindow"), focusOnClickInside = true) {
            LaunchedEffect(Unit) {
                // initial data loading
            }
            MyToolWindowContent(
                project.service<ShoakuSettings>().viewModel,
                project.service<ShoakuSettings>().state,
                project
            )
        }
    }
}

@Composable
private fun MyToolWindowContent(
    viewModel: ShoakuViewModel,
    state: ShoakuSettings.State,
    project: Project? = null,
    initialOpenSessionKeys: List<SessionKey> = emptyList(),
    initialSelectedSessionKey: SessionKey? = null
) {
    val filePathState = rememberTextFieldState(initialText = state.filePath)
    val vm = remember { viewModel }
    val openSessionKeys = remember { mutableStateListOf<SessionKey>().also { it.addAll(initialOpenSessionKeys) } }
    var selectedSessionKey by remember { mutableStateOf(initialSelectedSessionKey) }
    val sessions = vm.items.filter { it.shoakuId != null }
    val openSessions = openSessionKeys.mapNotNull { key -> sessions.firstOrNull { it.sessionKey == key } }
    val selectedSession = selectedSessionKey?.let { key -> sessions.firstOrNull { it.sessionKey == key } }

    LaunchedEffect(sessions) {
        val currentKeys = sessions.map { it.sessionKey }.toSet()
        openSessionKeys.removeAll { it !in currentKeys }
        if (selectedSessionKey != null && selectedSessionKey !in currentKeys) {
            selectedSessionKey = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SessionTabStrip(
            openSessions = openSessions,
            selectedSessionKey = selectedSessionKey,
            onSelectSessions = { selectedSessionKey = null },
            onSelectSession = { selectedSessionKey = it },
            onCloseSession = { key ->
                openSessionKeys.remove(key)
                if (selectedSessionKey == key) {
                    selectedSessionKey = null
                }
            }
        )

        if (selectedSession == null) {
            SessionListContent(
                sessions = sessions,
                filePathState = filePathState,
                project = project,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                onOpenSession = { session ->
                    project?.let {
                        LspServerManager.getInstance(project)
                            .getServersForProvider(LanguageServerProvider::class.java)
                            .first()
                            .sendNotification { (it as AppLanguageServer).startSession(StartSessionParams(session.shoakuId)) }
                    }
                    val key = session.sessionKey
                    if (key !in openSessionKeys) {
                        openSessionKeys.add(key)
                    }
                    selectedSessionKey = key
                }
            )
        } else {
            SessionDetailContent(
                session = selectedSession,
                filePath = state.filePath,
                viewModel = vm,
                project = project,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }

    LaunchedEffect(filePathState.text) {
        state.filePath = filePathState.text.toString()
    }
}

@Composable
private fun SessionTabStrip(
    openSessions: List<Item>,
    selectedSessionKey: SessionKey?,
    onSelectSessions: () -> Unit,
    onSelectSession: (SessionKey) -> Unit,
    onCloseSession: (SessionKey) -> Unit
) {
    val tabs = buildList {
        add(
            TabData.Default(
                selected = selectedSessionKey == null,
                content = { Text("Sessions") },
                closable = false,
                onClose = {},
                onClick = onSelectSessions
            )
        )
        openSessions.forEach { session ->
            val key = session.sessionKey
            add(
                TabData.Default(
                    selected = selectedSessionKey == key,
                    content = { Text(session.content) },
                    closable = true,
                    onClose = { onCloseSession(key) },
                    onClick = { onSelectSession(key) }
                )
            )
        }
    }

    TabStrip(
        tabs = tabs,
        style = LocalDefaultTabStyle.current,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SessionListContent(
    sessions: List<Item>,
    filePathState: androidx.compose.foundation.text.input.TextFieldState,
    project: Project?,
    modifier: Modifier = Modifier,
    onOpenSession: (Item) -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(MyMessageBundle.message("toolwindow.MyToolWindow.filePath.label"))
            TextField(
                state = filePathState,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = {
                if (project != null) {
                    val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                    FileChooser.chooseFile(descriptor, project, null) { virtualFile ->
                        filePathState.setTextAndPlaceCursorAtEnd(virtualFile.path)
                    }
                }
            }) {
                Text(MyMessageBundle.message("toolwindow.MyToolWindow.filePath.browse.button"))
            }
        }

        if (sessions.isEmpty()) {
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center
            ) {
                Text("No sessions")
            }
            return
        }

        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(sessions) { index, session ->
                val activeChild = session.children.firstOrNull { it.checked == false }
                TodoRow(
                    title = session.content,
                    meta = "#${index + 1}",
                    subtitle = activeChild?.status?.takeIf { it.isNotBlank() } ?: "${session.children.size} items",
                    completed = session.checked ?: false,
                    hoverable = true,
                    onClick = { onOpenSession(session) }
                )
            }
        }
    }
}

@Composable
private fun SessionDetailContent(
    session: Item,
    filePath: String,
    viewModel: ShoakuViewModel,
    project: Project? = null,
    modifier: Modifier = Modifier
) {
    val instructionState = rememberTextFieldState()
    val responseScrollState = rememberScrollState()
    val diffResponse = session.shoakuId?.let { viewModel.diffResponses[it] }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = session.content,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(session.children) { model ->
                TodoRow(
                    title = model.content,
                    subtitle = null,
                    meta = null,
                    completed = model.checked ?: false,
                    compact = true
                )
            }
            if (diffResponse?.response?.isNotBlank() == true) {
                item {
                    InfoCard(
                        title = "",
                        modifier = Modifier.fillMaxWidth(),
                        action = {
                            OutlinedButton(
                                onClick = {
                                    project?.let {
                                        LspServerManager.getInstance(project)
                                            .getServersForProvider(LanguageServerProvider::class.java)
                                            .first()
                                            .sendNotification {
                                                (it as AppLanguageServer).applyDiff(
                                                    ApplyDiffParams(diffResponse.shoakuId, diffResponse.response)
                                                )
                                            }
                                    }
                                    session.shoakuId.let { shoakuId ->
                                        viewModel.diffResponses.remove(shoakuId)
                                    }
                                },
                                enabled = filePath.isNotBlank()
                            ) {
                                Text("Apply")
                            }
                        }
                    ) {
                        SelectionContainer {
                            Text(diffResponse.diff, color = TodoColors.infoText)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (session.response?.isNotBlank() == true) {
            InfoCard(
                title = "",
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(responseScrollState)
                ) {
                    SelectionContainer {
                        Text(session.response, color = TodoColors.infoText)
                    }
                }
                if (responseScrollState.maxValue > 0 && responseScrollState.value < responseScrollState.maxValue) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, TodoColors.infoSurface.copy(alpha = 0.82f))
                                )
                            )
                    )
                    Text(
                        text = "Scroll for more",
                        fontSize = 11.sp,
                        color = TodoColors.secondaryText,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextField(
                state = instructionState,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Input...") },
                enabled = session.shoakuId != null
            )
            OutlinedButton(
                onClick = {
                    val instruction = instructionState.text.toString()
                    if (instruction.isNotBlank()) {
                        project?.let {
                            LspServerManager.getInstance(project)
                                .getServersForProvider(LanguageServerProvider::class.java)
                                .first()
                                .sendNotification {
                                    (it as AppLanguageServer).reply(
                                        ReplyParams(
                                            session.shoakuId!!,
                                            instruction
                                        )
                                    )
                                }
                        }

                        instructionState.setTextAndPlaceCursorAtEnd("")
                    }
                },
                enabled = session.shoakuId != null
            ) {
                Text("Send")
            }
        }
    }
}

private data class SessionKey(
    val type: String,
    val content: String
)

private val Item.sessionKey
    get() = SessionKey(type, content)

@Composable
private fun TodoRow(
    title: String,
    subtitle: String?,
    meta: String?,
    completed: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    hoverable: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val colors = when {
        hoverable && isHovered && completed -> TodoColors.completedHoveredCard()
        hoverable && isHovered -> TodoColors.hoveredCard()
        completed -> TodoColors.completedCard()
        else -> TodoColors.defaultCard()
    }
    val shape = RoundedCornerShape(TodoMetrics.cardCornerRadius)
    val cardModifier = modifier
        .fillMaxWidth()
        .border(width = 1.dp, color = colors.border, shape = shape)
        .background(color = colors.background, shape = shape)
        .then(if (hoverable) Modifier.hoverable(interactionSource) else Modifier)
        .then(
            if (onClick != null) {
                Modifier.clickable(interactionSource = interactionSource, indication = null) { onClick() }
            } else {
                Modifier
            }
        )
        .padding(
            horizontal = TodoMetrics.horizontalPadding,
            vertical = if (compact) TodoMetrics.compactVerticalPadding else TodoMetrics.verticalPadding
        )

    Row(
        modifier = cardModifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (subtitle != null) 4.dp else 0.dp)
        ) {
            Text(
                text = title,
                fontSize = if (compact) 14.sp else 15.sp,
                fontWeight = if (compact) FontWeight.Medium else FontWeight.SemiBold,
                color = if (completed) TodoColors.completedText else TodoColors.primaryText,
                textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None
            )
            subtitle?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = TodoColors.secondaryText
                )
            }
        }
        meta?.let {
            Text(
                text = it,
                fontSize = 12.sp,
                color = TodoColors.secondaryText
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(TodoMetrics.cardCornerRadius)
    Column(
        modifier = modifier
            .background(TodoColors.infoSurface, shape)
            .padding(TodoMetrics.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TodoColors.secondaryText
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
        if (action != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                action()
            }
        }
    }
}

private fun sessionBadgeText(session: Item, activeChild: Item?): String =
    when {
        session.checked == true -> "Done"
        !activeChild?.status.isNullOrBlank() -> activeChild?.status ?: "Open"
        session.children.any { it.checked == false } -> "Active"
        else -> "Open"
    }

private data class TodoCardColors(
    val background: Color,
    val border: Color
)

private object TodoMetrics {
    val cardCornerRadius = 16.dp
    val horizontalPadding = 12.dp
    val verticalPadding = 10.dp
    val compactVerticalPadding = 8.dp
}

private object TodoColors {
    private val panelBackground = namedColor("Panel.background", 0xFF1F2329, 0xFFF7F8FA)
    private val listBackground = namedColor("List.background", 0xFF262B33, 0xFFFFFFFF)
    private val separator = namedColor("Separator.foreground", 0xFF3B4252, 0xFFD8DEE9)
    private val componentBorder = namedColor("Component.borderColor", 0xFF4B5263, 0xFFC5CDD8)
    private val hoverBackground = namedColor("List.hoverBackground", 0xFF2D4366, 0xFFEAF2FF)
    private val infoBackground = namedColor("Component.infoBackground", 0xFF24324A, 0xFFF3F7FF)
    val primaryText = namedColor("Label.foreground", 0xFFE6EDF3, 0xFF1F2328)
    val secondaryText = namedColor("Label.infoForeground", 0xFF9DA7B3, 0xFF667281)
    val completedText = secondaryText.copy(alpha = 0.9f)
    val infoSurface = blend(panelBackground, listBackground, 0.9f)
    val infoText = namedColor("Editor.foreground", 0xFFD5DCE5, 0xFF253041)

    fun defaultCard() = TodoCardColors(
        background = overlay(infoBackground.copy(alpha = 0.12f), blend(listBackground, panelBackground, 0.44f)),
        border = componentBorder.copy(alpha = 0.95f)
    )

    fun hoveredCard() = TodoCardColors(
        background = overlay(hoverBackground.copy(alpha = 0.28f), blend(listBackground, panelBackground, 0.36f)),
        border = componentBorder.copy(alpha = 1f)
    )

    fun completedCard() = TodoCardColors(
        background = overlay(infoBackground.copy(alpha = 0.07f), blend(panelBackground, listBackground, 0.7f)),
        border = componentBorder.copy(alpha = 0.72f)
    )

    fun completedHoveredCard() = TodoCardColors(
        background = overlay(hoverBackground.copy(alpha = 0.18f), blend(panelBackground, listBackground, 0.62f)),
        border = componentBorder.copy(alpha = 0.84f)
    )
}

private fun namedColor(name: String, dark: Long, light: Long): Color =
    Color(
        JBColor.namedColor(
            name,
            JBColor(
                java.awt.Color(dark.toInt()),
                java.awt.Color(light.toInt())
            )
        ).rgb
    )

private fun blend(base: Color, overlay: Color, ratio: Float): Color =
    Color(
        red = base.red * ratio + overlay.red * (1f - ratio),
        green = base.green * ratio + overlay.green * (1f - ratio),
        blue = base.blue * ratio + overlay.blue * (1f - ratio),
        alpha = 1f
    )

private fun overlay(foreground: Color, background: Color): Color {
    val alpha = foreground.alpha
    val inverse = 1f - alpha
    return Color(
        red = foreground.red * alpha + background.red * inverse,
        green = foreground.green * alpha + background.green * inverse,
        blue = foreground.blue * alpha + background.blue * inverse,
        alpha = 1f
    )
}

@Composable
@Preview
fun MyToolWindowSessionListPreview() {
    MyToolWindowContent(
        sampleShoakuViewModel(),
        ShoakuSettings.State(filePath = "/tmp/shoaku-todo.md")
    )
}

@Composable
@Preview
fun MyToolWindowSessionTabsPreview() {
    val viewModel = sampleShoakuViewModel()
    val openSessions = viewModel.items.take(2).map { it.sessionKey }

    MyToolWindowContent(
        viewModel,
        ShoakuSettings.State(filePath = "/tmp/shoaku-todo.md"),
        initialOpenSessionKeys = openSessions,
        initialSelectedSessionKey = openSessions.firstOrNull()
    )
}

private fun sampleShoakuViewModel() = ShoakuViewModel().apply {
    items = listOf(
        Item(
            "text",
            "aaa",
            checked = false,
            children = listOf(
                Item("text", "aaa-1", checked = true, status = "Done"),
                Item("text", "aaa-2", checked = false, status = "In Progress"),
            ),
            response = "AI response",
            shoakuId = "shoaku-preview-1"
        ),
        Item(
            "text",
            "bbb",
            checked = null,
            children = listOf(
                Item("text", "bbb-1", checked = false, status = "Next"),
                Item("text", "bbb-2", checked = null)
            )
        )
    )
    diffResponses["shoaku-preview-1"] = ShoakuShowDiffParams(
        shoakuId = "shoaku-preview-1",
        response = "- Extract common setup\n- Reuse validated command sequence",
        diff = """
            - a
            +  - a-a
            - b
        """.trimIndent()
    )
}
