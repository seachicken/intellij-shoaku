package shoaku.presentation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.ui.JBColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.PathIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import shoaku.*
import java.text.NumberFormat
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.roundToInt

class GoalsWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val settings = project.service<ShoakuSettings>()
        settings.viewModel.goalFilter = settings.state.goalFilter

        toolWindow.addComposeTab(focusOnClickInside = true) {
            LaunchedEffect(Unit) {
                // initial data loading
            }
            MyToolWindowContent(
                settings.viewModel,
                settings.state,
                project,
                initialOpenSessionKeys = settings.state.openSessionIds.map(::SessionKey),
                initialSelectedSessionKey = settings.state.selectedSessionId?.let(::SessionKey),
                onFilePathChange = { newPath ->
                    project.sendNotificationToShoakuServer {
                        it.didChangeGoalsFilePath(DidChangeGoalsFilePath(newPath))
                    }
                }
            )
        }
    }
}

private fun Project.sendNotificationToShoakuServer(
    notification: (AppLanguageServer) -> Unit
) {
    val server = LspServerManager.getInstance(this)
        .getServersForProvider(LanguageServerProvider::class.java)
        .firstOrNull() ?: return
    server.sendNotification { notification(it as AppLanguageServer) }
}

private val ChatSendIconKey = PathIconKey("/icons/send/send.svg", GoalsWindowFactory::class.java)
private val EmptyGoalsSample = """
- [ ] Fix errors with large item counts [shoaku]
  - [ ] Reproduce the issue
  - [ ] Identify the root cause
  - [ ] Add regression tests
""".trimIndent()

@Composable
private fun MyToolWindowContent(
    viewModel: ShoakuViewModel,
    state: ShoakuSettings.State,
    project: Project? = null,
    initialOpenSessionKeys: List<SessionKey> = emptyList(),
    initialSelectedSessionKey: SessionKey? = null,
    onFilePathChange: (String) -> Unit = {}
) {
    val filePathState = rememberTextFieldState(initialText = state.filePath)
    val vm = remember { viewModel }
    val openSessionKeys = remember { mutableStateListOf<SessionKey>().also { it.addAll(initialOpenSessionKeys) } }
    var selectedSessionKey by remember { mutableStateOf(initialSelectedSessionKey) }
    val instructionValues = remember { mutableStateMapOf<SessionKey, TextFieldValue>() }
    val expandedConversationKeys = remember { mutableStateMapOf<SessionKey, Boolean>() }
    val goals = vm.items.filter { it.shoakuId != null }
    val goalFilter = vm.goalFilter
    val openSessions = openSessionKeys.mapNotNull { key -> goals.firstOrNull { it.sessionKey == key } }
    val selectedSession = selectedSessionKey?.let { key -> goals.firstOrNull { it.sessionKey == key } }

    fun persistOpenSessions() {
        state.openSessionIds = openSessionKeys.map(SessionKey::shoakuId).toMutableList()
    }

    fun selectSession(key: SessionKey?) {
        selectedSessionKey = key
        state.selectedSessionId = key?.shoakuId
    }

    LaunchedEffect(initialOpenSessionKeys, vm.hasReceivedGoals) {
        // The language server is guaranteed to be available only after its first sync.
        // Sending this during initial composition can happen before the server starts.
        if (!vm.hasReceivedGoals) {
            return@LaunchedEffect
        }
        val currentProject = project ?: return@LaunchedEffect
        initialOpenSessionKeys.forEach { key ->
            currentProject.sendNotificationToShoakuServer { server ->
                server.startSession(StartSessionParams(key.shoakuId))
            }
        }
    }

    LaunchedEffect(goals, vm.hasReceivedGoals) {
        if (!vm.hasReceivedGoals) {
            return@LaunchedEffect
        }
        val currentKeys = goals.map { it.sessionKey }.toSet()
        if (openSessionKeys.removeAll { it !in currentKeys }) {
            persistOpenSessions()
        }
        if (selectedSessionKey != null && selectedSessionKey !in currentKeys) {
            selectSession(null)
        }
        instructionValues.keys.retainAll(currentKeys)
        expandedConversationKeys.keys.retainAll(currentKeys)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val scope = rememberCoroutineScope()

        SessionHeaderSwitcher(
            openSessions = openSessions,
            selectedSessionKey = selectedSessionKey,
            onSelectSessions = { selectSession(null) },
            onSelectSession = ::selectSession,
            onCloseSession = { key ->
                openSessionKeys.remove(key)
                persistOpenSessions()
                if (selectedSessionKey == key) {
                    selectSession(null)
                }
            }
        )

        if (selectedSession == null) {
            SessionListContent(
                goals = goals,
                filter = goalFilter,
                filePathState = filePathState,
                project = project,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                onFilterChange = {
                    vm.goalFilter = it
                    state.goalFilter = it
                },
                onOpenSession = { session ->
                    if (session.shoakuId == null) {
                        return@SessionListContent
                    }
                    project?.let {
                        project.sendNotificationToShoakuServer { server ->
                            server.startSession(StartSessionParams(session.shoakuId))
                        }
                    }
                    val key = session.sessionKey
                    if (key !in openSessionKeys) {
                        openSessionKeys.add(key)
                        persistOpenSessions()
                    }
                    selectSession(key)
                }
            )
        } else {
            SessionDetailContent(
                session = selectedSession,
                viewModel = vm,
                project = project,
                instructionValue = instructionValues[selectedSession.sessionKey] ?: TextFieldValue(),
                onInstructionValueChange = { instructionValues[selectedSession.sessionKey] = it },
                conversationExpanded = expandedConversationKeys[selectedSession.sessionKey] == true,
                onConversationExpandedChange = { expandedConversationKeys[selectedSession.sessionKey] = it },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }

    LaunchedEffect(filePathState.text.toString()) {
        val updatedFilePath = filePathState.text.toString()
        state.filePath = updatedFilePath
        onFilePathChange(updatedFilePath)
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
    goals: List<Item>,
    filter: GoalFilter,
    filePathState: TextFieldState,
    project: Project?,
    modifier: Modifier = Modifier,
    onFilterChange: (GoalFilter) -> Unit,
    onOpenSession: (Item) -> Unit
) {
    val filteredGoals = remember(goals, filter) {
        goals.filter { filter.matches(it) }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TodoMetrics.horizontalPadding)
        ) {
            Text("Goals file:")
            TextField(
                state = filePathState,
                modifier = Modifier.weight(1f)
            )
            ToolbarIconButton(
                iconKey = AllIconsKeys.General.OpenDisk,
                contentDescription = "Browse",
                onClick = {
                    chooseGoalsFile(project) { selectedPath ->
                        filePathState.setTextAndPlaceCursorAtEnd(selectedPath)
                    }
                }
            )
            GoalFilterButton(
                selectedFilter = filter,
                onFilterChange = onFilterChange,
                counts = GoalFilter.entries.associateWith { candidate ->
                    goals.count { candidate.matches(it) }
                }
            )
        }

        if (goals.isEmpty()) {
            GoalsEmptyState(
                modifier = modifier
            )
            return
        }

        if (filteredGoals.isEmpty()) {
            InfoCard(
                title = "No matching goals",
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Try another filter to see more goals.",
                    fontSize = 12.sp,
                    color = TodoColors.secondaryText
                )
            }
            return
        }

        val listState = rememberLazyListState()
        val activeSessionIndex = filteredGoals.indexOfFirst { it.checked == false }

        LaunchedEffect(filteredGoals) {
            if (filteredGoals.isEmpty()) {
                return@LaunchedEffect
            }

            val targetIndex = if (activeSessionIndex >= 0) activeSessionIndex else filteredGoals.lastIndex
            listState.animateScrollToItem(targetIndex)
        }

        ScrollHintLazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(
                    start = TodoMetrics.horizontalPadding,
                    top = 8.dp,
                    end = TodoMetrics.horizontalPadding
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(filteredGoals) { index, session ->
                val todoItems = session.children.filter { it.checked != null }
                val state = when {
                    session.checked == true -> TaskItemState.Completed
                    index == activeSessionIndex -> TaskItemState.Current
                    else -> TaskItemState.Pending
                }
                TodoRow(
                    title = session.content,
                    subtitle = "${todoItems.size} tasks",
                    meta = null,
                    state = state,
                    hoverable = true,
                    onClick = if (session.shoakuId != null) ({ onOpenSession(session) }) else null,
                    trailing = {
                        val status = session.status
                            ?.toSummaryStatusState()
                            ?.takeIf { it.tone != AgentStatusTone.Hidden }
                        if (status != null) {
                            StatusPill(
                                label = status.label,
                                tone = status.tone,
                                pulse = statusPulse(),
                                minWidth = Dp.Unspecified
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SessionDetailContent(
    session: Item,
    viewModel: ShoakuViewModel,
    project: Project? = null,
    instructionValue: TextFieldValue,
    onInstructionValueChange: (TextFieldValue) -> Unit,
    conversationExpanded: Boolean,
    onConversationExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val finalCheckState = remember(session.shoakuId) { FinalCheckDisplayState() }
    SessionTaskPane(
        session = session,
        viewModel = viewModel,
        project = project,
        finalCheckState = finalCheckState,
        instructionValue = instructionValue,
        onInstructionValueChange = onInstructionValueChange,
        conversationExpanded = conversationExpanded,
        onConversationExpandedChange = onConversationExpandedChange,
        modifier = modifier
    )
}

@Composable
private fun SessionTaskPane(
    session: Item,
    viewModel: ShoakuViewModel,
    project: Project?,
    finalCheckState: FinalCheckDisplayState,
    instructionValue: TextFieldValue,
    onInstructionValueChange: (TextFieldValue) -> Unit,
    conversationExpanded: Boolean,
    onConversationExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val todoItems = session.children.filter { it.checked != null }
    val remainingCount = todoItems.count { it.checked == false }
    val isChecklistComplete = todoItems.isNotEmpty() && remainingCount == 0
    val isFinalCheckActive = isChecklistComplete
    val activeItemIndex = remember(todoItems) { todoItems.indexOfFirst { it.checked == false } }
    val activeItem = todoItems.getOrNull(activeItemIndex)
    val activeTaskKey = ActiveTaskKey(activeItemIndex, activeItem?.content)
    val messages = session.messages.orEmpty()
    var taskMessageBoundary by remember(session.shoakuId) {
        mutableStateOf(ActiveTaskMessageBoundary(activeTaskKey, 0))
    }
    val activeTaskMessages = remember(taskMessageBoundary, activeTaskKey, messages) {
        messagesForActiveTask(
            boundary = taskMessageBoundary,
            activeTaskKey = activeTaskKey,
            messages = messages
        )
    }
    SideEffect {
        if (
            taskMessageBoundary.activeTaskKey != activeTaskKey ||
            messages.size < taskMessageBoundary.messageCount
        ) {
            taskMessageBoundary = ActiveTaskMessageBoundary(activeTaskKey, messages.size)
        }
    }
    val alignmentState = remember(messages) { alignmentDisplayState(messages) }
    val effectiveTokenUsage = remember(session.tokenUsage, session.shoakuId, viewModel.tokenBudgetOverrides.toMap()) {
        val base = session.tokenUsage ?: return@remember null
        val overrideMax = session.shoakuId?.let(viewModel.tokenBudgetOverrides::get) ?: return@remember base
        base.copy(maxTokens = overrideMax.coerceAtLeast(1))
    }
    val requestedMaxTokens = session.shoakuId?.let(viewModel.tokenBudgetOverrides::get)
    LaunchedEffect(session.shoakuId, session.tokenUsage?.maxTokens, requestedMaxTokens) {
        val shoakuId = session.shoakuId ?: return@LaunchedEffect
        val requestedMax = requestedMaxTokens ?: return@LaunchedEffect

        // The server value is authoritative once it catches up with the optimistic UI value.
        if (session.tokenUsage?.maxTokens == requestedMax) {
            viewModel.tokenBudgetOverrides.remove(shoakuId)
            return@LaunchedEffect
        }

        delay(500)
        project?.sendNotificationToShoakuServer {
            it.didChangeMaxTokens(DidChangeMaxTokens(shoakuId, requestedMax))
        }
    }
    val finalCheckMessage = remember(messages, finalCheckState.startMessageCount.value) {
        finalCheckState.startMessageCount.value?.let { startMessageCount ->
            finalCheckResponse(messages.drop(startMessageCount))
        }
    }
    val taskCheckState = remember(session.shoakuId, activeItemIndex, activeItem?.content) {
        FinalCheckDisplayState()
    }
    val taskGuidanceState = remember(session.shoakuId, activeItemIndex, activeItem?.content) {
        TaskGuidanceDisplayState()
    }
    val replyState = remember(session.shoakuId) { ReplyDisplayState() }
    val replyMessage = remember(messages, replyState.startMessageCount.value) {
        replyState.startMessageCount.value?.let { startMessageCount ->
            finalCheckResponse(messages.drop(startMessageCount))
        }
    }
    val taskCheckMessage = remember(messages, taskCheckState.startMessageCount.value) {
        taskCheckState.startMessageCount.value?.let { startMessageCount ->
            finalCheckResponse(messages.drop(startMessageCount))
        }
    }
    val taskGuidanceMessage = remember(messages, taskGuidanceState.startMessageCount.value) {
        taskGuidanceState.startMessageCount.value?.let { startMessageCount ->
            finalCheckResponse(messages.drop(startMessageCount))
        }
    }
    val finalCheckRequested = finalCheckState.requested.value
    val finalCheckThinking = finalCheckState.thinking.value
    val taskCheckRequested = taskCheckState.requested.value
    val taskCheckThinking = taskCheckState.thinking.value
    val taskGuidanceRequested = taskGuidanceState.requested.value
    val taskGuidanceThinking = taskGuidanceState.thinking.value
    val replyThinking = replyState.thinking.value
    val replyDisplayMessage = when {
        replyThinking -> Message(
            type = "agentMessage",
            phase = "final_answer",
            text = ThinkingMessage
        )
        replyState.startMessageCount.value != null -> replyMessage
        else -> null
    }
    val taskCheckDisplayMessage = when {
        taskCheckThinking -> Message(
            type = "agentMessage",
            phase = "task_check",
            text = ThinkingMessage
        )
        taskCheckRequested -> taskCheckMessage
        else -> null
    }
    val taskGuidanceDisplayMessage = when {
        taskGuidanceThinking -> Message(
            type = "agentMessage",
            phase = "task_guidance",
            text = ThinkingMessage
        )
        taskGuidanceRequested -> taskGuidanceMessage
        else -> null
    }
    val latestFinalPhaseMessage = remember(activeTaskMessages) {
        latestTaskResponse(activeTaskMessages)
    }
    val isTaskCheckResponse = taskCheckDisplayMessage != null
    val finalCheckDisplayMessage = when {
        !isFinalCheckActive || !finalCheckRequested -> null
        finalCheckThinking -> Message(
            type = "agentMessage",
            phase = "final_check",
            text = ThinkingMessage
        )
        else -> finalCheckMessage
    }
    val interactionResponse = replyDisplayMessage
        ?: taskGuidanceDisplayMessage
        ?: finalCheckDisplayMessage
        ?: taskCheckDisplayMessage
        ?: latestFinalPhaseMessage?.takeIf { !it.text.isNullOrBlank() }
    val isFinalCheckResponse = finalCheckDisplayMessage != null
    val isFixedResponse = interactionResponse?.text?.let(::isFixedResponseText) == true

    LaunchedEffect(messages.size, finalCheckMessage, taskCheckMessage, taskGuidanceMessage) {
        if (finalCheckMessage != null) {
            finalCheckState.thinking.value = false
        }
        if (taskCheckMessage != null) {
            taskCheckState.thinking.value = false
        }
        if (taskGuidanceMessage != null) {
            taskGuidanceState.thinking.value = false
        }
        replyState.startMessageCount.value?.let { startMessageCount ->
            if (messages.drop(startMessageCount).any { it.type == "agentMessage" }) {
                replyState.thinking.value = false
            }
        }
    }
    val comparableResponseText = remember(alignmentState) {
        (alignmentState as? AlignmentDisplayState.NeedsInput)
            ?.message
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
    var previousComparableResponseText by remember(session.shoakuId) { mutableStateOf<String?>(null) }

    LaunchedEffect(session.shoakuId, comparableResponseText) {
        val nextResponseText = comparableResponseText ?: return@LaunchedEffect
        val previousResponseText = previousComparableResponseText
        previousComparableResponseText = nextResponseText
        if (previousResponseText == null || previousResponseText == nextResponseText) {
            return@LaunchedEffect
        }
    }

    val replyPlaceholder = "Ask Shoaku"
    val latestTaskComparison = remember(messages) {
        messages.asReversed().firstNotNullOfOrNull { it.taskComparison }
    }
    val displayedTaskComparisonRows = remember(latestTaskComparison, todoItems) {
        latestTaskComparison?.let { taskComparisonRows(it, todoItems) }
    }
    val comparisonRows = displayedTaskComparisonRows.orEmpty()
    val activeComparisonRow = comparisonRows.firstOrNull {
        it.humanTask?.content == activeItem?.content
    }
    val activeExplorerTask = activeComparisonRow?.explorerTasks?.firstOrNull()
    val activeExplorerTaskIndex = activeExplorerTask?.taskIndex()
    val activeTaskPatchPath = activeExplorerTask?.effectivePatchPath(session.temporaryWorkspace)
    val sendReply = {
        replyState.thinking.value = true
        replyState.startMessageCount.value = messages.size
        sendSessionReply(
            project = project,
            shoakuId = session.shoakuId,
            instruction = instructionValue.text
        )
        onInstructionValueChange(TextFieldValue())
    }
    val requestDiffReview: (Int) -> Unit = { explorerTaskIndex ->
        val shoakuId = session.shoakuId
        if (shoakuId != null) {
            project?.sendNotificationToShoakuServer { server ->
                server.createDiff(CreateDiffParams(shoakuId, explorerTaskIndex)).thenAccept { result ->
                    openProjectDirectoryDiff(project, result.explorerTaskPath)
                }
            }
        }
    }
    Box(
        modifier = modifier
            .background(TodoColors.sectionSurface, RoundedCornerShape(8.dp))
            .padding(TodoMetrics.horizontalPadding)
            .onPreviewKeyEvent { event ->
                if (
                    conversationExpanded &&
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.Escape
                ) {
                    onConversationExpandedChange(false)
                    true
                } else {
                    false
                }
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!conversationExpanded) {
            SessionSectionHeader(
                title = "Tasks",
                trailing = {
                    TokenUsageIndicator(
                        tokenUsage = effectiveTokenUsage,
                        status = session.status,
                        onIncreaseBudget = {
                            val shoakuId = session.shoakuId ?: return@TokenUsageIndicator
                            val currentMax = viewModel.tokenBudgetOverrides[shoakuId]
                                ?: session.tokenUsage?.maxTokens
                                ?: return@TokenUsageIndicator
                            val increment = (currentMax / 10f).toInt().coerceAtLeast(1)
                            viewModel.tokenBudgetOverrides[shoakuId] = currentMax + increment
                        }
                    )
                }
            )
            PlanComparisonPane(
                comparisonRows = displayedTaskComparisonRows,
                humanTasks = todoItems,
                temporaryWorkspace = session.temporaryWorkspace,
                activeHumanTaskContent = activeItem?.content,
                runImplementationCommandEnabled = !session.sessionId.isNullOrBlank(),
                onOpenCodeDiff = requestDiffReview,
                onRunImplementationCommand = { task ->
                    runImplementationForkCommand(project, session.sessionId, task)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
            ConversationNavigationCard(
                isThinking = interactionResponse?.text == ThinkingMessage,
                hasResponse = interactionResponse != null,
                onClick = { onConversationExpandedChange(true) }
            )
            }
            if (conversationExpanded) {
                key(session.sessionKey) {
                    ExpandedConversationPane(
                        messages = messages,
                        isThinking = interactionResponse?.text == ThinkingMessage,
                        contextLabel = activeItem?.content ?: ReviewTaskTitle,
                        onExpandedChange = onConversationExpandedChange,
                        instructionValue = instructionValue,
                        onInstructionValueChange = onInstructionValueChange,
                        enabled = session.shoakuId != null,
                        placeholder = replyPlaceholder,
                        onSend = sendReply,
                        codeDiffEnabled = !activeTaskPatchPath.isNullOrBlank() && activeExplorerTaskIndex != null,
                        runImplementationCommandEnabled = !session.sessionId.isNullOrBlank(),
                        onOpenCodeDiff = {
                            val explorerTaskIndex = activeExplorerTaskIndex
                            if (explorerTaskIndex != null) {
                                requestDiffReview(explorerTaskIndex)
                            }
                        },
                        onRunImplementationCommand = {
                            activeItem?.content?.let { task ->
                                runImplementationForkCommand(project, session.sessionId, task)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}

private const val TaskDifferenceAligned = "aligned"
private const val TaskDifferenceHumanOnly = "human_only"
private const val TaskDifferenceExplorerOnly = "explorer_only"

@Composable
private fun PlanComparisonPane(
    comparisonRows: List<TaskComparisonRowUi>?,
    humanTasks: List<Item>,
    temporaryWorkspace: String?,
    activeHumanTaskContent: String?,
    runImplementationCommandEnabled: Boolean,
    onOpenCodeDiff: (Int) -> Unit,
    onRunImplementationCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = planComparisonRows(comparisonRows, humanTasks)
    val sections = remember(rows) { planComparisonSections(rows) }
    val expandedSuggestionSections = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(sections) {
        val currentIds = sections.filter { it.suggested }.mapTo(mutableSetOf()) { it.id }
        expandedSuggestionSections.keys.retainAll(currentIds)
    }
    val activeComparisonRowId = rows.firstOrNull {
        it.humanTask?.content == activeHumanTaskContent
    }?.id
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No tasks in either plan.", color = TodoColors.secondaryText, fontSize = 12.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                sections.forEach { section ->
                    if (section.suggested) {
                        item(key = "suggestions-toggle-${section.id}") {
                            ExplorerOnlyTasksDisclosure(
                                count = section.rows.size,
                                expanded = expandedSuggestionSections[section.id] == true,
                                onToggle = {
                                    expandedSuggestionSections[section.id] =
                                        expandedSuggestionSections[section.id] != true
                                }
                            )
                        }
                    }
                    if (!section.suggested || expandedSuggestionSections[section.id] == true) {
                        items(section.rows, key = { it.id }) { row ->
                            PlanComparisonRow(
                                row = row,
                                temporaryWorkspace = temporaryWorkspace,
                                isActive = row.id == activeComparisonRowId,
                                onOpenCodeDiff = onOpenCodeDiff,
                                runImplementationCommandEnabled = runImplementationCommandEnabled,
                                onRunImplementationCommand = onRunImplementationCommand
                            )
                            Box(
                                Modifier.fillMaxWidth().height(1.dp)
                                    .background(TodoColors.sectionDivider.copy(alpha = 0.55f))
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun planComparisonRows(
    comparisonRows: List<TaskComparisonRowUi>?,
    humanTasks: List<Item>
): List<TaskComparisonRowUi> {
    if (comparisonRows != null) {
        val remainingExplorerRows = comparisonRows.filter { it.explorerTasks.isNotEmpty() }.toMutableList()
        val remainingHumanOnlyRows = comparisonRows.filter { it.explorerTasks.isEmpty() }.toMutableList()
        val orderedRows = mutableListOf<TaskComparisonRowUi>()

        humanTasks.forEach { task ->
            val lastMappedIndex = remainingExplorerRows.indexOfLast {
                it.humanTask?.content == task.content
            }
            if (lastMappedIndex >= 0) {
                orderedRows += remainingExplorerRows.take(lastMappedIndex + 1)
                repeat(lastMappedIndex + 1) { remainingExplorerRows.removeAt(0) }
            } else {
                val humanOnlyIndex = remainingHumanOnlyRows.indexOfFirst {
                    it.humanTask?.content == task.content
                }
                if (humanOnlyIndex >= 0) {
                    orderedRows += remainingHumanOnlyRows.removeAt(humanOnlyIndex)
                }
            }
        }
        return orderedRows + remainingExplorerRows + remainingHumanOnlyRows
    }
    return humanTasks.mapIndexed { index, task ->
        TaskComparisonRowUi(
            id = "human-$index",
            humanTask = ComparedTaskUi("human-$index", task.content, task.checked == true),
            difference = TaskDifferenceHumanOnly
        )
    }
}

internal fun taskComparisonRows(
    comparison: List<TaskComparison>,
    humanTasks: List<Item>
): List<TaskComparisonRowUi> {
    val humanTasksByName = humanTasks.associateBy { it.content }
    val matchedHumanTaskNames = comparison.mapNotNullTo(mutableSetOf()) {
        it.humanTaskName.takeIf(String::isNotEmpty)
    }
    val explorerRows = mutableListOf<TaskComparisonRowUi>()
    val alignedRowIndexes = mutableMapOf<String, Int>()
    comparison.forEach { result ->
        val humanItem = humanTasksByName[result.humanTaskName]
        val humanTask = humanItem?.let {
            ComparedTaskUi(
                id = "human-${result.humanTaskName}",
                content = it.content,
                checked = it.checked == true
            )
        }
        val explorerTask = ComparedTaskUi(
            id = "explorer-${result.explorerTaskIndex}",
            content = result.explorerTaskName
        )
        val comparedExplorerTask = ComparedExplorerTaskUi(
            task = explorerTask,
            patchFullPath = result.explorerPatchFullPath.takeIf(String::isNotBlank)
        )
        if (humanTask != null && result.humanTaskName.isNotEmpty()) {
            val existingIndex = alignedRowIndexes[result.humanTaskName]
            if (existingIndex != null) {
                val existing = explorerRows[existingIndex]
                explorerRows[existingIndex] = existing.copy(
                    explorerTasks = existing.explorerTasks + comparedExplorerTask
                )
            } else {
                alignedRowIndexes[result.humanTaskName] = explorerRows.size
                explorerRows += TaskComparisonRowUi(
                    id = "human-${result.humanTaskName}",
                    humanTask = humanTask,
                    explorerTasks = listOf(comparedExplorerTask),
                    difference = TaskDifferenceAligned
                )
            }
        } else {
            explorerRows += TaskComparisonRowUi(
                id = "explorer-${result.explorerTaskIndex}",
                explorerTasks = listOf(comparedExplorerTask),
                difference = TaskDifferenceExplorerOnly
            )
        }
    }
    val humanOnlyRows = humanTasks
        .filterNot { it.content in matchedHumanTaskNames }
        .mapIndexed { index, task ->
            TaskComparisonRowUi(
                id = "human-only-$index",
                humanTask = ComparedTaskUi("human-only-$index", task.content, task.checked == true),
                difference = TaskDifferenceHumanOnly
            )
        }
    return planComparisonRows(explorerRows + humanOnlyRows, humanTasks)
}

internal data class PlanComparisonSection(
    val id: String,
    val suggested: Boolean,
    val rows: List<TaskComparisonRowUi>
)

internal fun planComparisonSections(rows: List<TaskComparisonRowUi>): List<PlanComparisonSection> =
    rows.fold(mutableListOf<PlanComparisonSection>()) { sections, row ->
        val suggested = row.difference == TaskDifferenceExplorerOnly
        val previous = sections.lastOrNull()
        if (previous?.suggested == suggested) {
            sections[sections.lastIndex] = previous.copy(rows = previous.rows + row)
        } else {
            sections += PlanComparisonSection(
                id = "${if (suggested) "suggestions" else "tasks"}-${row.id}",
                suggested = suggested,
                rows = listOf(row)
            )
        }
        sections
    }

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PlanComparisonRow(
    row: TaskComparisonRowUi,
    temporaryWorkspace: String?,
    isActive: Boolean,
    onOpenCodeDiff: (Int) -> Unit,
    runImplementationCommandEnabled: Boolean,
    onRunImplementationCommand: (String) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var actionMenuExpanded by remember { mutableStateOf(false) }
    val background = when {
        isActive -> TodoColors.currentTaskSurface
        row.difference == TaskDifferenceAligned || row.difference == TaskDifferenceHumanOnly -> Color.Transparent
        row.difference == TaskDifferenceExplorerOnly -> Color.Transparent
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .hoverable(interactionSource)
    ) {
        Column(
            modifier = Modifier.padding(
                start = if (row.difference == TaskDifferenceExplorerOnly) 22.dp else 8.dp,
                top = 7.dp,
                end = 0.dp,
                bottom = 7.dp
            ),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (row.difference != TaskDifferenceExplorerOnly) {
                UnifiedTaskLine(
                    task = row.humanTask,
                    isActive = isActive,
                    trailing = null
                )
            }
            row.explorerTasks.forEach { explorerTask ->
                ExplorerTaskComparisonLine(
                    explorerTask = explorerTask,
                    humanTask = row.humanTask,
                    active = isActive,
                    temporaryWorkspace = temporaryWorkspace,
                    hovered = hovered || actionMenuExpanded,
                    runImplementationCommandEnabled = runImplementationCommandEnabled,
                    onOpenCodeDiff = { onOpenCodeDiff(explorerTask.taskIndex()) },
                    onRunImplementationCommand = {
                        (row.humanTask ?: explorerTask.task)?.content?.let(onRunImplementationCommand)
                    },
                    onMenuExpandedChange = { actionMenuExpanded = it }
                )
            }
        }
    }
}

@Composable
private fun ExplorerTaskComparisonLine(
    explorerTask: ComparedExplorerTaskUi,
    humanTask: ComparedTaskUi?,
    active: Boolean,
    temporaryWorkspace: String?,
    hovered: Boolean,
    runImplementationCommandEnabled: Boolean,
    onOpenCodeDiff: () -> Unit,
    onRunImplementationCommand: () -> Unit,
    onMenuExpandedChange: (Boolean) -> Unit
) {
    val patchPath = explorerTask.effectivePatchPath(temporaryWorkspace)
    Box(modifier = Modifier.fillMaxWidth()) {
        UnifiedTaskLine(
            task = explorerTask.task,
            prefix = "Explorer",
            prefixColor = TodoColors.explorerAccent,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 104.dp)
        )
        TaskBackgroundImplementationControls(
            progress = taskExplorerProgress(explorerTask, humanTask, active, patchPath),
            temporaryWorkspace = temporaryWorkspace,
            patchFullPath = patchPath,
            showActions = hovered,
            runImplementationCommandEnabled = runImplementationCommandEnabled,
            onOpenCodeDiff = onOpenCodeDiff,
            onRunImplementationCommand = onRunImplementationCommand,
            onMenuExpandedChange = onMenuExpandedChange,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

internal enum class TaskExplorerProgress {
    Unassigned,
    Queued,
    Working,
    Ready
}

internal fun taskExplorerProgress(
    explorerTask: ComparedExplorerTaskUi,
    humanTask: ComparedTaskUi?,
    active: Boolean,
    patchPath: String? = explorerTask.patchFullPath
): TaskExplorerProgress = when {
    !patchPath.isNullOrBlank() -> TaskExplorerProgress.Ready
    humanTask == null -> TaskExplorerProgress.Unassigned
    active -> TaskExplorerProgress.Working
    else -> TaskExplorerProgress.Queued
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TaskBackgroundImplementationControls(
    progress: TaskExplorerProgress,
    temporaryWorkspace: String?,
    patchFullPath: String?,
    showActions: Boolean,
    runImplementationCommandEnabled: Boolean,
    onOpenCodeDiff: () -> Unit,
    onRunImplementationCommand: () -> Unit,
    onMenuExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val pulse = if (progress == TaskExplorerProgress.Working) {
        statusPulse()
    } else {
        0f
    }
    val completionTime = rememberExplorerPatchModifiedAt(temporaryWorkspace, patchFullPath)
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(completionTime) {
        if (completionTime == null) return@LaunchedEffect
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(60_000)
        }
    }
    Box(modifier = modifier.width(96.dp).height(24.dp), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            visible = !showActions,
            enter = fadeIn(animationSpec = tween(90)),
            exit = fadeOut(animationSpec = tween(70))
        ) {
            when {
                progress == TaskExplorerProgress.Ready && completionTime != null -> {
                    val tooltip = "Explorer implementation completed ${formatAbsoluteTime(completionTime)}"
                    Tooltip(tooltip = { Text(tooltip) }) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                key = AllIconsKeys.Actions.Checked,
                                contentDescription = null,
                                tint = TodoColors.explorerAccent,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = formatRelativeTime(completionTime, nowMillis),
                                color = TodoColors.secondaryText,
                                fontSize = 9.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
                progress == TaskExplorerProgress.Working -> {
                    Tooltip(tooltip = { Text("Explorer implementation in progress") }) {
                        Icon(
                            key = AllIconsKeys.Actions.IntentionBulb,
                            contentDescription = "Explorer implementation in progress",
                            tint = TodoColors.explorerAccent.copy(alpha = 0.48f + pulse * 0.42f),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showActions,
            enter = fadeIn(animationSpec = tween(90)),
            exit = fadeOut(animationSpec = tween(70))
        ) {
            Row(
                modifier = Modifier.width(52.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (completionTime != null) {
                        Tooltip(tooltip = { Text("Open Explorer implementation diff") }) {
                            ToolbarIconButton(
                                iconKey = AllIconsKeys.Actions.Preview,
                                contentDescription = "Open Explorer implementation diff",
                                accentColor = TodoColors.explorerAccent,
                                size = 24.dp,
                                onClick = onOpenCodeDiff
                            )
                        }
                    }
                }
                DelegateImplementationMenu(
                    enabled = runImplementationCommandEnabled,
                    onRunImplementationCommand = onRunImplementationCommand,
                    onExpandedChange = onMenuExpandedChange
                )
            }
        }
    }
}

private fun ComparedExplorerTaskUi.taskIndex(): Int =
    task.id.removePrefix("explorer-").toIntOrNull() ?: 0

private fun ComparedExplorerTaskUi.effectivePatchPath(temporaryWorkspace: String?): String? {
    patchFullPath?.takeIf(String::isNotBlank)?.let { return it }
    val workspace = temporaryWorkspace?.takeIf(String::isNotBlank) ?: return null
    return Path.of(workspace).resolve(".shoaku/task-patches/${taskIndex()}.patch").toString()
}

@Composable
private fun rememberExplorerPatchModifiedAt(
    temporaryWorkspace: String?,
    patchFullPath: String?
): Long? {
    val modifiedAt by produceState<Long?>(null, temporaryWorkspace, patchFullPath) {
        value = withContext(Dispatchers.IO) {
            val workspace = runCatching {
                temporaryWorkspace?.takeIf(String::isNotBlank)?.let(Path::of)
            }.getOrNull()
                ?: return@withContext null
            val requestedPatch = runCatching {
                patchFullPath?.takeIf(String::isNotBlank)?.let(Path::of)
            }.getOrNull()
                ?: return@withContext null
            runCatching {
                resolveTaskPatchPath(workspace, requestedPatch)
                    ?.let(Files::getLastModifiedTime)
                    ?.toMillis()
            }.getOrNull()
        }
    }
    return modifiedAt
}

internal fun formatRelativeTime(completedAtMillis: Long, nowMillis: Long): String {
    val elapsedMinutes = ((nowMillis - completedAtMillis).coerceAtLeast(0) / 60_000).toInt()
    return when {
        elapsedMinutes < 1 -> "just now"
        elapsedMinutes < 60 -> "$elapsedMinutes ${if (elapsedMinutes == 1) "minute" else "minutes"} ago"
        elapsedMinutes < 1_440 -> {
            val hours = elapsedMinutes / 60
            "$hours ${if (hours == 1) "hour" else "hours"} ago"
        }
        else -> {
            val days = elapsedMinutes / 1_440
            "$days ${if (days == 1) "day" else "days"} ago"
        }
    }
}

private val ExplorerCompletionTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a").withZone(ZoneId.systemDefault())

internal fun formatAbsoluteTime(completedAtMillis: Long): String =
    ExplorerCompletionTimeFormatter.format(Instant.ofEpochMilli(completedAtMillis))

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TaskImplementationActions(
    codeDiffEnabled: Boolean,
    runImplementationCommandEnabled: Boolean,
    onOpenCodeDiff: () -> Unit,
    onRunImplementationCommand: () -> Unit,
    onMenuExpandedChange: (Boolean) -> Unit = {}
) {
    Row(
        modifier = Modifier.width(52.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tooltip(tooltip = { Text("Open code diff") }) {
            ToolbarIconButton(
                iconKey = AllIconsKeys.Actions.Preview,
                contentDescription = "Open code diff",
                enabled = codeDiffEnabled,
                size = 24.dp,
                onClick = onOpenCodeDiff
            )
        }
        DelegateImplementationMenu(
            enabled = runImplementationCommandEnabled,
            onRunImplementationCommand = onRunImplementationCommand,
            onExpandedChange = onMenuExpandedChange
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DelegateImplementationMenu(
    enabled: Boolean,
    onRunImplementationCommand: () -> Unit,
    onExpandedChange: (Boolean) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Tooltip(tooltip = { Text("More task actions") }) {
            ToolbarIconButton(
                iconKey = AllIconsKeys.Actions.More,
                contentDescription = "More task actions",
                selected = expanded,
                size = 24.dp,
                onClick = {
                    expanded = !expanded
                    onExpandedChange(expanded)
                }
            )
        }
        if (expanded) {
            PopupMenu(
                onDismissRequest = {
                    expanded = false
                    onExpandedChange(false)
                    true
                },
                horizontalAlignment = Alignment.End
            ) {
                selectableItem(
                    selected = false,
                    iconKey = null,
                    onClick = {
                        if (enabled) onRunImplementationCommand()
                        expanded = false
                        onExpandedChange(false)
                    }
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            key = AllIconsKeys.Actions.Forward,
                            contentDescription = null,
                            tint = if (enabled) TodoColors.primaryText else TodoColors.secondaryText.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Delegate implementation",
                            color = if (enabled) TodoColors.primaryText else TodoColors.secondaryText.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplorerOnlyTasksDisclosure(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(TodoColors.sectionSurface.copy(alpha = 0.38f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            key = AllIconsKeys.Actions.IntentionBulb,
            contentDescription = null,
            tint = TodoColors.explorerAccent.copy(alpha = 0.82f),
            modifier = Modifier.size(11.dp)
        )
        Text(
            text = "$count Explorer ${if (count == 1) "suggestion" else "suggestions"}",
            color = TodoColors.secondaryText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.weight(1f))
        Icon(
            key = if (expanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
            contentDescription = if (expanded) "Hide Explorer suggestions" else "Show Explorer suggestions",
            tint = TodoColors.secondaryText,
            modifier = Modifier.size(13.dp)
        )
    }
}

@Composable
private fun UnifiedTaskLine(
    task: ComparedTaskUi?,
    isActive: Boolean = false,
    prefix: String? = null,
    prefixColor: Color = TodoColors.secondaryText,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.heightIn(min = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (task != null) {
            prefix?.let {
                Text(
                    text = it,
                    color = prefixColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(58.dp)
                )
            }
            if (task.checked) {
                Icon(
                    key = AllIconsKeys.Actions.Checked,
                    contentDescription = "Completed",
                    tint = TodoColors.completedMarker,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isActive) TodoColors.activeMarker else Color.Transparent)
                        .border(
                            1.dp,
                            if (isActive) TodoColors.activeMarker else TodoColors.pendingMarker,
                            RoundedCornerShape(999.dp)
                        )
                )
            }
            Text(
                text = task.content,
                color = if (task.checked) TodoColors.completedTaskText else TodoColors.primaryText,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
    }
}

@Composable
private fun ConversationNavigationCard(
    isThinking: Boolean,
    hasResponse: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (hovered) TodoColors.currentTaskSurface else TodoColors.taskResponseSurface)
            .border(1.dp, TodoColors.sectionDivider, RoundedCornerShape(7.dp))
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            key = AllIconsKeys.General.Balloon,
            contentDescription = null,
            tint = TodoColors.linkText,
            modifier = Modifier.size(16.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text("Conversation", color = TodoColors.primaryText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = when {
                    isThinking -> "Shoaku is thinking…"
                    hasResponse -> "Response available"
                    else -> "Ask Shoaku or review the current task"
                },
                color = TodoColors.secondaryText,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            key = AllIconsKeys.Actions.Forward,
            contentDescription = "Open Conversation",
            tint = TodoColors.linkText,
            modifier = Modifier.size(16.dp)
        )
    }
}

private const val AlignmentGapMessageThreshold = 0.9
private const val ThinkingMessage = "Thinking"
private const val ReviewTaskTitle = "Final Check"
private const val NoFinalCheckIssuesMessage = "No issues found."
private const val UnderstandingGuidanceMode = "deepen_understanding"

private fun isFixedResponseText(message: String) =
    message == ThinkingMessage || message == NoFinalCheckIssuesMessage

private class FinalCheckDisplayState {
    val requested = mutableStateOf(false)
    val thinking = mutableStateOf(false)
    val startMessageCount = mutableStateOf<Int?>(null)
}

private class ReplyDisplayState {
    val thinking = mutableStateOf(false)
    val startMessageCount = mutableStateOf<Int?>(null)
}

private class TaskGuidanceDisplayState {
    val requested = mutableStateOf(false)
    val thinking = mutableStateOf(false)
    val startMessageCount = mutableStateOf<Int?>(null)
}

private data class TaskResponseDisplay(
    val text: String,
    val kind: TaskResponseKind = TaskResponseKind.Detail
)

private enum class TaskResponseKind {
    Clarification,
    Status,
    Detail
}

internal sealed interface AlignmentDisplayState {
    data object Unavailable : AlignmentDisplayState
    data object Checking : AlignmentDisplayState
    data object InSync : AlignmentDisplayState
    data class NeedsInput(val message: String) : AlignmentDisplayState
}

internal data class ActiveTaskKey(
    val index: Int,
    val content: String?
)

internal data class ActiveTaskMessageBoundary(
    val activeTaskKey: ActiveTaskKey,
    val messageCount: Int
)

internal fun messagesForActiveTask(
    boundary: ActiveTaskMessageBoundary,
    activeTaskKey: ActiveTaskKey,
    messages: List<Message>
): List<Message> {
    if (boundary.activeTaskKey != activeTaskKey) {
        return emptyList()
    }
    return messages.drop(boundary.messageCount.coerceIn(0, messages.size))
}

private enum class TaskItemState {
    Pending,
    Current,
    Completed
}

private enum class TaskRowKind {
    Task,
    FinalCheck
}

@Composable
private fun TaskListCard(
    todoItems: List<Item>,
    activeItemIndex: Int,
    guidanceActionsEnabled: Boolean,
    chatEnabled: Boolean,
    reviewCurrentTaskEnabled: Boolean,
    runImplementationCommandEnabled: Boolean,
    onReviewCurrentTask: () -> Unit,
    onRunImplementationCommand: () -> Unit,
    onOpenChat: () -> Unit,
    onDeepenUnderstanding: () -> Unit,
    showCurrentTaskContent: Boolean,
    currentTaskContent: @Composable ColumnScope.() -> Unit,
    reviewEnabled: Boolean,
    finalCheckActive: Boolean,
    reviewResponse: Message?,
    onReviewClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (todoItems.isEmpty()) {
            Column(
                modifier = Modifier.padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "No tasks added to this goal.",
                    color = TodoColors.secondaryText,
                    style = JewelTheme.defaultTextStyle
                )
            }
        }
        if (todoItems.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                todoItems.forEachIndexed { index, model ->
                    val state = when {
                        index == activeItemIndex -> TaskItemState.Current
                        model.checked == true -> TaskItemState.Completed
                        else -> TaskItemState.Pending
                    }
                    if (state == TaskItemState.Current) {
                        PlanSuggestionCard(
                            title = "Consider before continuing",
                            suggestionCount = 1,
                            enabled = guidanceActionsEnabled,
                            onAskShoaku = onDeepenUnderstanding
                        )
                    }
                    TaskListRow(
                        title = model.content,
                        state = state,
                        actionContent = if (state == TaskItemState.Current) {
                            { hovered ->
                                TaskGuidanceActionBar(
                                    visible = hovered,
                                    enabled = guidanceActionsEnabled,
                                    chatEnabled = chatEnabled,
                                    reviewEnabled = reviewCurrentTaskEnabled,
                                    runCommandEnabled = runImplementationCommandEnabled,
                                    onOpenChat = onOpenChat,
                                    onDeepenUnderstanding = onDeepenUnderstanding,
                                    onReview = onReviewCurrentTask,
                                    onRunCommand = onRunImplementationCommand
                                )
                            }
                        } else null,
                        attachedContent = currentTaskContent.takeIf {
                            state == TaskItemState.Current && showCurrentTaskContent
                        }
                    )
                }
                PlanSuggestionCard(
                    title = "Consider later",
                    suggestionCount = 1,
                    enabled = guidanceActionsEnabled,
                    onAskShoaku = onDeepenUnderstanding
                )
                TaskListRow(
                    title = ReviewTaskTitle,
                    state = if (finalCheckActive) TaskItemState.Current else TaskItemState.Pending,
                    kind = TaskRowKind.FinalCheck,
                    enabled = finalCheckActive && reviewEnabled,
                    actionContent = { visible ->
                        OpinionActionButton(
                            visible = visible,
                            enabled = finalCheckActive && reviewResponse?.text != ThinkingMessage,
                            tooltip = "Get Shoaku's final opinion",
                            iconKey = AllIconsKeys.Actions.Preview,
                            onClick = onReviewClick
                        )
                    },
                    attachedContent = currentTaskContent.takeIf { finalCheckActive }
                )
            }
        } else {
            TaskListRow(
                title = ReviewTaskTitle,
                state = TaskItemState.Pending,
                kind = TaskRowKind.FinalCheck,
                enabled = false,
                actionLabel = "Run Check",
                onActionClick = onReviewClick,
                actionEnabled = false,
                attachedContent = currentTaskContent
            )
        }
    }
}

@Composable
private fun PlanSuggestionCard(
    title: String,
    suggestionCount: Int,
    enabled: Boolean,
    onAskShoaku: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 4.dp)
            .hoverable(interactionSource)
            .focusable()
            .onFocusChanged { focused = it.hasFocus }
            .padding(start = 7.5.dp, top = 5.dp, bottom = 5.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(TodoColors.sectionDivider)
        )
        Icon(
            key = AllIconsKeys.Actions.IntentionBulb,
            contentDescription = null,
            tint = TodoColors.secondaryText,
            modifier = Modifier.size(14.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = title,
                color = TodoColors.primaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Not in current plan  ·  $suggestionCount suggestion",
                color = TodoColors.secondaryText,
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal
            )
        }
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            OpinionActionButton(
                visible = hovered || focused,
                enabled = enabled,
                tooltip = "Ask Shoaku",
                iconKey = AllIconsKeys.General.Balloon,
                onClick = onAskShoaku
            )
        }
    }
}

@Composable
private fun TaskListRow(
    title: String,
    state: TaskItemState,
    kind: TaskRowKind = TaskRowKind.Task,
    response: TaskResponseDisplay? = null,
    onReviewLinkClick: ((String) -> Unit)? = null,
    reviewComments: List<ReviewComment> = emptyList(),
    selectedReviewLocation: ReviewLocation? = null,
    onReviewCommentClick: ((ReviewComment) -> Unit)? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
    actionContent: (@Composable (hovered: Boolean) -> Unit)? = null,
    attachedContent: (@Composable ColumnScope.() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isActiveGroup = state == TaskItemState.Current
    val isInteractiveGroup = isActiveGroup || actionContent != null
    val activeGroupShape = RoundedCornerShape(8.dp)
    val groupInteractionSource = remember { MutableInteractionSource() }
    val groupHovered by groupInteractionSource.collectIsHoveredAsState()
    var groupFocused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = if (isActiveGroup) 8.dp else 0.dp)
            .then(
                if (isInteractiveGroup) {
                    Modifier
                        .clip(activeGroupShape)
                        .background(
                            if (isActiveGroup) {
                                TodoColors.activeTaskGroupSurface
                            } else if (groupHovered) {
                                TodoColors.currentTaskSurface.copy(alpha = 0.55f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (groupHovered) {
                                TodoColors.activeTaskGroupHoverBorder
                            } else {
                                Color.Transparent
                            },
                            shape = activeGroupShape
                        )
                        .hoverable(groupInteractionSource)
                        .focusable()
                        .onFocusChanged { groupFocused = it.hasFocus }
                } else {
                    Modifier
                }
            ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (state == TaskItemState.Current) TodoColors.currentTaskSurface else Color.Transparent)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 34.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TaskItemMarker(state = state, kind = kind)
                Text(
                    text = title,
                    color = TodoColors.taskItemTitle(state, enabled),
                    style = JewelTheme.defaultTextStyle.copy(
                        fontWeight = if (state == TaskItemState.Current) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .then(if (actionContent != null) Modifier.padding(end = 20.dp) else Modifier)
                )
                if (actionContent == null && actionLabel != null) {
                    DefaultButton(
                        onClick = { onActionClick?.invoke() },
                        enabled = enabled && actionEnabled && onActionClick != null,
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(actionLabel)
                    }
                }
            }
            if (actionContent != null) {
                Box(
                    modifier = Modifier.matchParentSize()
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp, end = 6.dp)
                    ) {
                        actionContent(groupHovered || groupFocused)
                    }
                }
            }
        }
        if (response != null) {
            AttachedResponseCard(
                response = response,
                onReviewLinkClick = onReviewLinkClick,
                reviewComments = reviewComments,
                selectedReviewLocation = selectedReviewLocation,
                onReviewCommentClick = onReviewCommentClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp)
            )
        }
        if (attachedContent != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                content = attachedContent
            )
        }
    }
}

@Composable
private fun TaskTimelineNode(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        content = content
    )
}

@Composable
private fun TaskItemMarker(
    state: TaskItemState,
    kind: TaskRowKind = TaskRowKind.Task
) {
    Box(
        modifier = Modifier
            .width(20.dp)
            .height(20.dp),
        contentAlignment = Alignment.Center
    ) {
        if (kind == TaskRowKind.FinalCheck) {
            Icon(
                key = AllIconsKeys.Actions.Preview,
                contentDescription = "Final check",
                tint = TodoColors.taskItemMarkerText(state),
                modifier = Modifier.size(16.dp)
            )
        } else {
            when (state) {
                TaskItemState.Completed -> Icon(
                    key = AllIconsKeys.Actions.Checked,
                    contentDescription = "Completed",
                    tint = TodoColors.taskItemMarkerText(state),
                    modifier = Modifier.size(14.dp)
                )
                TaskItemState.Current -> Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(TodoColors.taskItemMarkerText(state))
                )
                TaskItemState.Pending -> Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(TodoColors.taskItemMarkerText(state))
                )
            }
        }
    }
}

@Composable
private fun AttachedResponseCard(
    response: TaskResponseDisplay?,
    responseLabel: String = "Shoaku review",
    onReviewLinkClick: ((String) -> Unit)? = null,
    reviewComments: List<ReviewComment> = emptyList(),
    selectedReviewLocation: ReviewLocation? = null,
    onReviewCommentClick: ((ReviewComment) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val displayText = response?.text?.trim().orEmpty()
    val hasMessage = displayText.isNotEmpty()
    val kind = response?.kind

    Column(
        modifier = modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (hasMessage) {
            if (displayText == ThinkingMessage) {
                ThinkingIndicator(modifier = Modifier.padding(start = 8.dp, top = 2.dp))
            } else if (kind == TaskResponseKind.Status && reviewComments.isEmpty()) {
                Text(
                    text = displayText,
                    color = TodoColors.secondaryText,
                    style = JewelTheme.defaultTextStyle,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                )
            } else if (kind == TaskResponseKind.Clarification) {
                TaskResponsePanel(label = "Shoaku response") {
                    AgentMessageContent(
                        message = displayText,
                        onLinkClick = onReviewLinkClick
                    )
                }
            } else {
                TaskResponsePanel(label = responseLabel) {
                    AgentMessageContent(
                        message = displayText,
                        onLinkClick = onReviewLinkClick
                    )
                    if (reviewComments.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(TodoColors.reviewCommentDivider)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            reviewComments.forEach { comment ->
                                ReviewCommentRow(
                                    comment = comment,
                                    selected = selectedReviewLocation == ReviewLocation(comment.path, comment.line),
                                    onClick = onReviewCommentClick
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
@Composable
private fun TaskResponsePanel(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 3.dp, bottom = 2.dp)
            .clip(shape)
            .background(TodoColors.taskResponseSurface)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = label,
            color = TodoColors.taskResponseLabelText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        content()
    }
}

@Composable
private fun ReviewCommentRow(
    comment: ReviewComment,
    selected: Boolean,
    onClick: ((ReviewComment) -> Unit)?
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> TodoColors.reviewCommentSelectedSurface
                    hovered -> TodoColors.reviewCommentHoverSurface
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) TodoColors.reviewCommentSelectedBorder else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = onClick != null
            ) { onClick?.invoke(comment) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${comment.path}:${comment.line}",
                color = TodoColors.linkText,
                fontSize = 11.sp,
                textDecoration = TextDecoration.Underline
            )
            Text(
                text = "Open diff ↗",
                color = TodoColors.linkText,
                fontSize = 11.sp
            )
        }
        Text(
            text = comment.text,
            color = TodoColors.infoText,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun ConversationComposerNode(
    response: TaskResponseDisplay?,
    reviewComments: List<ReviewComment>,
    selectedReviewLocation: ReviewLocation?,
    onResponseLinkClick: ((String) -> Unit)?,
    onReviewCommentClick: ((ReviewComment) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val nodeShape = RoundedCornerShape(7.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(nodeShape)
            .background(TodoColors.taskResponseSurface)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        response?.let { currentResponse ->
            if (currentResponse.text == ThinkingMessage) {
                ThinkingIndicator()
            } else if (currentResponse.kind == TaskResponseKind.Status && reviewComments.isEmpty()) {
                Text(
                    text = currentResponse.text,
                    color = TodoColors.secondaryText,
                    style = JewelTheme.defaultTextStyle,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            } else {
                AgentMessageContent(
                    message = currentResponse.text,
                    onLinkClick = onResponseLinkClick
                )
                if (reviewComments.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(TodoColors.reviewCommentDivider)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        reviewComments.forEach { comment ->
                            ReviewCommentRow(
                                comment = comment,
                                selected = selectedReviewLocation ==
                                    ReviewLocation(comment.path, comment.line),
                                onClick = onReviewCommentClick
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ExpandedConversationPane(
    messages: List<Message>,
    isThinking: Boolean,
    contextLabel: String,
    onExpandedChange: (Boolean) -> Unit,
    instructionValue: TextFieldValue,
    onInstructionValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
    placeholder: String,
    onSend: () -> Unit,
    codeDiffEnabled: Boolean,
    runImplementationCommandEnabled: Boolean,
    onOpenCodeDiff: () -> Unit,
    onRunImplementationCommand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleMessages = remember(messages) {
        visibleChatMessages(messages)
    }
    val historyItemCount = visibleMessages.size + if (isThinking) 1 else 0
    val historyListState = rememberLazyListState()
    val sheetFocusRequester = remember { FocusRequester() }

    LaunchedEffect(historyItemCount) {
        sheetFocusRequester.requestFocus()
        if (historyItemCount > 0) {
            historyListState.scrollToItem(historyItemCount - 1)
        }
    }

    Column(
        modifier = modifier
            .focusRequester(sheetFocusRequester)
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tooltip(
                tooltip = {
                    Row {
                        Text("Back to Tasks")
                        Text(
                            text = " (Esc)",
                            color = TodoColors.secondaryText
                        )
                    }
                }
            ) {
                ToolbarIconButton(
                    iconKey = AllIconsKeys.Actions.Back,
                    contentDescription = "Back to Tasks",
                    size = 24.dp,
                    onClick = { onExpandedChange(false) }
                )
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(TodoColors.sectionDivider)
            )
            Text(
                text = "Conversation",
                color = TodoColors.primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "\u00b7",
                color = TodoColors.secondaryText,
                fontSize = 10.sp
            )
            Text(
                text = contextLabel,
                color = TodoColors.secondaryText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TaskImplementationActions(
                codeDiffEnabled = codeDiffEnabled,
                runImplementationCommandEnabled = runImplementationCommandEnabled,
                onOpenCodeDiff = onOpenCodeDiff,
                onRunImplementationCommand = onRunImplementationCommand
            )
        }
        ScrollHintLazyColumn(
            state = historyListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
        ) {
            itemsIndexed(visibleMessages) { index, entry ->
                ConversationEntry(
                    messages = visibleMessages,
                    index = index,
                    entry = entry
                )
            }
            if (isThinking) {
                item {
                    ChatEntryRow(
                        entry = Message(type = "agentMessage", text = ThinkingMessage),
                        showTurnDivider = false,
                        topSpacing = if (visibleMessages.isEmpty()) 4.dp else 8.dp
                    )
                }
            }
        }
        ChatComposer(
            value = instructionValue,
            onValueChange = onInstructionValueChange,
            enabled = enabled,
            placeholder = placeholder,
            onSend = onSend,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ConversationEntry(
    messages: List<Message>,
    index: Int,
    entry: Message
) {
    val previousEntry = messages.getOrNull(index - 1)
    val startsNewTurn =
        entry.turnId != null &&
            entry.type != "userMessage" &&
            entry.turnId != previousEntry?.turnId
    val previousIsSameSpeaker =
        previousEntry?.type == entry.type &&
            previousEntry.turnId == entry.turnId &&
            previousEntry.command == null &&
            entry.command == null

    ChatEntryRow(
        entry = entry,
        showTurnDivider = startsNewTurn,
        topSpacing = when {
            index == 0 -> 4.dp
            startsNewTurn -> 14.dp
            previousIsSameSpeaker -> 4.dp
            else -> 12.dp
        }
    )
}

internal fun alignmentDisplayState(messages: List<Message>): AlignmentDisplayState {
    val latestScoredAgentMessage = messages.lastOrNull {
        it.type == "agentMessage" && it.alignmentScore != null
    } ?: return AlignmentDisplayState.Unavailable
    val alignmentScore = requireNotNull(latestScoredAgentMessage.alignmentScore)

    if (latestScoredAgentMessage.phase != "final_answer") {
        return AlignmentDisplayState.Checking
    }
    val responseText = latestScoredAgentMessage.text?.trim().takeUnless { it.isNullOrEmpty() }
    if (alignmentScore >= AlignmentGapMessageThreshold || responseText == null) {
        return AlignmentDisplayState.InSync
    }
    return AlignmentDisplayState.NeedsInput(responseText)
}

internal fun hasResponseAfterLatestAlignment(messages: List<Message>): Boolean {
    val latestAlignmentIndex = messages.indexOfLast {
        it.type == "agentMessage" &&
            it.phase == "final_answer" &&
            it.alignmentScore != null
    }
    if (latestAlignmentIndex < 0) {
        return false
    }

    return messages.drop(latestAlignmentIndex + 1).any { message ->
        !message.text.isNullOrBlank() &&
            (
                message.type == "userMessage" ||
                    (
                        message.type == "agentMessage" &&
                            message.phase == "final_answer" &&
                            message.alignmentScore == null
                        )
                )
    }
}

private fun isUnexpectedMessageType(type: String): Boolean =
    type !in setOf(
        "userMessage",
        "agentMessage",
        "commandExecution",
        "webSearch",
        "contextCompaction",
        "contextCompactionStarted"
    )

private fun isTaskComparisonMessage(message: Message): Boolean =
    message.taskComparison != null

private fun isVisibleChatMessage(message: Message): Boolean =
    // Reasoning items are frequent internal progress updates and add noise to the conversation.
    message.type != "reasoning" &&
        message.phase != "final_check" &&
        message.alignmentScore == null &&
        (
            message.type in setOf("contextCompaction", "contextCompactionStarted") ||
                isTaskComparisonMessage(message) ||
                isUnexpectedMessageType(message.type) ||
                message.command?.isNotBlank() == true ||
                message.text?.isNotBlank() == true
            )

internal fun visibleChatMessages(messages: List<Message>): List<Message> {
    val completedCompactionTurnIds = messages.asSequence()
        .filter { it.type == "contextCompaction" }
        .mapNotNull(Message::turnId)
        .toSet()

    return messages.filter { message ->
        isVisibleChatMessage(message) &&
            !(message.type == "contextCompactionStarted" &&
                message.turnId in completedCompactionTurnIds)
    }
}

internal fun latestTaskResponse(messages: List<Message>): Message? =
    messages.lastOrNull {
        it.type == "agentMessage" &&
            it.phase == "final_answer" &&
            it.alignmentScore == null
    }

private fun finalCheckResponse(messages: List<Message>): Message? =
    latestTaskResponse(messages)

@Composable
private fun TokenUsageIndicator(
    tokenUsage: TokenUsageUi?,
    status: AgentStatusUi? = null,
    onIncreaseBudget: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val explorerBudgetExhausted = tokenUsage?.isExplorerTokenBudgetExhausted() == true
    val activity = remember(status, explorerBudgetExhausted) {
        activityFromStatus(status, explorerBudgetExhausted)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val hasRunningAgent = activity.any { it.isRunning }
    val transition = rememberInfiniteTransition(label = "tokenUsageActivity")
    val pulse by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tokenUsageActivityPulse"
    )
    val navigatorUsageFraction = remember(tokenUsage) {
        tokenUsage?.let {
            (it.navigatorTokens.coerceAtLeast(0).toFloat() / it.maxTokens.coerceAtLeast(1)).coerceIn(0f, 1f)
        } ?: 0f
    }
    val explorerUsageFraction = remember(tokenUsage, navigatorUsageFraction) {
        tokenUsage?.let {
            (it.explorerTokens.coerceAtLeast(0).toFloat() / it.maxTokens.coerceAtLeast(1))
                .coerceIn(0f, 1f - navigatorUsageFraction)
        } ?: 0f
    }
    val chipBackground = when {
        pressed -> TodoColors.tokenUsageTrackBorder.copy(alpha = 0.18f)
        hovered || expanded -> TodoColors.tokenUsageTrackBorder.copy(alpha = 0.1f)
        hasRunningAgent -> TodoColors.activityGlow(activityPulse = pulse)
        else -> Color.Transparent
    }
    val chipBorder = when {
        hasRunningAgent -> TodoColors.activityBorder(activityPulse = pulse, emphasized = hovered || expanded)
        hovered || expanded -> TodoColors.tokenUsageTrackBorder.copy(alpha = 0.5f)
        else -> TodoColors.tokenUsageTrackBorder.copy(alpha = 0.22f)
    }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(chipBackground)
                .border(1.dp, chipBorder, RoundedCornerShape(999.dp))
                .hoverable(interactionSource)
                .clickable(indication = null, interactionSource = interactionSource) {
                    expanded = !expanded
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .drawBehind {
                        val strokeWidth = size.minDimension * 0.22f
                        if (hasRunningAgent) {
                            drawCircle(
                                color = TodoColors.activityGlowStrong(activityPulse = pulse),
                                radius = size.minDimension * (0.68f + 0.2f * pulse)
                            )
                        }
                        drawArc(
                            color = TodoColors.tokenUsageTrackBorder.copy(alpha = 0.45f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = strokeWidth)
                        )
                        if (navigatorUsageFraction > 0f) {
                            drawArc(
                                color = TodoColors.navigatorTokenUsage,
                                startAngle = -90f,
                                sweepAngle = 360f * navigatorUsageFraction,
                                useCenter = false,
                                style = Stroke(width = strokeWidth)
                            )
                        }
                        if (explorerUsageFraction > 0f) {
                            drawArc(
                                color = TodoColors.explorerTokenUsage,
                                startAngle = -90f + 360f * navigatorUsageFraction,
                                sweepAngle = 360f * explorerUsageFraction,
                                useCenter = false,
                                style = Stroke(width = strokeWidth)
                            )
                        }
                    }
            )
            Text(
                text = formatTokenSummary(tokenUsage),
                fontSize = 11.sp,
                color = TodoColors.secondaryText
            )
        }

        if (expanded && tokenUsage != null) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, 32),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true
                )
            ) {
                TokenUsagePopupContent(
                    tokenUsage = tokenUsage,
                    activity = activity,
                    onIncreaseBudget = onIncreaseBudget
                )
            }
        }
    }
}

@Composable
private fun TokenUsagePopupContent(
    tokenUsage: TokenUsageUi,
    activity: List<AgentActivityState>,
    onIncreaseBudget: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(min = 300.dp, max = 360.dp)
            .shadow(16.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(TodoColors.popupSurface)
            .border(1.dp, TodoColors.popupBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Token budget",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TodoColors.primaryText
            )
        }
        TokenUsageCard(
            tokenUsage = tokenUsage,
            activity = activity,
            onIncreaseBudget = onIncreaseBudget,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TokenUsageCard(
    tokenUsage: TokenUsageUi,
    activity: List<AgentActivityState>,
    onIncreaseBudget: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var addButtonHovered by remember { mutableStateOf(false) }
    val navigatorTokens = tokenUsage.navigatorTokens.coerceAtLeast(0)
    val explorerTokens = tokenUsage.explorerTokens.coerceAtLeast(0)
    val totalTokens = navigatorTokens + explorerTokens
    val maxTokens = tokenUsage.maxTokens.coerceAtLeast(1)
    val clampedNavigatorFraction = (navigatorTokens.toFloat() / maxTokens).coerceIn(0f, 1f)
    val clampedExplorerFraction = (explorerTokens.toFloat() / maxTokens).coerceIn(0f, 1f - clampedNavigatorFraction)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = TodoColors.popupSecondaryText)) {
                        append(formatTokenCount(totalTokens))
                        append(" / ")
                    }
                    withStyle(
                        SpanStyle(
                            color = if (addButtonHovered) TodoColors.primaryText else TodoColors.popupSecondaryText
                        )
                    ) {
                        append(formatTokenCount(maxTokens))
                    }
                },
                fontSize = 12.sp
            )
            CompactAddButton(
                onHoverChange = { addButtonHovered = it },
                onClick = { onIncreaseBudget?.invoke() }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(TodoColors.tokenUsageTrack)
                .border(1.dp, TodoColors.tokenUsageTrackBorder, RoundedCornerShape(999.dp))
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (clampedNavigatorFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(clampedNavigatorFraction)
                            .background(TodoColors.navigatorTokenUsage)
                    )
                }
                if (clampedExplorerFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(clampedExplorerFraction / (1f - clampedNavigatorFraction).coerceAtLeast(0.0001f))
                            .background(TodoColors.explorerTokenUsage)
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TokenLegendItem(
                color = TodoColors.navigatorTokenUsage,
                label = "Navigator",
                state = activity.getOrNull(0),
                usagePercent = "${(clampedNavigatorFraction * 100).roundToInt()}%",
                usageAmount = formatTokenCount(navigatorTokens)
            )
            TokenLegendItem(
                color = TodoColors.explorerTokenUsage,
                label = "Explorer",
                state = activity.getOrNull(1),
                usagePercent = "${(clampedExplorerFraction * 100).roundToInt()}%",
                usageAmount = formatTokenCount(explorerTokens)
            )
        }
    }
}

@Composable
private fun TokenLegendItem(
    color: Color,
    label: String,
    state: AgentActivityState? = null,
    usagePercent: String,
    usageAmount: String
) {
    val isRunning = state?.isRunning == true
    val statusTone = state?.tone ?: AgentStatusTone.Hidden
    val statusLabel = state?.displayStatus.orEmpty()
    val transition = rememberInfiniteTransition(label = "${label}LegendIndicator")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "${label}LegendIndicatorPulse"
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    color.copy(
                        alpha = when {
                            statusTone == AgentStatusTone.Disabled -> 0.28f
                            isRunning -> 0.92f
                            else -> 0.72f
                        }
                    )
                )
        )
        Text(
            text = label,
            color = when {
                statusTone == AgentStatusTone.Disabled -> TodoColors.popupSecondaryText.copy(alpha = 0.55f)
                isRunning -> TodoColors.primaryText
                else -> TodoColors.popupSecondaryText
            },
            fontSize = 11.sp,
            fontWeight = if (isRunning) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.width(64.dp)
        )
        if (statusTone != AgentStatusTone.Hidden) {
            StatusPill(
                label = statusLabel,
                tone = statusTone,
                pulse = pulse,
                minWidth = Dp.Unspecified
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = usageAmount,
                color = TodoColors.popupSecondaryText,
                fontSize = 11.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.width(40.dp)
            )
            Text(
                text = "($usagePercent)",
                color = TodoColors.popupSecondaryText,
                fontSize = 11.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.width(38.dp)
            )
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    tone: AgentStatusTone,
    pulse: Float,
    modifier: Modifier = Modifier,
    minWidth: Dp = Dp.Unspecified
) {
    val background = when (tone) {
        AgentStatusTone.Running -> TodoColors.statusRunningSurface(activityPulse = pulse)
        AgentStatusTone.Error -> TodoColors.statusError.copy(alpha = 0.16f)
        AgentStatusTone.Disabled -> TodoColors.tokenUsageTrackBorder.copy(alpha = 0.12f)
        AgentStatusTone.Hidden -> Color.Transparent
    }
    val border = when (tone) {
        AgentStatusTone.Running -> TodoColors.statusRunningBorder(activityPulse = pulse)
        AgentStatusTone.Error -> TodoColors.statusError.copy(alpha = 0.42f)
        AgentStatusTone.Disabled -> TodoColors.tokenUsageTrackBorder.copy(alpha = 0.4f)
        AgentStatusTone.Hidden -> Color.Transparent
    }
    val content = when (tone) {
        AgentStatusTone.Running -> TodoColors.statusRunningText
        AgentStatusTone.Error -> TodoColors.statusError
        AgentStatusTone.Disabled -> TodoColors.popupSecondaryText.copy(alpha = 0.72f)
        AgentStatusTone.Hidden -> Color.Transparent
    }

    Box(
        modifier = modifier
            .then(if (minWidth != Dp.Unspecified) Modifier.widthIn(min = minWidth) else Modifier)
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .padding(
                horizontal = 8.dp,
                vertical = 3.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = content,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CompactAddButton(
    onHoverChange: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val background = when {
        pressed -> TodoColors.tokenUsageButtonPressed
        hovered -> TodoColors.tokenUsageButtonHover
        else -> TodoColors.tokenUsageButtonSurface
    }

    LaunchedEffect(hovered) {
        onHoverChange(hovered)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .border(1.dp, TodoColors.tokenUsageButtonBorder, RoundedCornerShape(999.dp))
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "+10%",
            color = TodoColors.tokenUsageButtonContent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ChatComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
    placeholder: String = "Ask Shoaku",
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSend = enabled && value.text.isNotBlank()
    var isFocused by remember { mutableStateOf(false) }
    val composerShape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(composerShape)
            .background(TodoColors.composerSurface)
            .border(
                1.dp,
                if (isFocused) TodoColors.composerFocusBorder else TodoColors.composerBorder,
                composerShape
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .padding(end = 42.dp, bottom = 26.dp)
                .onFocusChanged { isFocused = it.isFocused }
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
                        text = placeholder,
                        color = TodoColors.secondaryText.copy(alpha = 0.75f),
                        fontSize = 13.sp
                    )
                }
                innerTextField()
            }
        )
        Text(
            text = "Enter to send  Shift+Enter for newline",
            color = TodoColors.secondaryText.copy(alpha = 0.72f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(end = 42.dp)
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
        project.sendNotificationToShoakuServer { server ->
            server.reply(
                ReplyParams(
                    shoakuId,
                    instruction
                )
            )
        }
    }
}

private fun runImplementationForkCommand(project: Project?, sessionId: String?, task: String) {
    if (sessionId.isNullOrBlank()) return
    val escapedTask = task.replace("'", "'\\\"'\\\"'")
    val command = "codex fork $sessionId 'Implement: $escapedTask'"
    val workingDirectory = project?.basePath ?: return

    runCatching {
        val terminal = TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(workingDirectory, "Codex: Shoaku", true, true)
        terminal.executeCommand(command)
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

private fun formatTokenCount(value: Int): String {
    return compactTokenFormatter.format(value.coerceAtLeast(0))
}

private fun formatTokenSummary(tokenUsage: TokenUsageUi?): String {
    if (tokenUsage == null) {
        return "-- / --"
    }

    val totalTokens = tokenUsage.navigatorTokens.coerceAtLeast(0) + tokenUsage.explorerTokens.coerceAtLeast(0)
    return "${formatTokenCount(totalTokens)} / ${formatTokenCount(tokenUsage.maxTokens.coerceAtLeast(1))}"
}

private val compactTokenFormatter: NumberFormat =
    NumberFormat.getCompactNumberInstance(Locale.US, NumberFormat.Style.SHORT).apply {
        maximumFractionDigits = 1
    }

@Composable
private fun SessionSectionCard(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .background(TodoColors.sectionSurface, RoundedCornerShape(8.dp))
            .padding(TodoMetrics.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        header()
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
private fun SessionSectionHeader(
    title: String,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TodoColors.primaryText
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TodoColors.sectionDivider)
        )
    }
}

@Composable
private fun GoalsEmptyState(
    modifier: Modifier = Modifier
) {
    val sampleShape = RoundedCornerShape(10.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InfoCard(title = "How it works") {
            Text(
                text = "1. Choose a `.md` file in Goals file.",
                fontSize = 12.sp,
                color = TodoColors.secondaryText
            )
            Text(
                text = "2. Add `[shoaku]` to any goal you want Shoaku to investigate and implement.",
                fontSize = 12.sp,
                color = TodoColors.secondaryText
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TodoColors.codeBlockSurface, sampleShape)
                .border(1.dp, TodoColors.codeBlockBorder, sampleShape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Example",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TodoColors.primaryText
            )
            Text(
                text = EmptyGoalsSample,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = TodoColors.infoText
            )
        }
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
    val scope = rememberCoroutineScope()
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
            ScrollHintButton {
                val lastIndex = state.layoutInfo.totalItemsCount - 1
                if (lastIndex >= 0) {
                    scope.launch { state.animateScrollToItem(lastIndex) }
                }
            }
        }
    }
}

@Composable
private fun ScrollHintButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(TodoColors.scrollHintSurface)
            .border(1.dp, TodoColors.scrollHintBorder, RoundedCornerShape(999.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(bottom = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "↓",
            color = TodoColors.scrollHintContent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ChatEntryRow(
    entry: Message,
    showTurnDivider: Boolean,
    topSpacing: Dp
) {
    if (
        isTaskComparisonMessage(entry) ||
        isUnexpectedMessageType(entry.type) ||
            entry.command != null ||
            entry.type in setOf("contextCompaction", "contextCompactionStarted")
    ) {
        ChatActivityRow(
            entry = entry,
            showTurnDivider = showTurnDivider,
            topSpacing = topSpacing
        )
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
        if (showTurnDivider) {
            ChatTurnDivider()
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isUser) {
                    val userBubbleShape = RoundedCornerShape(14.dp)
                    Text(
                        text = message,
                        color = TodoColors.userMessageText,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        modifier = Modifier
                            .clip(userBubbleShape)
                            .background(TodoColors.userMessageSurface)
                            .border(1.dp, TodoColors.userMessageBorder, userBubbleShape)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                } else {
                    AgentMessageContent(
                        message = message,
                        style = agentMessageTextStyle(message)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatTurnDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(TodoColors.agentMessageDivider)
    )
}

@Composable
private fun AgentMessageContent(
    message: String,
    onLinkClick: ((String) -> Unit)? = null,
    style: TextStyle = agentMessageTextStyle(message)
) {
    if (message == ThinkingMessage) {
        ThinkingIndicator()
        return
    }
    val blocks = remember(message) { parseAgentMessageBlocks(message) }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        blocks.forEach { block ->
            when (block) {
                is AgentMessageBlock.Paragraph -> MarkdownTextBlock(text = block.text, style = style, onLinkClick = onLinkClick)
                is AgentMessageBlock.Heading -> MarkdownTextBlock(
                    text = block.text,
                    style = TextStyle(
                        color = TodoColors.primaryText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = headingFontSize(block.level),
                        lineHeight = (headingFontSize(block.level).value + 7).sp
                    )
                )
                is AgentMessageBlock.UnorderedList -> MarkdownList(
                    items = block.items.map { MarkdownListEntry(marker = "•", text = it) },
                    onLinkClick = onLinkClick
                )
                is AgentMessageBlock.OrderedList -> MarkdownList(
                    items = block.items.map { (number, item) -> MarkdownListEntry(marker = "$number.", text = item) },
                    onLinkClick = onLinkClick
                )
                is AgentMessageBlock.Table -> MarkdownTable(block, onLinkClick)
                is AgentMessageBlock.Quote -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .background(TodoColors.agentMessageDivider)
                    )
                    MarkdownTextBlock(
                        text = block.text,
                        modifier = Modifier.weight(1f),
                        style = TextStyle(
                            color = TodoColors.secondaryText,
                            fontStyle = FontStyle.Italic,
                            fontSize = 13.sp,
                            lineHeight = 21.sp
                        )
                    )
                }
                is AgentMessageBlock.Code -> CodeBlock(block)
                AgentMessageBlock.ThematicBreak -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(TodoColors.agentMessageDivider)
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator(
    modifier: Modifier = Modifier
) {
    ShimmeringStatusText(
        text = ThinkingMessage,
        modifier = modifier
    )
}

@Composable
private fun ShimmeringStatusText(
    text: String,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "thinkingTextShimmer")
    val shimmerPosition by transition.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "thinkingTextShimmerPosition"
    )
    val density = LocalDensity.current
    val textWidth = with(density) { (text.length * 7.5f).dp.toPx() }
    val highlightWidth = with(density) { 14.dp.toPx() }
    val shimmerCenter = textWidth * shimmerPosition
    val baseColor = TodoColors.secondaryText
    val highlightColor = lerp(baseColor, TodoColors.linkText, 0.5f)

    Text(
        text = text,
        modifier = modifier.padding(vertical = 2.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        style = TextStyle(
            brush = Brush.linearGradient(
                colors = listOf(baseColor, highlightColor, baseColor),
                start = Offset(shimmerCenter - highlightWidth, 0f),
                end = Offset(shimmerCenter + highlightWidth, 0f)
            )
        )
    )
}

private fun agentMessageTextStyle(message: String) = TextStyle(
    color = if (isFixedResponseText(message)) TodoColors.secondaryText else TodoColors.infoText,
    fontSize = 13.sp,
    lineHeight = 21.sp
)

private sealed interface AgentMessageBlock {
    data class Paragraph(val text: String) : AgentMessageBlock
    data class Heading(val level: Int, val text: String) : AgentMessageBlock
    data class Code(val language: String?, val code: String) : AgentMessageBlock
    data class UnorderedList(val items: List<String>) : AgentMessageBlock
    data class OrderedList(val items: List<Pair<String, String>>) : AgentMessageBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : AgentMessageBlock
    data class Quote(val text: String) : AgentMessageBlock
    data object ThematicBreak : AgentMessageBlock
}

private const val MarkdownLinkTag = "markdown-link"

private data class MarkdownListEntry(
    val marker: String,
    val text: String
)

@Composable
private fun MarkdownTextBlock(
    text: String,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
    style: TextStyle = TextStyle(
        color = TodoColors.infoText,
        fontSize = 13.sp,
        lineHeight = 21.sp
    )
) {
    val annotatedText = remember(text) { buildMarkdownAnnotatedString(text) }
    ClickableText(
        text = annotatedText,
        modifier = modifier,
        style = style,
        onClick = { offset ->
            annotatedText
                .getStringAnnotations(MarkdownLinkTag, offset, offset)
                .firstOrNull()
                ?.let { annotation ->
                    onLinkClick?.invoke(annotation.item) ?: BrowserUtil.browse(annotation.item)
                }
        }
    )
}

@Composable
private fun MarkdownList(
    items: List<MarkdownListEntry>,
    onLinkClick: ((String) -> Unit)? = null
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val markerStyle = TextStyle(fontSize = 13.sp)
    val markerColumnWidth = with(density) {
        maxOf(
            16.dp.toPx(),
            items.maxOfOrNull { entry ->
                textMeasurer.measure(entry.marker, style = markerStyle).size.width.toFloat()
            } ?: 0f
        ).toDp()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { entry ->
            MarkdownListItem(
                marker = entry.marker,
                text = entry.text,
                markerColumnWidth = markerColumnWidth,
                onLinkClick = onLinkClick
            )
        }
    }
}

@Composable
private fun MarkdownListItem(
    marker: String,
    text: String,
    markerColumnWidth: Dp,
    onLinkClick: ((String) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(modifier = Modifier.width(markerColumnWidth)) {
            Text(
                text = marker,
                color = TodoColors.secondaryText,
                fontSize = 13.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth()
            )
        }
        MarkdownTextBlock(
            text = text,
            onLinkClick = onLinkClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MarkdownTable(
    table: AgentMessageBlock.Table,
    onLinkClick: ((String) -> Unit)? = null
) {
    val horizontalScroll = rememberScrollState()
    val columnCount = maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(horizontalScroll)
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, TodoColors.codeBlockBorder, RoundedCornerShape(7.dp))
    ) {
        MarkdownTableRow(
            cells = table.headers,
            columnCount = columnCount,
            header = true,
            onLinkClick = onLinkClick
        )
        table.rows.forEach { row ->
            MarkdownTableRow(
                cells = row,
                columnCount = columnCount,
                onLinkClick = onLinkClick
            )
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    columnCount: Int,
    header: Boolean = false,
    onLinkClick: ((String) -> Unit)? = null
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        repeat(columnCount) { index ->
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .background(
                        if (header) TodoColors.codeBlockChatSurface else Color.Transparent
                    )
                    .border(1.dp, TodoColors.codeBlockBorder)
                    .padding(horizontal = 9.dp, vertical = 7.dp)
            ) {
                MarkdownTextBlock(
                    text = cells.getOrNull(index).orEmpty(),
                    onLinkClick = onLinkClick,
                    style = TextStyle(
                        color = if (header) TodoColors.primaryText else TodoColors.infoText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal
                    )
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(block: AgentMessageBlock.Code) {
    val horizontalScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TodoColors.codeBlockChatSurface)
            .border(1.dp, TodoColors.codeBlockBorder, RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        block.language?.takeIf { it.isNotBlank() }?.let { language ->
            Text(
                text = language,
                color = TodoColors.secondaryText,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(horizontalScroll)
                .padding(horizontal = 12.dp)
        ) {
            SelectionContainer {
                Text(
                    text = block.code,
                    color = TodoColors.infoText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    softWrap = false,
                    modifier = Modifier.wrapContentWidth(unbounded = true)
                )
            }
        }
    }
}

private fun parseAgentMessageBlocks(message: String): List<AgentMessageBlock> {
    val lines = message.lines()
    val blocks = mutableListOf<AgentMessageBlock>()
    var index = 0

    while (index < lines.size) {
        val trimmed = lines[index].trim()
        if (trimmed.isEmpty()) {
            index += 1
            continue
        }

        if (trimmed.startsWith("```")) {
            val language = trimmed.removePrefix("```").trim().ifBlank { null }
            val codeLines = mutableListOf<String>()
            index += 1
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                codeLines += lines[index]
                index += 1
            }
            if (index < lines.size) index += 1
            blocks += AgentMessageBlock.Code(language, codeLines.joinToString("\n"))
            continue
        }

        Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)?.let { match ->
            blocks += AgentMessageBlock.Heading(match.groupValues[1].length, match.groupValues[2])
            index += 1
            continue
        }

        if (trimmed.matches(Regex("^(-{3,}|\\*{3,})$"))) {
            blocks += AgentMessageBlock.ThematicBreak
            index += 1
            continue
        }

        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (index < lines.size && lines[index].trim().startsWith(">")) {
                quoteLines += lines[index].trim().removePrefix(">").trimStart()
                index += 1
            }
            blocks += AgentMessageBlock.Quote(quoteLines.joinToString("\n"))
            continue
        }

        if (trimmed.matches(Regex("^[-*]\\s+.+$"))) {
            val items = mutableListOf<String>()
            while (index < lines.size && lines[index].trim().matches(Regex("^[-*]\\s+.+$"))) {
                items += lines[index].trim().replaceFirst(Regex("^[-*]\\s+"), "")
                index += 1
            }
            blocks += AgentMessageBlock.UnorderedList(items)
            continue
        }

        if (trimmed.matches(Regex("^\\d+\\.\\s+.+$"))) {
            val items = mutableListOf<Pair<String, String>>()
            while (index < lines.size && lines[index].trim().matches(Regex("^\\d+\\.\\s+.+$"))) {
                Regex("^(\\d+)\\.\\s+(.+)$").matchEntire(lines[index].trim())?.let {
                    items += it.groupValues[1] to it.groupValues[2]
                }
                index += 1
            }
            blocks += AgentMessageBlock.OrderedList(items)
            continue
        }

        if (index + 1 < lines.size && isMarkdownTableDelimiter(lines[index + 1])) {
            val headers = splitMarkdownTableRow(lines[index])
            index += 2
            val rows = mutableListOf<List<String>>()
            while (index < lines.size && isMarkdownTableRow(lines[index])) {
                rows += splitMarkdownTableRow(lines[index])
                index += 1
            }
            blocks += AgentMessageBlock.Table(headers, rows)
            continue
        }

        val paragraphLines = mutableListOf<String>()
        while (index < lines.size) {
            val current = lines[index].trim()
            if (
                current.isEmpty() ||
                current.startsWith("```") ||
                current.startsWith(">") ||
                current.matches(Regex("^(#{1,6})\\s+.+$")) ||
                current.matches(Regex("^[-*]\\s+.+$")) ||
                current.matches(Regex("^\\d+\\.\\s+.+$")) ||
                current.matches(Regex("^(-{3,}|\\*{3,})$")) ||
                (index + 1 < lines.size && isMarkdownTableDelimiter(lines[index + 1]))
            ) break
            paragraphLines += lines[index]
            index += 1
        }
        blocks += AgentMessageBlock.Paragraph(paragraphLines.joinToString("\n"))
    }

    return if (blocks.isEmpty()) listOf(AgentMessageBlock.Paragraph(message)) else blocks
}

private fun isMarkdownTableRow(line: String): Boolean =
    line.trim().contains('|') && splitMarkdownTableRow(line).isNotEmpty()

private fun isMarkdownTableDelimiter(line: String): Boolean {
    val cells = splitMarkdownTableRow(line)
    return cells.size >= 1 && cells.all { it.trim().matches(Regex("^:?-{3,}:?$")) }
}

private fun splitMarkdownTableRow(line: String): List<String> {
    val content = line.trim().removePrefix("|").removeSuffix("|")
    if (content.isBlank() || !content.contains('|')) return emptyList()
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    content.forEach { character ->
        when {
            character == '|' && !escaped -> {
                cells += current.toString().trim()
                current.clear()
            }
            else -> current.append(character)
        }
        escaped = character == '\\' && !escaped
    }
    cells += current.toString().trim()
    return cells.map { it.replace("\\|", "|") }
}

private fun buildMarkdownAnnotatedString(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    while (cursor < text.length) {
        when {
            text.startsWith("**", cursor) -> {
                val end = text.indexOf("**", cursor + 2)
                if (end > cursor + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(cursor + 2, end))
                    }
                    cursor = end + 2
                } else {
                    append(text[cursor++])
                }
            }
            text.startsWith("`", cursor) -> {
                val end = text.indexOf('`', cursor + 1)
                if (end > cursor + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = TodoColors.inlineCodeSurface,
                            color = TodoColors.inlineCodeText,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(text.substring(cursor + 1, end))
                    }
                    cursor = end + 1
                } else {
                    append(text[cursor++])
                }
            }
            text.startsWith("*", cursor) -> {
                val end = text.indexOf('*', cursor + 1)
                if (end > cursor + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(cursor + 1, end))
                    }
                    cursor = end + 1
                } else {
                    append(text[cursor++])
                }
            }
            text.startsWith("[", cursor) -> {
                val labelEnd = text.indexOf(']', cursor + 1)
                val urlStart = if (labelEnd != -1) text.indexOf('(', labelEnd) else -1
                val urlEnd = if (urlStart != -1) text.indexOf(')', urlStart) else -1
                if (labelEnd != -1 && urlStart == labelEnd + 1 && urlEnd > urlStart + 1) {
                    pushStringAnnotation(MarkdownLinkTag, text.substring(urlStart + 1, urlEnd))
                    withStyle(SpanStyle(color = TodoColors.linkText, textDecoration = TextDecoration.Underline)) {
                        append(text.substring(cursor + 1, labelEnd))
                    }
                    pop()
                    cursor = urlEnd + 1
                } else {
                    append(text[cursor++])
                }
            }
            else -> append(text[cursor++])
        }
    }
}

private fun headingFontSize(level: Int) = when (level) {
    1 -> 22.sp
    2 -> 20.sp
    3 -> 18.sp
    4 -> 16.sp
    else -> 14.sp
}

@Composable
private fun ChatActivityRow(
    entry: Message,
    showTurnDivider: Boolean,
    topSpacing: Dp
) {
    val activityLabel = when (entry.type) {
        "contextCompactionStarted" -> "Compacting context"
        "contextCompaction" -> "Compacted context"
        else if (isTaskComparisonMessage(entry)) -> "Updated task comparison"
        else if (isUnexpectedMessageType(entry.type)) -> "Ran ${entry.type}"
        else -> {
            val command = entry.command ?: return
            val verb = if (entry.type == "webSearch") "Searched" else "Ran"
            "$verb $command"
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topSpacing),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showTurnDivider) {
            ChatTurnDivider()
        }
        if (entry.type == "contextCompactionStarted") {
            ShimmeringStatusText(text = activityLabel)
        } else {
            Text(
                text = activityLabel,
                fontSize = 11.sp,
                color = TodoColors.secondaryText.copy(alpha = 0.78f)
            )
        }
    }
}

private data class SessionKey(
    val shoakuId: String
)

private fun chooseGoalsFile(
    project: Project?,
    onFileSelected: (String) -> Unit
) {
    if (project == null) {
        return
    }

    val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("md")
    FileChooser.chooseFile(descriptor, project, null) { virtualFile ->
        onFileSelected(virtualFile.path)
    }
}

@Composable
private fun GoalFilterButton(
    selectedFilter: GoalFilter,
    onFilterChange: (GoalFilter) -> Unit,
    counts: Map<GoalFilter, Int>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.wrapContentWidth()) {
        ToolbarIconButton(
            iconKey = AllIconsKeys.General.Filter,
            contentDescription = "Filter goals",
            selected = expanded,
            onClick = {
                expanded = !expanded
            }
        )

        if (expanded) {
            PopupMenu(
                onDismissRequest = {
                    expanded = false
                    true
                },
                horizontalAlignment = Alignment.End
            ) {
                GoalFilter.entries.forEach { filter ->
                    selectableItem(
                        selected = filter == selectedFilter,
                        iconKey = null,
                        onClick = {
                            onFilterChange(filter)
                            expanded = false
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = filter.label)
                            Text(
                                text = (counts[filter] ?: 0).toString(),
                                fontSize = 11.sp,
                                color = TodoColors.secondaryText
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun OpinionActionButton(
    visible: Boolean,
    enabled: Boolean,
    tooltip: String,
    iconKey: IconKey,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(90)),
        exit = fadeOut(animationSpec = tween(70))
    ) {
        Tooltip(tooltip = { Text(tooltip) }) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(TodoColors.taskResponseSurface)
                    .border(1.dp, TodoColors.goalProgressBorder, RoundedCornerShape(6.dp))
                    .padding(2.dp)
            ) {
                ToolbarIconButton(
                    iconKey = iconKey,
                    contentDescription = tooltip,
                    enabled = enabled,
                    size = 24.dp,
                    onClick = onClick
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TaskGuidanceActionBar(
    visible: Boolean,
    enabled: Boolean,
    chatEnabled: Boolean,
    reviewEnabled: Boolean,
    runCommandEnabled: Boolean,
    onOpenChat: () -> Unit,
    onDeepenUnderstanding: () -> Unit,
    onReview: () -> Unit,
    onRunCommand: () -> Unit,
    showChatAction: Boolean = true,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible || menuExpanded,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(90)),
        exit = fadeOut(animationSpec = tween(70))
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(TodoColors.taskResponseSurface)
                .border(1.dp, TodoColors.goalProgressBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showChatAction) {
                Tooltip(
                    tooltip = { Text("Open chat") }
                ) {
                    ToolbarIconButton(
                        iconKey = AllIconsKeys.General.Balloon,
                        contentDescription = "Open chat",
                        enabled = chatEnabled,
                        onClick = onOpenChat
                    )
                }
            }
            Tooltip(
                tooltip = { Text("Check my understanding") }
            ) {
                ToolbarIconButton(
                    iconKey = AllIconsKeys.Actions.IntentionBulb,
                    contentDescription = "Check my understanding",
                    enabled = enabled,
                    onClick = onDeepenUnderstanding
                )
            }
            TaskActionSelector(
                reviewEnabled = reviewEnabled,
                runCommandEnabled = runCommandEnabled,
                onReview = onReview,
                onRunCommand = onRunCommand,
                onExpandedChange = { menuExpanded = it }
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TaskActionSelector(
    reviewEnabled: Boolean,
    runCommandEnabled: Boolean,
    onReview: () -> Unit,
    onRunCommand: () -> Unit,
    onExpandedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val reviewContentColor = if (reviewEnabled) TodoColors.primaryText else TodoColors.secondaryText.copy(alpha = 0.5f)
    val runCommandContentColor = if (runCommandEnabled) TodoColors.primaryText else TodoColors.secondaryText.copy(alpha = 0.5f)

    Box(modifier = modifier.wrapContentWidth()) {
        Tooltip(
            tooltip = { Text("More task actions") }
        ) {
            ToolbarIconButton(
                iconKey = AllIconsKeys.Actions.More,
                contentDescription = "More task actions",
                selected = expanded,
                onClick = {
                    expanded = !expanded
                    onExpandedChange(expanded)
                }
            )
        }

        if (expanded) {
            PopupMenu(
                onDismissRequest = {
                    expanded = false
                    onExpandedChange(false)
                    true
                },
                horizontalAlignment = Alignment.End
            ) {
                selectableItem(
                    selected = false,
                    iconKey = null,
                    onClick = {
                        if (reviewEnabled) onReview()
                        expanded = false
                        onExpandedChange(false)
                    }
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            key = AllIconsKeys.Actions.Preview,
                            contentDescription = null,
                            tint = reviewContentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(text = "Review this task", color = reviewContentColor)
                    }
                }
                selectableItem(
                    selected = false,
                    iconKey = null,
                    onClick = {
                        if (runCommandEnabled) onRunCommand()
                        expanded = false
                        onExpandedChange(false)
                    }
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            key = AllIconsKeys.Actions.Forward,
                            contentDescription = null,
                            tint = runCommandContentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(text = "Delegate implementation", color = runCommandContentColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarIconButton(
    iconKey: IconKey,
    contentDescription: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 28.dp,
    accentColor: Color? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SelectableIconButton(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size)
    ) { state ->
        Icon(
            key = iconKey,
            contentDescription = contentDescription,
            tint = when {
                !state.isEnabled -> TodoColors.secondaryText.copy(alpha = 0.4f)
                accentColor != null && (state.isHovered || state.isPressed || state.isSelected) -> accentColor
                accentColor != null -> accentColor.copy(alpha = 0.78f)
                state.isHovered || state.isPressed || state.isSelected -> TodoColors.linkText
                else -> TodoColors.secondaryText
            }
        )
    }
}

private val Item.sessionKey
    get() = SessionKey(requireNotNull(shoakuId))

@Composable
private fun TodoRow(
    title: String,
    subtitle: String?,
    meta: String?,
    state: TaskItemState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hoverable: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    val cardModifier = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(TodoColors.goalRowSurface(state, enabled && hoverable && isHovered), shape)
        .then(if (hoverable) Modifier.hoverable(interactionSource) else Modifier)
        .then(
            if (onClick != null && enabled) {
                Modifier.clickable(interactionSource = interactionSource, indication = null) { onClick() }
            } else {
                Modifier
            }
        )
        .padding(horizontal = 8.dp, vertical = 7.dp)

    Box {
        Row(
            modifier = cardModifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            TaskItemMarker(state = state)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (subtitle != null) 4.dp else 0.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = if (state == TaskItemState.Current) FontWeight.SemiBold else FontWeight.Normal,
                    color = TodoColors.taskItemTitle(state, enabled)
                )
                subtitle?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = if (enabled) TodoColors.secondaryText else TodoColors.disabledText
                    )
                }
            }
            if (trailing != null) {
                trailing()
            } else {
                meta?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = if (enabled) TodoColors.secondaryText else TodoColors.disabledText
                    )
                }
            }
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
    val horizontalPadding = 8.dp
    val verticalPadding = 10.dp
    val compactVerticalPadding = 8.dp
}

private object TodoColors {
    private val panelBackground = namedColor("Panel.background", 0xFF1F2329, 0xFFF7F8FA)
    private val listBackground = namedColor("List.background", 0xFF262B33, 0xFFFFFFFF)
    private val componentBorder = namedColor("Component.borderColor", 0xFF4B5263, 0xFFC5CDD8)
    private val focusedBorder = namedColor("TabbedPane.focusColor", 0xFF4C9AFF, 0xFF4C9AFF)
    private val hoverBackground = namedColor("List.hoverBackground", 0xFF2D4366, 0xFFEAF2FF)
    private val brandAccentPrimary = namedColor("Actions.Blue", 0xFF2F7CF6, 0xFF2E6EEB)
    private val brandAccentSecondary = namedColor("Link.hoverForeground", 0xFF37B6FF, 0xFF228BE6)
    private val brandAccentBlend = blend(brandAccentPrimary, brandAccentSecondary, 0.4f)
    private val infoBackground = namedColor("Component.infoBackground", 0xFF24324A, 0xFFF3F7FF)
    val primaryText = namedColor("Label.foreground", 0xFFE6EDF3, 0xFF1F2328)
    val secondaryText = namedColor("Label.infoForeground", 0xFF9DA7B3, 0xFF667281)
    val disabledText = namedColor("Label.disabledForeground", 0xFF7A828E, 0xFFA0A8B5)
    val linkText = namedColor("Link.activeForeground", 0xFF6CB6FF, 0xFF0B57D0)
    private val navigatorViolet = namedColor("Shoaku.NavigatorViolet", 0xFFC078FF, 0xFF7547D1)
    val navigatorAccent = blend(navigatorViolet, linkText, 0.78f)
    val explorerAccent = linkText
    val popupSecondaryText = blend(primaryText, secondaryText, 0.55f)
    val completedText = secondaryText.copy(alpha = 0.9f)
    val completedTaskText = completedText
    val completedMarker = secondaryText
    val activeMarker = brandAccentPrimary
    val pendingMarker = secondaryText
    private val mutedPendingText = blend(primaryText, secondaryText, 0.68f)
    val infoSurface = blend(panelBackground, listBackground, 0.9f)
    val infoText = namedColor("Editor.foreground", 0xFFD5DCE5, 0xFF253041)
    val sectionSurface = blend(panelBackground, listBackground, 0.78f)
    val sectionDivider = componentBorder.copy(alpha = 0.48f)
    val goalProgressBorder = componentBorder.copy(alpha = 0.46f)
    val codeBlockChatSurface = namedColor("Editor.background", 0xFF1E1F22, 0xFFF4F7FB)
    val popupSurface = overlay(infoBackground.copy(alpha = 0.18f), blend(panelBackground, listBackground, 0.62f))
    val popupBorder = componentBorder.copy(alpha = 0.9f)
    val agentMessageDivider = componentBorder.copy(alpha = 0.54f)
    val codeBlockSurface = namedColor("Editor.background", 0xFF1E1F22, 0xFFF4F7FB)
    val codeBlockBorder = componentBorder.copy(alpha = 0.45f)
    val userMessageSurface = overlay(componentBorder.copy(alpha = 0.26f), blend(listBackground, panelBackground, 0.3f))
    val userMessageBorder = overlay(componentBorder.copy(alpha = 0.42f), brandAccentPrimary.copy(alpha = 0.08f))
    val userMessageText = namedColor("TextArea.foreground", 0xFFE8EDF5, 0xFF2D3848)
    val composerSurface = overlay(componentBorder.copy(alpha = 0.14f), codeBlockChatSurface)
    val composerBorder = componentBorder.copy(alpha = 0.68f)
    val composerFocusBorder = focusedBorder
    val composerSendSurface = overlay(brandAccentPrimary.copy(alpha = 0.84f), blend(listBackground, panelBackground, 0.12f))
    val scrollHintSurface = overlay(brandAccentBlend.copy(alpha = 0.2f), blend(listBackground, panelBackground, 0.4f))
    val scrollHintBorder = componentBorder.copy(alpha = 0.68f)
    val scrollHintContent = namedColor("Label.foreground", 0xFFEAF5FF, 0xFF245B9A)
    val tokenUsageTrack = overlay(componentBorder.copy(alpha = 0.14f), blend(panelBackground, listBackground, 0.82f))
    val tokenUsageTrackBorder = componentBorder.copy(alpha = 0.62f)
    val navigatorTokenUsage = navigatorAccent
    val explorerTokenUsage = explorerAccent
    val tokenUsageSegmentHoverBorder = primaryText.copy(alpha = 0.86f)
    val statusError = namedColor("Actions.Red", 0xFFE56A6A, 0xFFC75450)
    val tokenUsageButtonSurface = overlay(brandAccentPrimary.copy(alpha = 0.18f), blend(listBackground, panelBackground, 0.46f))
    val tokenUsageButtonHover = overlay(brandAccentBlend.copy(alpha = 0.28f), blend(listBackground, panelBackground, 0.36f))
    val tokenUsageButtonPressed = overlay(brandAccentBlend.copy(alpha = 0.36f), blend(listBackground, panelBackground, 0.28f))
    val tokenUsageButtonBorder = overlay(brandAccentPrimary.copy(alpha = 0.28f), componentBorder.copy(alpha = 0.92f))
    val tokenUsageButtonContent = namedColor("Label.foreground", 0xFFEAF4FF, 0xFF24538A)
    private val activityGlowStart = Color(0xFF38BDF8)
    private val activityGlowEnd = Color(0xFF2F6FED)
    val statusRunningText = namedColor("Label.foreground", 0xFFEAF4FF, 0xFF24538A)
    val activeTaskGroupSurface = overlay(brandAccentPrimary.copy(alpha = 0.05f), sectionSurface)
    val activeTaskGroupHoverBorder = focusedBorder.copy(alpha = 0.72f)
    val currentTaskSurface = overlay(brandAccentPrimary.copy(alpha = 0.09f), activeTaskGroupSurface)
    val planChangedSurface = overlay(brandAccentPrimary.copy(alpha = 0.07f), sectionSurface)
    val taskResponseSurface = codeBlockChatSurface
    val taskResponseLabelText = blend(infoText, secondaryText, 0.72f)
    val inlineCodeSurface = overlay(componentBorder.copy(alpha = 0.18f), blend(listBackground, panelBackground, 0.52f))
    val inlineCodeText = namedColor("Label.foreground", 0xFFF3F4F6, 0xFF20252B)
    val reviewCommentHoverSurface = overlay(hoverBackground.copy(alpha = 0.22f), blend(listBackground, panelBackground, 0.44f))
    val reviewCommentSelectedSurface = overlay(brandAccentPrimary.copy(alpha = 0.18f), blend(listBackground, panelBackground, 0.4f))
    val reviewCommentSelectedBorder = brandAccentPrimary.copy(alpha = 0.72f)
    val reviewCommentDivider = componentBorder.copy(alpha = 0.54f)

    fun taskItemMarkerText(state: TaskItemState): Color = when (state) {
        TaskItemState.Current -> brandAccentPrimary
        TaskItemState.Completed -> secondaryText
        TaskItemState.Pending -> secondaryText
    }

    fun taskItemTitle(state: TaskItemState, enabled: Boolean): Color = when {
        state == TaskItemState.Completed -> completedText
        !enabled -> secondaryText
        else -> primaryText
    }

    fun activityGlow(activityPulse: Float): Color =
        overlay(
            blend(activityGlowStart, activityGlowEnd, 0.42f).copy(alpha = 0.1f + 0.14f * activityPulse.coerceIn(0f, 1f)),
            Color.Transparent
        )

    fun activityGlowStrong(activityPulse: Float): Color =
        blend(activityGlowStart, activityGlowEnd, 0.42f).copy(alpha = 0.12f + 0.12f * activityPulse.coerceIn(0f, 1f))

    fun activityBorder(activityPulse: Float, emphasized: Boolean): Color {
        val base = blend(activityGlowStart, activityGlowEnd, 0.42f)
        val alpha = if (emphasized) 0.66f else 0.48f + 0.22f * activityPulse.coerceIn(0f, 1f)
        return base.copy(alpha = alpha)
    }

    fun statusRunningSurface(activityPulse: Float): Color =
        overlay(
            blend(activityGlowStart, activityGlowEnd, 0.56f).copy(alpha = 0.1f + 0.08f * activityPulse.coerceIn(0f, 1f)),
            popupSurface
        )

    fun statusRunningBorder(activityPulse: Float): Color =
        blend(activityGlowStart, activityGlowEnd, 0.56f).copy(alpha = 0.42f + 0.18f * activityPulse.coerceIn(0f, 1f))

    fun goalRowSurface(state: TaskItemState, hovered: Boolean): Color = when {
        state == TaskItemState.Current && hovered ->
            overlay(hoverBackground.copy(alpha = 0.16f), currentTaskSurface)
        state == TaskItemState.Current -> currentTaskSurface
        hovered -> overlay(hoverBackground.copy(alpha = 0.2f), Color.Transparent)
        else -> Color.Transparent
    }
}

private data class AgentActivityState(
    val label: String,
    val color: Color,
    val isRunning: Boolean,
    val displayStatus: String,
    val tone: AgentStatusTone
)

private fun activityFromStatus(
    status: AgentStatusUi?,
    explorerBudgetExhausted: Boolean = false
): List<AgentActivityState> {
    val navigatorState = status?.navigator.toAgentStatusState()
    val explorerState = if (explorerBudgetExhausted) {
        AgentStatusState("Budget exhausted", AgentStatusTone.Disabled, false)
    } else {
        status?.explorer.toAgentStatusState()
    }
    return listOf(
        AgentActivityState(
            label = "Navigator",
            color = TodoColors.navigatorTokenUsage,
            isRunning = navigatorState.isRunning,
            displayStatus = navigatorState.label,
            tone = navigatorState.tone
        ),
        AgentActivityState(
            label = "Explorer",
            color = TodoColors.explorerTokenUsage,
            isRunning = explorerState.isRunning,
            displayStatus = explorerState.label,
            tone = explorerState.tone
        )
    )
}

private enum class AgentStatusTone {
    Running,
    Error,
    Disabled,
    Hidden
}

private data class AgentStatusState(
    val label: String,
    val tone: AgentStatusTone,
    val isRunning: Boolean
)

private fun TokenUsageUi.isExplorerTokenBudgetExhausted(): Boolean =
    (navigatorTokens - explorerTokens).coerceAtLeast(0) >= maxTokens.coerceAtLeast(1)

@Composable
private fun statusPulse(): Float {
    val transition = rememberInfiniteTransition(label = "statusPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "statusPulseValue"
    )
    return pulse
}

private fun String?.toAgentStatusState(): AgentStatusState {
    val normalized = this?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return when (normalized) {
        "active" ->
            AgentStatusState("Working", AgentStatusTone.Running, true)
        "systemError" ->
            AgentStatusState("Error", AgentStatusTone.Error, false)
        else ->
            AgentStatusState("", AgentStatusTone.Hidden, false)
    }
}

private fun AgentStatusUi.toSummaryStatusState(): AgentStatusState {
    val states = listOf(navigator, explorer).map { it.toAgentStatusState() }
    return when {
        states.any { it.tone == AgentStatusTone.Error } -> AgentStatusState("Error", AgentStatusTone.Error, false)
        states.any { it.tone == AgentStatusTone.Running } -> AgentStatusState("Working", AgentStatusTone.Running, true)
        else -> AgentStatusState("", AgentStatusTone.Hidden, false)
    }
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
