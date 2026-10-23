package shoaku.presentation

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.impl.CacheDiffRequestChainProcessor
import com.intellij.diff.impl.DiffRequestProcessorListener
import com.intellij.diff.impl.DiffSettingsHolder.DiffSettings
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.UserDataHolder
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.treeStructure.Tree
import com.intellij.ui.TreeSpeedSearch
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeListener
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.BoxLayout
import javax.swing.JTabbedPane
import javax.swing.UIManager
import javax.swing.Scrollable
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.Timer

internal class CommitReviewVirtualFile(val workspace: Path, val isPrReview: Boolean = false) : LightVirtualFile(
    "${if (isPrReview) "PR review" else "Outcome"} · ${workspace.fileName}", PlainTextFileType.INSTANCE, ""
)

internal fun openCommitReview(project: Project, workspace: String, isPrReview: Boolean = false) {
    val path = Path.of(workspace).toAbsolutePath().normalize()
    val manager = FileEditorManager.getInstance(project)
    val existing = manager.openFiles.filterIsInstance<CommitReviewVirtualFile>().firstOrNull { it.workspace == path }
    manager.openFile(existing ?: CommitReviewVirtualFile(path, isPrReview), true)
}

class CommitReviewEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is CommitReviewVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        CommitReviewEditor(project, file as CommitReviewVirtualFile)
    override fun getEditorTypeId() = "shoaku-commit-review"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class CommitReviewEditor(private val project: Project, private val reviewFile: CommitReviewVirtualFile) : UserDataHolderBase(), FileEditor {
    private val root = JBPanel<JBPanel<*>>(BorderLayout())
    private val changesTree = Tree()
    private var selectedCommit: ReviewCommit? = null
    private var selectedFile: ReviewCommitFile? = null
    private data class LoadedCommit(val details: ReviewCommitDetails, val guideResult: ReviewGuideResult)
    private var loadedCommits = emptyList<LoadedCommit>()
    private val fileNodes = mutableMapOf<FileLocation, DefaultMutableTreeNode>()
    private val status = JBLabel("Loading commits…")
    private val overview = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(JBLabel("Loading outcome…"), BorderLayout.NORTH)
    }
    private val guidePanel = JBPanel<JBPanel<*>>(BorderLayout())
    private val stepPicker = JComboBox<GuideStep>()
    private val layoutPicker = JComboBox(arrayOf("Auto layout", "Side by side", "Below content"))
    private val split = OnePixelSplitter(true, 0.65f)
    private var stacked = true
    private var guide: ReviewGuide? = null
    private var guideIssue: String? = null
    private var choosingStep = false
    private val diff = NativeReviewDiffPanel(project, ::onDiffFileSelected)
    private val reader = GitCommitReviewReader(reviewFile.workspace)
    @Volatile private var disposed = false
    private var loading = false
    private var checkingUpdates = false
    private var lastSnapshot: OutcomeSnapshot? = null
    private val updateTimer = Timer(2000) { checkForUpdates() }
    private var changingPickers = false
    private var generation = 0
    private var selectedDetails: ReviewCommitDetails? = null
    private val documentEditors = mutableMapOf<JComponent, kotlin.Pair<FileEditorProvider, FileEditor>>()

    init {
        changesTree.name = "shoaku.commitReview.changes"
        changesTree.isRootVisible = false
        changesTree.showsRootHandles = true
        changesTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        TreeSpeedSearch.installOn(changesTree)
        changesTree.addTreeSelectionListener {
            if (!changingPickers) {
                val value = (changesTree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject
                when (value) {
                    is FileLocation -> {
                        selectedCommit = loadedCommits.firstOrNull { it.details.commit.hash == value.hash }?.details?.commit
                        loadCommit(value.file.path)
                    }
                    is ReviewCommit -> {
                        selectedCommit = value
                        loadCommit()
                    }
                }
            }
        }
        stepPicker.name = "shoaku.commitReview.steps"
        layoutPicker.name = "shoaku.commitReview.layout"
        layoutPicker.toolTipText = "Auto layout places the explanation below content when the editor is narrow. Drag the divider to resize."
        stepPicker.alignmentX = 0f
        guidePanel.name = "shoaku.commitReview.guide"
        val controls = JBPanel<JBPanel<*>>(BorderLayout(8, 4))
        controls.add(status, BorderLayout.CENTER)
        controls.add(layoutPicker, BorderLayout.EAST)
        val changes = JBPanel<JBPanel<*>>(BorderLayout())
        changes.add(controls, BorderLayout.NORTH)
        split.firstComponent = diff.component
        split.secondComponent = guidePanel.apply {
            minimumSize = Dimension(0, 0)
            border = BorderFactory.createTitledBorder("Explanation")
        }
        changes.add(OnePixelSplitter(false, 0.23f).apply {
            firstComponent = JBScrollPane(changesTree)
            secondComponent = split
        }, BorderLayout.CENTER)
        root.add(JTabbedPane().apply {
            name = "shoaku.outcome.sections"
            addTab("Overview", overview)
            addTab("Changes", changes)
        }, BorderLayout.CENTER)
        layoutPicker.addActionListener { updateLayout() }
        root.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = updateLayout()
        })
        stepPicker.addActionListener { if (!choosingStep) (stepPicker.selectedItem as? GuideStep)?.let { selectStep(it, true) } }
        loadCommits()
    }

    private data class OutcomeSnapshot(val commits: List<ReviewCommit>, val hash: String?,
                                       val overview: String, val design: String, val map: String)

    private fun readSnapshot(hash: String?, commits: List<ReviewCommit> = reader.commits()): OutcomeSnapshot {
        val root = reviewFile.workspace.resolve(".shoaku")
        fun stamp(path: Path): String = if (Files.isRegularFile(path)) {
            "${Files.getLastModifiedTime(path)}:${Files.size(path)}"
        } else "missing"
        return OutcomeSnapshot(commits, hash, stamp(root.resolve("RESULTS.md")),
            commits.joinToString { stamp(root.resolve("commits/${it.hash}.md")) },
            commits.joinToString { stamp(root.resolve("commits/${it.hash}.review.json")) })
    }

    private fun checkForUpdates() {
        if (disposed || project.isDisposed || !updateTimer.isRunning || loading || checkingUpdates) return
        checkingUpdates = true
        val hash = selectedCommit?.hash
        ApplicationManager.getApplication().executeOnPooledThread {
            val snapshot = runCatching { readSnapshot(hash) }
            ApplicationManager.getApplication().invokeLater {
                checkingUpdates = false
                if (disposed || project.isDisposed || !updateTimer.isRunning || loading || hash != selectedCommit?.hash) return@invokeLater
                val next = snapshot.getOrNull() ?: return@invokeLater
                val previous = lastSnapshot
                lastSnapshot = next
                val displayed = loadedCommits.map { it.details.commit }
                if (next.commits != displayed) {
                    loadCommits(next.commits)
                } else {
                    if (previous == null || next.overview != previous.overview) {
                        showDocument(reviewFile.workspace.resolve(".shoaku/RESULTS.md"), overview,
                            "The outcome summary will appear as work progresses.")
                    }
                    if (hash != null && (previous == null ||
                            (next.design != previous.design || next.map != previous.map))) {
                        loadCommits(next.commits)
                    }
                }
            }
        }
    }

    private fun updateLayout() {
        val below = when (layoutPicker.selectedIndex) {
            1 -> false
            2 -> true
            else -> root.width < JBUI.scale(1200)
        }
        if (below == stacked) return
        stacked = below
        split.setOrientation(below)
        split.proportion = 0.65f
        root.revalidate()
        root.repaint()
    }

    private fun <T> load(work: () -> T, apply: (T) -> Unit) {
        loading = true
        val ticket = ++generation
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(work)
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed && generation == ticket) {
                    loading = false
                    result.fold(apply) { error ->
                        status.text = "Could not load review: ${error.message}"
                        diff.setRequest(MessageDiffRequest("Review unavailable", error.message ?: "Unknown error"))
                    }
                }
            }
        }
    }

    private fun loadCommits(updatedCommits: List<ReviewCommit>? = null) {
        status.text = "Loading commits…"
        val previous = selectedCommit?.hash
        load({
            val commits = updatedCommits ?: reader.commits()
            val loaded = commits.map { commit ->
                val details = reader.details(commit)
                LoadedCommit(details, readReviewGuide(reviewFile.workspace, details, reader))
            }
            readSnapshot(previous ?: commits.firstOrNull()?.hash, commits) to loaded
        }) { (snapshot, loaded) ->
            val selectedHash = selectedCommit?.hash
            val previousFile = selectedFile?.path
            val previousStep = (stepPicker.selectedItem as? GuideStep)?.id
            lastSnapshot = snapshot
            loadedCommits = loaded
            showDocument(reviewFile.workspace.resolve(".shoaku/RESULTS.md"), overview,
                "The outcome summary will appear as work progresses.")
            val treeRoot = DefaultMutableTreeNode("Changes")
            fileNodes.clear()
            loaded.forEach { entry ->
                val commitNode = DefaultMutableTreeNode(entry.details.commit)
                treeRoot.add(commitNode)
                entry.details.files.forEach { file ->
                    val location = FileLocation(entry.details.commit.hash, file)
                    val node = DefaultMutableTreeNode(location, false)
                    commitNode.add(node)
                    fileNodes[location] = node
                }
            }
            changingPickers = true
            changesTree.model = DefaultTreeModel(treeRoot)
            for (row in 0 until loaded.size) {
                changesTree.expandPath(TreePath((treeRoot.getChildAt(row) as DefaultMutableTreeNode).path))
            }
            changingPickers = false
            selectedCommit = loaded.firstOrNull { it.details.commit.hash == selectedHash }?.details?.commit
                ?: loaded.firstOrNull()?.details?.commit
            if (loaded.isEmpty()) {
                selectedDetails = null
                selectedFile = null
                guide = null
                val emptyMessage = if (Files.isRegularFile(reviewFile.workspace.resolve(".shoaku/repositories.json")))
                    "See Overview for the outcome and a link to repository details."
                else "No changes ready to review yet. Updates appear automatically."
                guideIssue = emptyMessage
                renderGuide()
                status.text = emptyMessage
                diff.setRequest(MessageDiffRequest(emptyMessage))
            } else loadCommit(previousFile, previousStep)
        }
    }

    private fun loadCommit(preferredFile: String? = null, preferredStep: String? = null) {
        val entry = loadedCommits.firstOrNull { it.details.commit == selectedCommit } ?: return
        selectCommitContext(entry, preferredStep)
        selectedFile = entry.details.files.firstOrNull { it.path == preferredFile } ?: entry.details.files.firstOrNull()
        if (selectedFile == null) {
            status.text = "This commit has no file changes."
            diff.setRequest(MessageDiffRequest(status.text))
        } else loadFile()
    }

    // Updating the explanation must not reinstall the chain when native diff navigation crosses a commit.
    private fun selectCommitContext(entry: LoadedCommit, preferredStep: String? = null) {
        selectedCommit = entry.details.commit
        selectedDetails = entry.details
        guide = entry.guideResult.guide
        guideIssue = entry.guideResult.issue
        choosingStep = true
        stepPicker.model = DefaultComboBoxModel(guide?.steps?.toTypedArray() ?: emptyArray())
        guide?.steps?.firstOrNull { it.id == preferredStep }?.let { stepPicker.selectedItem = it }
        choosingStep = false
        status.toolTipText = entry.details.commit.hash
        renderGuide()
    }

    private fun syncTreeSelection(location: FileLocation) {
        val node = fileNodes[location] ?: return
        changingPickers = true
        val path = TreePath(node.path)
        changesTree.selectionPath = path
        changesTree.scrollPathToVisible(path)
        changingPickers = false
    }

    private fun loadFile(anchor: GuideAnchor? = null) {
        val details = selectedDetails ?: return
        val file = selectedFile ?: return
        if (anchor == null) {
            val related = guide?.steps?.filter { step -> step.anchors.any { it.path == file.path } }.orEmpty()
            val current = stepPicker.selectedItem as? GuideStep
            if (related.isNotEmpty()) selectStep(related.firstOrNull { it.id == current?.id } ?: related.first(), false)
            else renderGuide(file.path)
        }
        val step = stepPicker.selectedItem as? GuideStep
        if (step != null && guide != null && step.anchors.any { it.path == file.path }) showStepStatus(step, anchor ?: step.anchors.first { it.path == file.path })
        else status.text = "${details.files.size} files · parent ${details.parent?.take(12) ?: "empty tree"}"
        val locations = loadedCommits.flatMap { entry -> entry.details.files.map { FileLocation(entry.details.commit.hash, it) } }
        val anchorLocation = FileLocation(details.commit.hash, file)
        val producers = loadedCommits.flatMap { entry ->
            entry.details.files.map { changedFile ->
                val details = entry.details
                val map = entry.guideResult.guide
                val location = FileLocation(details.commit.hash, changedFile)
                object : DiffRequestProducer {
                    override fun getName() = "${details.commit.hash.take(12)} · ${changedFile.path}"
                    override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
                        indicator.checkCanceled()
                        val content = try { reader.content(details, changedFile) }
                        catch (error: Exception) { throw DiffRequestProducerException(error) }
                        val request = if (content.unavailableReason != null) {
                            MessageDiffRequest(changedFile.path, content.unavailableReason)
                        } else {
                            val factory = DiffContentFactory.getInstance()
                            val type = FileTypeManager.getInstance().getFileTypeByFileName(changedFile.path.substringAfterLast('/'))
                            SimpleDiffRequest(changedFile.path,
                                factory.create(project, content.before, type), factory.create(project, content.after, type),
                                details.parent?.take(12) ?: "Before", details.commit.hash.take(12))
                        }
                        request.putUserData(ReviewDiffLocationKey, location)
                        if (anchor != null && location == anchorLocation) {
                            request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE,
                                Pair.create(if (anchor.side == "before") Side.LEFT else Side.RIGHT, anchor.startLine - 1))
                        }
                        map?.let {
                            request.putUserData(CodeTraceKey, CodeTraceData(it.steps, changedFile.path,
                                { if (selectedCommit?.hash == location.hash) (stepPicker.selectedItem as? GuideStep)?.id else null },
                                { selected -> if (!disposed && selectedDetails?.commit?.hash == details.commit.hash &&
                                    selectedFile?.path == changedFile.path &&
                                    (stepPicker.selectedItem as? GuideStep)?.id != selected.id) selectStep(selected, false) }))
                        }
                        return request
                    }
                }
            }
        }
        val location = FileLocation(details.commit.hash, file)
        syncTreeSelection(location)
        diff.setFiles(producers, locations.indexOf(location))
    }

    private fun onDiffFileSelected(location: FileLocation) {
        if (disposed) return
        val entry = loadedCommits.firstOrNull { it.details.commit.hash == location.hash } ?: return
        if (selectedDetails?.commit != entry.details.commit) selectCommitContext(entry)
        val path = location.file.path
        selectedFile = location.file
        syncTreeSelection(location)
        status.text = "${entry.details.commit.hash.take(12)} · ${entry.details.commit.subject} · $path"
        val related = guide?.steps?.filter { step -> step.anchors.any { it.path == path } }.orEmpty()
        val current = stepPicker.selectedItem as? GuideStep
        if (related.isNotEmpty()) selectStep(related.firstOrNull { it.id == current?.id } ?: related.first(), false)
        else renderGuide(path)
    }

    private fun selectStep(step: GuideStep, navigate: Boolean) {
        choosingStep = true
        stepPicker.selectedItem = step
        choosingStep = false
        renderGuide()
        val location = step.anchors.firstOrNull { it.path == selectedFile?.path } ?: step.anchors.first()
        showStepStatus(step, location)
        if (navigate) step.anchors.firstOrNull()?.let(::navigateToAnchor)
    }

    private fun showStepStatus(step: GuideStep, anchor: GuideAnchor) {
        status.text = "${guide?.steps?.indexOf(step)?.plus(1)}. ${step.title} → ${anchor.path}:${anchor.startLine}–${anchor.endLine} (${anchor.side})"
    }

    private fun navigateToAnchor(anchor: GuideAnchor) {
        val details = selectedDetails ?: return
        val file = details.files.firstOrNull { it.path == anchor.path } ?: return
        selectedFile = file
        loadFile(anchor)
    }

    private fun renderGuide(unmappedFile: String? = null) {
        if (guide == null && selectedDetails != null) {
            val commit = selectedDetails!!.commit
            showDocument(reviewFile.workspace.resolve(".shoaku/commits/${commit.hash}.md"), guidePanel,
                guideIssue ?: "Explanation pending for ${commit.hash.take(12)}.")
            return
        }
        val previousPosition = (guidePanel.components.firstOrNull() as? JBScrollPane)?.verticalScrollBar?.value ?: 0
        clearDocument(guidePanel)
        val body = GuideBodyPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        fun paragraph(title: String, value: String) {
            body.add(JBLabel(title).apply { alignmentX = 0f; font = font.deriveFont(Font.BOLD) })
            body.add(JBTextArea(value).apply {
                isEditable = false; lineWrap = true; wrapStyleWord = true
                font = UIManager.getFont("Label.font").deriveFont(Font.PLAIN)
                background = body.background
                alignmentX = 0f
            })
            body.add(Box.createVerticalStrut(8))
        }
        val map = guide
        if (map == null) {
            paragraph("Correspondence not available", guideIssue ?: "No intent-to-content map is available.")
        } else {
            paragraph("Problem", map.problem)
            paragraph("Before → After", "${map.before}\n→ ${map.after}")
            val covered = map.steps.flatMap { it.anchors }.map { it.path }.distinct().size
            paragraph("Content correspondence", "$covered of ${selectedDetails?.files?.size ?: 0} changed files mapped. ${map.excluded.size} explicitly excluded.")
            if (map.steps.size > 1 && map.steps.any { step -> step.links.any { it.to != step.id } }) {
                body.add(JBLabel("Review flow — select a passage or responsibility").apply { alignmentX = 0f })
                body.add(ReviewFlowDiagram(map.steps) { selectStep(it, true) }.apply {
                    selectedId = (stepPicker.selectedItem as? GuideStep)?.id
                    alignmentX = 0f
                })
            }
            body.add(stepPicker)
            if (unmappedFile != null) {
                paragraph("No mapped design step for $unmappedFile", map.excluded.firstOrNull { it.path == unmappedFile }?.reason ?: "This file has no mapped explanation.")
            } else {
                val step = stepPicker.selectedItem as? GuideStep
                if (step != null) {
                    step.intent?.let { paragraph("Intent — what this communicates or achieves", it) }
                    paragraph("Why this choice?", step.why)
                    paragraph("What changed", step.change)
                    body.add(JBLabel("Content — select a location to highlight it").apply { alignmentX = 0f })
                    step.anchors.forEach { anchor ->
                        paragraph("${anchor.side} · ${anchor.startLine}–${anchor.endLine}", anchor.path)
                        body.add(JButton(anchor.label).apply {
                            toolTipText = "${anchor.path}:${anchor.startLine}–${anchor.endLine} (${anchor.side})"
                            addActionListener { navigateToAnchor(anchor) }
                            alignmentX = 0f
                        })
                    }
                    step.links.forEach { link ->
                        val target = map.steps.first { it.id == link.to }
                        body.add(JButton("→ ${target.title}: ${link.label}").apply {
                            addActionListener { selectStep(target, true) }
                            toolTipText = text
                            alignmentX = 0f
                        })
                    }
                    paragraph(if (reviewFile.isPrReview) "Verification — check evidence and provenance" else "Verification — reported by the author", step.verification)
                }
            }
            body.add(JBLabel("Selecting mapped text or code updates this explanation.").apply { alignmentX = 0f })
        }
        val scroll = JBScrollPane(body)
        guidePanel.add(scroll, BorderLayout.CENTER)
        guidePanel.revalidate()
        guidePanel.repaint()
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && scroll.parent == guidePanel) scroll.verticalScrollBar.value = previousPosition
        }
    }

    private fun clearDocument(panel: JComponent = guidePanel) {
        documentEditors.remove(panel)?.let { (provider, editor) ->
            editor.deselectNotify()
            provider.disposeEditor(editor)
        }
        panel.removeAll()
        panel.revalidate()
        panel.repaint()
    }

    private fun showDocument(path: Path, panel: JComponent, pending: String) {
        clearDocument(panel)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        file?.refresh(false, false)
        if (file == null) {
            panel.add(JBLabel(pending), BorderLayout.NORTH)
        } else {
            val provider = ApplicationManager.getApplication().runReadAction<FileEditorProvider?> {
                FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.firstOrNull {
                    it.javaClass.simpleName == "MarkdownSplitEditorProvider" && it.accept(project, file)
                }
            }
            val editor = provider?.let { selectedProvider ->
                runCatching {
                    ApplicationManager.getApplication().runReadAction<FileEditor> { selectedProvider.createEditor(project, file) }
                }.getOrNull()
            }
            if (editor is TextEditorWithPreview) {
                editor.setLayout(TextEditorWithPreview.Layout.SHOW_PREVIEW)
                documentEditors[panel] = kotlin.Pair(provider, editor)
                panel.add(editor.component, BorderLayout.CENTER)
                editor.selectNotify()
            } else {
                if (editor != null) provider.disposeEditor(editor)
                val text = JBTextArea(runCatching { Files.readString(path) }.getOrDefault("Explanation could not be read.")).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    caretPosition = 0
                }
                panel.add(JBLabel("Enable the Markdown plugin for formatted explanations and diagrams."), BorderLayout.NORTH)
                panel.add(JBScrollPane(text), BorderLayout.CENTER)
            }
        }
        panel.revalidate()
        panel.repaint()
    }

    override fun selectNotify() {
        if (!disposed) {
            updateTimer.start()
            checkForUpdates()
        }
    }

    override fun deselectNotify() {
        updateTimer.stop()
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = changesTree
    override fun getName() = if (reviewFile.isPrReview) "PR review" else "Outcome"
    override fun getFile(): VirtualFile = reviewFile
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = !disposed
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {
        disposed = true
        updateTimer.stop()
        ++generation
        diff.dispose()
        clearDocument()
        clearDocument(overview)
    }
}

private class GuideBodyPanel : JBPanel<GuideBodyPanel>(), Scrollable {
    override fun getPreferredScrollableViewportSize() = Dimension(360, 600)
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 16
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = (visibleRect.height - 24).coerceAtLeast(16)
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}

private data class FileLocation(val hash: String, val file: ReviewCommitFile) {
    override fun toString() = file.toString()
}

private val ReviewDiffLocationKey = Key.create<FileLocation>("shoaku.outcome.diff.location")

/** Uses the same request-chain processor as the IDE's multi-file diff editor. */
private class NativeReviewDiffPanel(private val project: Project, private val onFileSelected: (FileLocation) -> Unit) {
    val component = JBPanel<JBPanel<*>>(BorderLayout())
    private var processor: CacheDiffRequestChainProcessor? = null
    // Keep cross-file difference navigation local to Outcome, without changing IDE preferences.
    private val navigationSettings = DiffSettings().apply {
        isGoToNextFileOnNextDifference = true
    }

    fun setRequest(request: DiffRequest) = install(SimpleDiffRequestChain(request))

    fun setFiles(producers: List<DiffRequestProducer>, selected: Int) =
        install(SimpleDiffRequestChain.fromProducers(producers, selected))

    private fun install(chain: SimpleDiffRequestChain) {
        processor?.let(Disposer::dispose)
        component.removeAll()
        val next = object : CacheDiffRequestChainProcessor(project, chain) {
            override fun getSettings(): DiffSettings = navigationSettings
        }
        processor = next
        next.putContextUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        next.addListener(object : DiffRequestProcessorListener {
            override fun onViewerChanged() {
                next.activeRequest?.getUserData(ReviewDiffLocationKey)?.let(onFileSelected)
            }
        }, next)
        component.add(next.component, BorderLayout.CENTER)
        next.updateRequest()
        component.revalidate()
        component.repaint()
    }

    fun dispose() {
        processor?.let(Disposer::dispose)
        processor = null
    }
}
