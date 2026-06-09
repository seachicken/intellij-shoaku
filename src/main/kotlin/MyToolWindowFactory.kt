package shoaku

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.platform.lsp.api.LspServerManager
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
    val sessions = vm.items
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (session.checked ?: false) Color(0xFF1E1E2A) else Color(0xFF2D2D3F),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onOpenSession(session) }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = session.content,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = activeChild?.status?.takeIf { it.isNotBlank() }
                                ?: "${session.children.size} items",
                            fontSize = 12.sp,
                            color = Color(0xFFB8C0D0)
                        )
                    }
                    Text(
                        text = "#${index + 1}",
                        fontSize = 12.sp,
                        color = Color(0xFFB8C0D0)
                    )
                }
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (model.checked ?: false) Color(0xFF1E1E2A) else Color(0xFF2D2D3F),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        SelectionContainer {
                            Column {
                                Text(
                                    text = model.status ?: ""
                                )
                                Text(model.content)
                            }
                        }
                    }
                }
            }
            if (diffResponse?.response?.isNotBlank() == true) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = Color(0xFF203224),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "AI Summary",
                                fontWeight = FontWeight.Bold
                            )
                            SelectionContainer {
                                Text(diffResponse.diff)
                            }
                        }
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
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (session.response?.isNotBlank() == true) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color(0xFF202A2A),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "AI Response",
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(responseScrollState)
                ) {
                    SelectionContainer {
                        Text(session.response)
                    }
                }
                if (responseScrollState.maxValue > 0 && responseScrollState.value < responseScrollState.maxValue) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color(0xAA202A2A))
                                )
                            )
                    )
                    Text(
                        text = "Scroll for more",
                        fontSize = 11.sp,
                        color = Color(0xFF9FB0B0),
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
