package shoaku

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icon.PathIconKey
import java.awt.Dimension
import java.beans.PropertyChangeListener
import kotlin.math.abs

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab(focusOnClickInside = true) {
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

private val ChatSendIconKey = PathIconKey("/icons/send/send.svg", MyToolWindowFactory::class.java)

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
    var detailTodoPaneFraction by remember { mutableStateOf(0.1f) }
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
        SessionHeaderSwitcher(
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
                todoPaneFraction = detailTodoPaneFraction,
                onTodoPaneFractionChange = { detailTodoPaneFraction = it },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }

    LaunchedEffect(filePathState.text) {
        state.filePath = filePathState.text.toString()
    }
}

@Composable
private fun SessionHeaderSwitcher(
    openSessions: List<Item>,
    selectedSessionKey: SessionKey?,
    onSelectSessions: () -> Unit,
    onSelectSession: (SessionKey) -> Unit,
    onCloseSession: (SessionKey) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SessionHeaderItem(
            title = "Goals",
            selected = selectedSessionKey == null,
            onClick = onSelectSessions
        )
        openSessions.forEach { session ->
            val key = session.sessionKey
            SessionHeaderItem(
                title = session.content,
                selected = selectedSessionKey == key,
                onClick = { onSelectSession(key) },
                onClose = { onCloseSession(key) }
            )
        }
    }
}

@Composable
private fun SessionHeaderItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val closeInteractionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val closeHovered by closeInteractionSource.collectIsHoveredAsState()
    val colors = SessionHeaderColors.item(selected = selected, hovered = hovered)
    val shape = RoundedCornerShape(SessionHeaderMetrics.cornerRadius)

    Row(
        modifier = Modifier
            .widthIn(max = SessionHeaderMetrics.maxWidth)
            .clip(shape)
            .background(colors.background, shape)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = colors.border,
                shape = shape
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = SessionHeaderMetrics.horizontalPadding,
                vertical = SessionHeaderMetrics.verticalPadding
            ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f, fill = false),
            text = title,
            color = colors.content,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (onClose != null) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        SessionHeaderColors.closeBackground(
                            visible = selected || hovered || closeHovered,
                            hovered = closeHovered
                        ),
                        RoundedCornerShape(4.dp)
                    )
                    .hoverable(closeInteractionSource)
                    .clickable(
                        interactionSource = closeInteractionSource,
                        indication = null,
                        onClick = onClose
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "\u00d7",
                    color = SessionHeaderColors.closeContent(selected || hovered || closeHovered),
                    fontSize = 11.sp
                )
            }
        }
    }
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
                Text("No goals")
            }
            return
        }

        val listState = rememberLazyListState()
        ScrollHintLazyColumn(
            state = listState,
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
    todoPaneFraction: Float,
    onTodoPaneFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val diffResponse = session.shoakuId?.let { viewModel.diffResponses[it] }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SessionDetailSplitter(
            session = session,
            diffResponse = diffResponse,
            filePath = filePath,
            viewModel = viewModel,
            project = project,
            todoPaneFraction = todoPaneFraction,
            onTodoPaneFractionChange = onTodoPaneFractionChange,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
}

@Composable
private fun SessionDetailSplitter(
    session: Item,
    diffResponse: ShoakuShowDiffParams?,
    filePath: String,
    viewModel: ShoakuViewModel,
    project: Project?,
    todoPaneFraction: Float,
    onTodoPaneFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val sessionState = rememberUpdatedState(session)
    val diffResponseState = rememberUpdatedState(diffResponse)
    val filePathState = rememberUpdatedState(filePath)
    val viewModelState = rememberUpdatedState(viewModel)
    val projectState = rememberUpdatedState(project)
    val splitter = remember {
        OnePixelSplitter(true, todoPaneFraction, 0.1f, 0.9f).apply {
            dividerWidth = JBUI.scale(5)
            divider.background = java.awt.Color(0, 0, 0, 0)
            setHonorComponentsMinimumSize(true)
            dividerPositionStrategy = Splitter.DividerPositionStrategy.KEEP_FIRST_SIZE
            lackOfSpaceStrategy = Splitter.LackOfSpaceStrategy.HONOR_THE_FIRST_MIN_SIZE
            isShowDividerControls = false
            isShowDividerIcon = false
            firstComponent = JewelComposePanel {
                SessionTodoPane(
                    session = sessionState.value,
                    diffResponse = diffResponseState.value,
                    filePath = filePathState.value,
                    viewModel = viewModelState.value,
                    project = projectState.value
                )
            }.apply {
                minimumSize = Dimension(0, JBUI.scale(90))
            }
            secondComponent = JewelComposePanel {
                SessionChatPane(
                    session = sessionState.value,
                    project = projectState.value
                )
            }.apply {
                minimumSize = Dimension(0, JBUI.scale(220))
            }
        }
    }

    DisposableEffect(splitter, onTodoPaneFractionChange) {
        val listener = PropertyChangeListener { event ->
            if (event.propertyName == Splitter.PROP_PROPORTION) {
                onTodoPaneFractionChange(splitter.proportion)
            }
        }
        splitter.addPropertyChangeListener(listener)
        onDispose {
            splitter.removePropertyChangeListener(listener)
        }
    }

    SideEffect {
        if (abs(splitter.proportion - todoPaneFraction) > 0.001f) {
            splitter.proportion = todoPaneFraction
        }
    }

    SwingPanel(
        factory = { splitter },
        modifier = modifier,
        update = {}
    )
}

@Composable
private fun SessionTodoPane(
    session: Item,
    diffResponse: ShoakuShowDiffParams?,
    filePath: String,
    viewModel: ShoakuViewModel,
    project: Project?
) {
    SessionSectionCard(
        title = "Tasks",
        modifier = Modifier.fillMaxSize()
    ) {
        val listState = rememberLazyListState()
        ScrollHintLazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(session.children.filter { it.checked != null }) { model ->
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
                        title = "Suggested summary",
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
                                    session.shoakuId?.let { shoakuId ->
                                        viewModel.diffResponses.remove(shoakuId)
                                    }
                                },
                                enabled = filePath.isNotBlank()
                            ) {
                                Text("Apply")
                            }
                        }
                    ) {
                        Text(diffResponse.diff, color = TodoColors.infoText)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionChatPane(
    session: Item,
    project: Project?
) {
    var instructionValue by remember { mutableStateOf(TextFieldValue()) }
    val isEnabled = session.shoakuId != null
    SessionSectionCard(
        title = "Chat",
        modifier = Modifier.fillMaxSize()
    ) {
        if (session.messages?.isEmpty() ?: true) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("No messages yet", color = TodoColors.secondaryText)
            }
        } else {
            val listState = rememberLazyListState()
            ScrollHintLazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 4.dp)
            ) {
                itemsIndexed(session.messages) { index, entry ->
                    val previousEntry = session.messages.getOrNull(index - 1)
                    val previousIsSameSpeaker = previousEntry?.type == entry.type && previousEntry.command == null && entry.command == null

                    ChatEntryRow(
                        entry = entry,
                        isFirstInGroup = !previousIsSameSpeaker,
                        topSpacing = if (previousIsSameSpeaker) 4.dp else 10.dp
                    )
                }
            }
        }

        ChatComposer(
            value = instructionValue,
            onValueChange = { instructionValue = it },
            enabled = isEnabled,
            onSend = {
                sendSessionReply(
                    project = project,
                    shoakuId = session.shoakuId,
                    instruction = instructionValue.text
                )
                instructionValue = TextFieldValue()
            }
        )
    }
}

@Composable
private fun ChatComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit
) {
    val canSend = enabled && value.text.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(TodoColors.composerSurface)
                .border(1.dp, TodoColors.composerBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 42.dp)
                    .defaultMinSize(minHeight = 52.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            if (event.isShiftPressed) {
                                onValueChange(value.insertNewline())
                                true
                            } else if (canSend) {
                                onSend()
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    },
                enabled = enabled,
                maxLines = 8,
                textStyle = TextStyle(
                    color = TodoColors.primaryText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                ),
                cursorBrush = SolidColor(TodoColors.primaryText),
                decorationBox = { innerTextField ->
                    if (value.text.isEmpty()) {
                        Text(
                            text = "Ask Shoaku",
                            color = TodoColors.secondaryText.copy(alpha = 0.75f),
                            fontSize = 13.sp
                        )
                    }
                    innerTextField()
                }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (canSend) TodoColors.composerSendSurface else TodoColors.composerSendSurface.copy(alpha = 0.35f)
                    )
                    .clickable(enabled = canSend) { onSend() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    key = ChatSendIconKey,
                    contentDescription = "Send"
                )
            }
        }
        Text(
            text = "Enter to send  Shift+Enter for newline",
            color = TodoColors.secondaryText.copy(alpha = 0.72f),
            fontSize = 11.sp
        )
    }
}

private fun sendSessionReply(
    project: Project?,
    shoakuId: String?,
    instruction: String
) {
    if (shoakuId == null || instruction.isBlank()) {
        return
    }

    project?.let {
        LspServerManager.getInstance(project)
            .getServersForProvider(LanguageServerProvider::class.java)
            .first()
            .sendNotification {
                (it as AppLanguageServer).reply(
                    ReplyParams(
                        shoakuId,
                        instruction
                    )
                )
            }
    }
}

private fun TextFieldValue.insertNewline(): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val updatedText = buildString(text.length + 1) {
        append(text, 0, start)
        append('\n')
        append(text, end, text.length)
    }
    val cursor = start + 1
    return copy(
        text = updatedText,
        selection = TextRange(cursor)
    )
}

@Composable
private fun SessionSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .background(TodoColors.sectionSurface, RoundedCornerShape(8.dp))
            .padding(TodoMetrics.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = TodoColors.primaryText
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun ScrollHintLazyColumn(
    state: LazyListState,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit
) {
    val showScrollHint by remember(state) {
        derivedStateOf {
            val layoutInfo = state.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false

            lastVisible.index < totalItems - 1 ||
                lastVisible.offset + lastVisible.size > layoutInfo.viewportEndOffset
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            verticalArrangement = verticalArrangement,
            contentPadding = contentPadding,
            content = content
        )

        AnimatedVisibility(
            visible = showScrollHint,
            enter = fadeIn() + scaleIn(initialScale = 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.92f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(TodoColors.scrollHintSurface)
                    .border(1.dp, TodoColors.scrollHintBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "↓",
                    color = TodoColors.scrollHintContent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ChatEntryRow(
    entry: Message,
    isFirstInGroup: Boolean,
    topSpacing: Dp
) {
    if (entry.command != null) {
        ChatActivityRow(entry = entry, topSpacing = topSpacing)
        return
    }

    val message = entry.text.orEmpty()
    val isUser = entry.type == "userMessage"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topSpacing),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!isUser && isFirstInGroup) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(TodoColors.agentMessageDivider)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isUser) {
                    Text(
                        text = message,
                        color = TodoColors.userMessageText,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(TodoColors.userMessageSurface)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                } else {
                    AgentMessageContent(message = message)
                }
            }
        }
    }
}

private sealed interface AgentMessageBlock {
    data class Paragraph(val text: String) : AgentMessageBlock
    data class Code(val language: String?, val code: String) : AgentMessageBlock
}

@Composable
private fun AgentMessageContent(message: String) {
    val blocks = remember(message) { parseAgentMessageBlocks(message) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is AgentMessageBlock.Paragraph -> Text(
                    text = block.text,
                    color = TodoColors.infoText
                )

                is AgentMessageBlock.Code -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TodoColors.codeBlockSurface)
                        .border(1.dp, TodoColors.codeBlockBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    block.language?.takeIf { it.isNotBlank() }?.let { language ->
                        Text(
                            text = language,
                            color = TodoColors.secondaryText,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = block.code,
                        color = TodoColors.infoText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

private fun parseAgentMessageBlocks(message: String): List<AgentMessageBlock> {
    val fence = "```"
    if (!message.contains(fence)) {
        return listOf(AgentMessageBlock.Paragraph(message))
    }

    val blocks = mutableListOf<AgentMessageBlock>()
    var cursor = 0
    while (cursor < message.length) {
        val fenceStart = message.indexOf(fence, cursor)
        if (fenceStart == -1) {
            val tail = message.substring(cursor)
            if (tail.isNotEmpty()) {
                blocks += AgentMessageBlock.Paragraph(tail)
            }
            break
        }

        if (fenceStart > cursor) {
            val paragraph = message.substring(cursor, fenceStart)
            if (paragraph.isNotEmpty()) {
                blocks += AgentMessageBlock.Paragraph(paragraph)
            }
        }

        val infoLineStart = fenceStart + fence.length
        val codeStart = message.indexOf('\n', infoLineStart)
        if (codeStart == -1) {
            blocks += AgentMessageBlock.Paragraph(message.substring(fenceStart))
            break
        }

        val infoString = message.substring(infoLineStart, codeStart).trim().ifBlank { null }
        val fenceEnd = message.indexOf(fence, codeStart + 1)
        if (fenceEnd == -1) {
            blocks += AgentMessageBlock.Paragraph(message.substring(fenceStart))
            break
        }

        val code = message.substring(codeStart + 1, fenceEnd).trimEnd('\n', '\r')
        blocks += AgentMessageBlock.Code(language = infoString, code = code)
        cursor = fenceEnd + fence.length
        if (cursor < message.length && message[cursor] == '\n') {
            cursor += 1
        }
    }

    return blocks.ifEmpty { listOf(AgentMessageBlock.Paragraph(message)) }
}

@Composable
private fun ChatActivityRow(
    entry: Message,
    topSpacing: Dp
) {
    val command = entry.command ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topSpacing),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = "Ran $command",
            fontSize = 11.sp,
            color = TodoColors.secondaryText.copy(alpha = 0.78f)
        )
    }
}

private data class SessionKey(
    val shoakuId: String
)

private val Item.sessionKey
    get() = SessionKey(requireNotNull(shoakuId))

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

private data class TodoCardColors(
    val background: Color,
    val border: Color
)

private data class HeaderItemColors(
    val background: Color,
    val border: Color,
    val content: Color
)

private object SessionHeaderMetrics {
    val cornerRadius = 8.dp
    val maxWidth = 240.dp
    val horizontalPadding = 10.dp
    val verticalPadding = 6.dp
}

private object SessionHeaderColors {
    private val panelBackground = namedColor("Panel.background", 0xFF1F2329, 0xFFF7F8FA)
    private val hoverBackground = namedColor("List.hoverBackground", 0xFF2F3540, 0xFFEFF3F8)
    private val selectionBackground = namedColor("TabbedPane.focusColor", 0xFF2B313C, 0xFFE8EEF7)
    private val border = namedColor("Component.borderColor", 0xFF4B5263, 0xFFC5CDD8)
    private val content = namedColor("Label.foreground", 0xFFE6EDF3, 0xFF1F2328)
    private val secondaryContent = namedColor("Label.infoForeground", 0xFF9DA7B3, 0xFF667281)

    fun item(selected: Boolean, hovered: Boolean): HeaderItemColors =
        when {
            selected -> HeaderItemColors(
                background = overlay(selectionBackground.copy(alpha = 0.38f), panelBackground),
                border = border.copy(alpha = 0.95f),
                content = content
            )
            hovered -> HeaderItemColors(
                background = overlay(hoverBackground.copy(alpha = 0.32f), panelBackground),
                border = Color.Transparent,
                content = content
            )
            else -> HeaderItemColors(
                background = Color.Transparent,
                border = Color.Transparent,
                content = secondaryContent
            )
        }

    fun closeBackground(visible: Boolean, hovered: Boolean): Color =
        when {
            hovered -> overlay(border.copy(alpha = 0.32f), panelBackground)
            visible -> overlay(border.copy(alpha = 0.18f), panelBackground)
            else -> Color.Transparent
        }

    fun closeContent(visible: Boolean): Color = if (visible) content else secondaryContent.copy(alpha = 0.75f)
}

private object TodoMetrics {
    val cardCornerRadius = 16.dp
    val horizontalPadding = 12.dp
    val verticalPadding = 10.dp
    val compactVerticalPadding = 8.dp
}

private object TodoColors {
    private val panelBackground = namedColor("Panel.background", 0xFF1F2329, 0xFFF7F8FA)
    private val listBackground = namedColor("List.background", 0xFF262B33, 0xFFFFFFFF)
    private val componentBorder = namedColor("Component.borderColor", 0xFF4B5263, 0xFFC5CDD8)
    private val hoverBackground = namedColor("List.hoverBackground", 0xFF2D4366, 0xFFEAF2FF)
    private val userMessageBlue = namedColor("Actions.Blue", 0xFF1849C6, 0xFF3574F0)
    private val infoBackground = namedColor("Component.infoBackground", 0xFF24324A, 0xFFF3F7FF)
    val primaryText = namedColor("Label.foreground", 0xFFE6EDF3, 0xFF1F2328)
    val secondaryText = namedColor("Label.infoForeground", 0xFF9DA7B3, 0xFF667281)
    val completedText = secondaryText.copy(alpha = 0.9f)
    val infoSurface = blend(panelBackground, listBackground, 0.9f)
    val infoText = namedColor("Editor.foreground", 0xFFD5DCE5, 0xFF253041)
    val sectionSurface = overlay(infoBackground.copy(alpha = 0.08f), blend(panelBackground, listBackground, 0.78f))
    val agentMessageDivider = componentBorder.copy(alpha = 0.54f)
    val codeBlockSurface = overlay(infoBackground.copy(alpha = 0.12f), blend(listBackground, panelBackground, 0.5f))
    val codeBlockBorder = componentBorder.copy(alpha = 0.72f)
    val userMessageSurface = overlay(userMessageBlue.copy(alpha = 0.78f), blend(listBackground, panelBackground, 0.16f))
    val userMessageText = namedColor("TextArea.foreground", 0xFFF4F8FF, 0xFF17375E)
    val composerSurface = overlay(infoBackground.copy(alpha = 0.12f), blend(listBackground, panelBackground, 0.28f))
    val composerBorder = componentBorder.copy(alpha = 0.82f)
    val composerSendSurface = overlay(userMessageBlue.copy(alpha = 0.88f), blend(listBackground, panelBackground, 0.12f))
    val scrollHintSurface = overlay(userMessageBlue.copy(alpha = 0.18f), blend(listBackground, panelBackground, 0.4f))
    val scrollHintBorder = componentBorder.copy(alpha = 0.68f)
    val scrollHintContent = namedColor("Label.foreground", 0xFFEAF2FF, 0xFF1D4A85)

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
            messages = listOf(
                Message(type = "commandExecution", command = "file read"),
                Message(type = "agentMessage", text = "I reviewed the current todo order and found the next actionable item."),
                Message(type = "userMessage", text = "Split the detail view into todo and chat sections."),
                Message(type = "commandExecution", command = "execute cmd"),
                Message(type = "agentMessage", text = "Use separate scrollable panes so the todo list stays visible while the conversation grows.")
            ),
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
