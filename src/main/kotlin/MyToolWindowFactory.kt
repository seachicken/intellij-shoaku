package shoaku

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icon.PathIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.Dimension
import java.beans.PropertyChangeListener
import java.text.NumberFormat
import java.util.*
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
                project,
                onFilePathChange = { newPath ->
                    LspServerManager.getInstance(project)
                        .getServersForProvider(LanguageServerProvider::class.java)
                        .first()
                        .sendNotification { (it as AppLanguageServer).didChangeGoalsFilePath(DidChangeGoalsFilePath(newPath)) }
                }
            )
        }
    }
}

private val ChatSendIconKey = PathIconKey("/icons/send/send.svg", MyToolWindowFactory::class.java)
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
    var detailTodoPaneFraction by remember { mutableStateOf(0.1f) }
    val goals = vm.items.filter { it.shoakuId != null }
    val goalFilter = vm.goalFilter
    val openSessions = openSessionKeys.mapNotNull { key -> goals.firstOrNull { it.sessionKey == key } }
    val selectedSession = selectedSessionKey?.let { key -> goals.firstOrNull { it.sessionKey == key } }

    LaunchedEffect(goals) {
        val currentKeys = goals.map { it.sessionKey }.toSet()
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
                goals = goals,
                filter = goalFilter,
                filePathState = filePathState,
                project = project,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                onFilterChange = { vm.goalFilter = it },
                onOpenSession = { session ->
                    if (session.shoakuId == null) {
                        return@SessionListContent
                    }
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
    goals: List<Item>,
    filter: GoalFilter,
    filePathState: androidx.compose.foundation.text.input.TextFieldState,
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
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(MyMessageBundle.message("toolwindow.MyToolWindow.filePath.label"))
            TextField(
                state = filePathState,
                modifier = Modifier.weight(1f)
            )
            ToolbarIconButton(
                iconKey = AllIconsKeys.General.OpenDisk,
                contentDescription = MyMessageBundle.message("toolwindow.MyToolWindow.filePath.browse.button"),
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
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(filteredGoals) { _, session ->
                val todoItems = session.children.filter { it.checked != null }
                TodoRow(
                    title = session.content,
                    meta = null,
                    subtitle = "${todoItems.size} tasks",
                    completed = session.checked ?: false,
                    compact = true,
                    hoverable = true,
                    onClick = if (session.shoakuId != null) ({ onOpenSession(session) }) else null
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
                    viewModel = viewModelState.value,
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
    val todoItems = session.children.filter { it.checked != null }
    val remainingCount = todoItems.count { it.checked == false }

    SessionSectionCard(
        header = {
            SessionSectionHeader(
                title = "Tasks",
                trailing = {
                    Text(
                        text = "$remainingCount left",
                        fontSize = 11.sp,
                        color = TodoColors.secondaryText
                    )
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) {
        val listState = rememberLazyListState()
        val activeItemIndex = todoItems.indexOfFirst { it.checked == false }

        LaunchedEffect(todoItems) {
            if (todoItems.isEmpty()) {
                return@LaunchedEffect
            }

            val targetIndex = if (activeItemIndex >= 0) activeItemIndex else todoItems.lastIndex
            listState.animateScrollToItem(targetIndex)
        }

        ScrollHintLazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(todoItems) { model ->
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
    viewModel: ShoakuViewModel,
    project: Project?
) {
    var instructionValue by remember { mutableStateOf(TextFieldValue()) }
    val isEnabled = session.shoakuId != null
    val messages = session.messages.orEmpty()
    val effectiveTokenUsage = remember(session.tokenUsage, session.shoakuId, viewModel.tokenBudgetOverrides.toMap()) {
        val base = session.tokenUsage ?: return@remember null
        val overrideMax = session.shoakuId?.let(viewModel.tokenBudgetOverrides::get) ?: return@remember base
        base.copy(maxTokens = overrideMax.coerceAtLeast(1))
    }
    SessionSectionCard(
        header = {
            SessionSectionHeader(
                title = "Chat",
                trailing = {
                    TokenUsageIndicator(
                        tokenUsage = effectiveTokenUsage,
                        onIncreaseBudget = {
                            val shoakuId = session.shoakuId ?: return@TokenUsageIndicator
                            val currentMax = effectiveTokenUsage?.maxTokens ?: session.tokenUsage?.maxTokens ?: return@TokenUsageIndicator
                            val increment = (currentMax / 10f).toInt().coerceAtLeast(1)
                            viewModel.tokenBudgetOverrides[shoakuId] = currentMax + increment
                        }
                    )
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) {
        val listState = rememberLazyListState()
        ScrollHintLazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 4.dp)
        ) {
            itemsIndexed(messages) { index, entry ->
                val previousEntry = messages.getOrNull(index - 1)
                val previousIsSameSpeaker =
                    previousEntry?.type == entry.type && previousEntry.command == null && entry.command == null

                ChatEntryRow(
                    entry = entry,
                    isFirstInGroup = !previousIsSameSpeaker,
                    topSpacing = if (previousIsSameSpeaker) 4.dp else 10.dp
                )
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
            },
            modifier = Modifier
                .fillMaxWidth()
        )
    }
}

@Composable
private fun TokenUsageIndicator(
    tokenUsage: TokenUsageUi?,
    onIncreaseBudget: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val usageFraction = remember(tokenUsage) {
        if (tokenUsage == null) {
            0f
        } else {
            val maxTokens = tokenUsage.maxTokens.coerceAtLeast(1)
            ((tokenUsage.navigatorTokens + tokenUsage.explorerTokens).toFloat() / maxTokens).coerceIn(0f, 1f)
        }
    }
    val indicatorColor = when {
        tokenUsage != null -> TodoColors.tokenUsageIndicator(usageFraction)
        true -> TodoColors.tokenUsageTrackBorder.copy(alpha = 0.7f)
        else -> TodoColors.scrollHintContent
    }
    val chipBackground = when {
        pressed -> TodoColors.tokenUsageTrackBorder.copy(alpha = 0.18f)
        hovered || expanded -> TodoColors.tokenUsageTrackBorder.copy(alpha = 0.1f)
        else -> Color.Transparent
    }
    val chipBorder = when {
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
                        drawArc(
                            color = TodoColors.tokenUsageTrackBorder.copy(alpha = 0.45f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = strokeWidth)
                        )
                        drawArc(
                            color = indicatorColor,
                            startAngle = -90f,
                            sweepAngle = 360f * usageFraction,
                            useCenter = false,
                            style = Stroke(width = strokeWidth)
                        )
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
                offset = androidx.compose.ui.unit.IntOffset(0, 32),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)
            ) {
                Column(
                    modifier = Modifier
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
                        onIncreaseBudget = onIncreaseBudget,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun TokenUsageCard(
    tokenUsage: TokenUsageUi,
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TokenLegendItem(
                    color = TodoColors.navigatorTokenUsage,
                    label = "Navigator"
                )
                TokenLegendItem(
                    color = TodoColors.explorerTokenUsage,
                    label = "Explorer"
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(color = TodoColors.popupSecondaryText)) {
                            append(formatTokenCount(totalTokens))
                        }
                        withStyle(androidx.compose.ui.text.SpanStyle(color = TodoColors.popupSecondaryText)) {
                            append(" / ")
                        }
                        withStyle(
                            androidx.compose.ui.text.SpanStyle(
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
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(TodoColors.tokenUsageTrack)
                .border(1.dp, TodoColors.tokenUsageTrackBorder, RoundedCornerShape(999.dp))
        )
        {
            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                if (clampedNavigatorFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(clampedNavigatorFraction)
                            .background(TodoColors.navigatorTokenUsage)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                Spacer(modifier = Modifier.fillMaxWidth(clampedNavigatorFraction))
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
    }
}

@Composable
private fun TokenLegendItem(
    color: Color,
    label: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color)
        )
        Text(
            text = label,
            color = TodoColors.popupSecondaryText,
            fontSize = 11.sp
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
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSend = enabled && value.text.isNotBlank()
    var isFocused by remember { mutableStateOf(false) }
    val composerShape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
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
                    .padding(end = 42.dp)
                    .defaultMinSize(minHeight = 52.dp)
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
                color = TodoColors.secondaryText
            )
            Text(
                text = EmptyGoalsSample,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = TodoColors.secondaryText
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
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(TodoColors.scrollHintSurface)
                    .border(1.dp, TodoColors.scrollHintBorder, RoundedCornerShape(999.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        val lastIndex = state.layoutInfo.totalItemsCount - 1
                        if (lastIndex >= 0) {
                            scope.launch {
                                state.animateScrollToItem(lastIndex)
                            }
                        }
                    }
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

@Composable
private fun AgentMessageContent(message: String) {
    val blocks = remember(message) { parseAgentMessageBlocks(message) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is AgentMessageBlock.Paragraph -> MarkdownTextBlock(text = block.text)
                is AgentMessageBlock.Heading -> MarkdownTextBlock(
                    text = block.text,
                    style = TextStyle(
                        color = TodoColors.primaryText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = headingFontSize(block.level),
                        lineHeight = (headingFontSize(block.level).value + 6).sp
                    )
                )
                is AgentMessageBlock.UnorderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { MarkdownListItem(marker = "•", text = it) }
                }
                is AgentMessageBlock.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { (number, item) -> MarkdownListItem(marker = "$number.", text = item) }
                }
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
                            lineHeight = 20.sp
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

private sealed interface AgentMessageBlock {
    data class Paragraph(val text: String) : AgentMessageBlock
    data class Heading(val level: Int, val text: String) : AgentMessageBlock
    data class Code(val language: String?, val code: String) : AgentMessageBlock
    data class UnorderedList(val items: List<String>) : AgentMessageBlock
    data class OrderedList(val items: List<Pair<String, String>>) : AgentMessageBlock
    data class Quote(val text: String) : AgentMessageBlock
    data object ThematicBreak : AgentMessageBlock
}

private const val MarkdownLinkTag = "markdown-link"

@Composable
private fun MarkdownTextBlock(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle(
        color = TodoColors.infoText,
        fontSize = 13.sp,
        lineHeight = 20.sp
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
                ?.let { BrowserUtil.browse(it.item) }
        }
    )
}

@Composable
private fun MarkdownListItem(
    marker: String,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = marker,
            color = TodoColors.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.widthIn(min = 18.dp)
        )
        MarkdownTextBlock(
            text = text,
            modifier = Modifier.weight(1f)
        )
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
                current.matches(Regex("^(-{3,}|\\*{3,})$"))
            ) break
            paragraphLines += lines[index]
            index += 1
        }
        blocks += AgentMessageBlock.Paragraph(paragraphLines.joinToString("\n"))
    }

    return if (blocks.isEmpty()) listOf(AgentMessageBlock.Paragraph(message)) else blocks
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
                            background = TodoColors.sectionSurface,
                            color = TodoColors.infoText
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
private fun ToolbarIconButton(
    iconKey: org.jetbrains.jewel.ui.icon.IconKey,
    contentDescription: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SelectableIconButton(
        selected = selected,
        onClick = onClick,
        modifier = modifier.size(28.dp)
    ) {
        Icon(
            key = iconKey,
            contentDescription = contentDescription,
            tint = if (selected) TodoColors.primaryText else TodoColors.secondaryText
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
    private val focusedBorder = namedColor("TabbedPane.focusColor", 0xFF4C9AFF, 0xFF4C9AFF)
    private val hoverBackground = namedColor("List.hoverBackground", 0xFF2D4366, 0xFFEAF2FF)
    private val userMessageBlue = namedColor("Actions.Blue", 0xFF1849C6, 0xFF3574F0)
    private val infoBackground = namedColor("Component.infoBackground", 0xFF24324A, 0xFFF3F7FF)
    val primaryText = namedColor("Label.foreground", 0xFFE6EDF3, 0xFF1F2328)
    val secondaryText = namedColor("Label.infoForeground", 0xFF9DA7B3, 0xFF667281)
    val linkText = namedColor("Link.activeForeground", 0xFF6CB6FF, 0xFF0B57D0)
    val popupSecondaryText = blend(primaryText, secondaryText, 0.55f)
    val completedText = secondaryText.copy(alpha = 0.9f)
    val infoSurface = blend(panelBackground, listBackground, 0.9f)
    val infoText = namedColor("Editor.foreground", 0xFFD5DCE5, 0xFF253041)
    val sectionSurface = overlay(infoBackground.copy(alpha = 0.08f), blend(panelBackground, listBackground, 0.78f))
    val codeBlockChatSurface = overlay(Color.Black.copy(alpha = 0.18f), blend(panelBackground, listBackground, 0.92f))
    val popupSurface = overlay(infoBackground.copy(alpha = 0.18f), blend(panelBackground, listBackground, 0.62f))
    val popupBorder = componentBorder.copy(alpha = 0.9f)
    val agentMessageDivider = componentBorder.copy(alpha = 0.54f)
    val codeBlockSurface = namedColor("Editor.background", 0xFF1E1F22, 0xFF1E1F22)
    val codeBlockBorder = componentBorder.copy(alpha = 0.45f)
    val userMessageSurface = overlay(userMessageBlue.copy(alpha = 0.78f), blend(listBackground, panelBackground, 0.16f))
    val userMessageText = namedColor("TextArea.foreground", 0xFFF4F8FF, 0xFF17375E)
    val composerSurface = overlay(infoBackground.copy(alpha = 0.12f), blend(listBackground, panelBackground, 0.28f))
    val composerBorder = componentBorder.copy(alpha = 0.82f)
    val composerFocusBorder = focusedBorder
    val composerSendSurface = overlay(userMessageBlue.copy(alpha = 0.88f), blend(listBackground, panelBackground, 0.12f))
    val scrollHintSurface = overlay(userMessageBlue.copy(alpha = 0.18f), blend(listBackground, panelBackground, 0.4f))
    val scrollHintBorder = componentBorder.copy(alpha = 0.68f)
    val scrollHintContent = namedColor("Label.foreground", 0xFFEAF2FF, 0xFF1D4A85)
    val tokenUsageTrack = overlay(componentBorder.copy(alpha = 0.14f), blend(panelBackground, listBackground, 0.82f))
    val tokenUsageTrackBorder = componentBorder.copy(alpha = 0.62f)
    val navigatorTokenUsage = namedColor("Actions.Blue", 0xFF3574F0, 0xFF3574F0)
    val explorerTokenUsage = namedColor("Actions.Green", 0xFF4FAF6B, 0xFF3D9B58)
    private val tokenUsageNeutral = namedColor("Label.disabledForeground", 0xFFC9D1D9, 0xFFB8C1CC)
    private val tokenUsageWarning = namedColor("Actions.Yellow", 0xFFE2A93B, 0xFFB26A00)
    private val tokenUsageDanger = namedColor("Actions.Red", 0xFFE05555, 0xFFC75450)
    val tokenUsageButtonSurface = overlay(userMessageBlue.copy(alpha = 0.2f), blend(listBackground, panelBackground, 0.46f))
    val tokenUsageButtonHover = overlay(userMessageBlue.copy(alpha = 0.3f), blend(listBackground, panelBackground, 0.36f))
    val tokenUsageButtonPressed = overlay(userMessageBlue.copy(alpha = 0.42f), blend(listBackground, panelBackground, 0.28f))
    val tokenUsageButtonBorder = overlay(userMessageBlue.copy(alpha = 0.34f), componentBorder.copy(alpha = 0.92f))
    val tokenUsageButtonContent = namedColor("Label.foreground", 0xFFF2F7FF, 0xFF17375E)

    fun tokenUsageIndicator(usageFraction: Float): Color {
        val clampedFraction = usageFraction.coerceIn(0f, 1f)
        return when {
            clampedFraction <= 0.7f -> lerp(tokenUsageNeutral, tokenUsageWarning, clampedFraction / 0.7f)
            else -> lerp(tokenUsageWarning, tokenUsageDanger, (clampedFraction - 0.7f) / 0.3f)
        }
    }

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
                Item("text", "aaa-1", checked = true),
                Item("text", "aaa-2", checked = false),
            ),
            messages = listOf(
                Message(type = "commandExecution", command = "file read"),
                Message(type = "agentMessage", text = "I reviewed the current todo order and found the next actionable item."),
                Message(type = "userMessage", text = "Split the detail view into todo and chat sections."),
                Message(type = "commandExecution", command = "execute cmd"),
                Message(type = "agentMessage", text = "Use separate scrollable panes so the todo list stays visible while the conversation grows.")
            ),
            tokenUsage = TokenUsageUi(
                maxTokens = 32_000,
                navigatorTokens = 7_800,
                explorerTokens = 5_400
            ),
            shoakuId = "shoaku-preview-1"
        ),
        Item(
            "text",
            "bbb",
            checked = null,
            children = listOf(
                Item("text", "bbb-1", checked = false),
                Item("text", "bbb-2", checked = null)
            ),
            shoakuId = "shoaku-preview-2"
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
