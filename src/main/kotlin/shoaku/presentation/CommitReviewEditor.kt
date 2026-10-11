package shoaku.presentation

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.impl.CacheDiffRequestChainProcessor
import com.intellij.diff.impl.DiffRequestProcessorListener
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

internal class CommitReviewVirtualFile(val workspace: Path) : LightVirtualFile(
    "Outcome · ${workspace.fileName}", PlainTextFileType.INSTANCE, ""
)

internal fun openCommitReview(project: Project, workspace: String) {
    val path = Path.of(workspace).toAbsolutePath().normalize()
    val manager = FileEditorManager.getInstance(project)
    val existing = manager.openFiles.filterIsInstance<CommitReviewVirtualFile>().firstOrNull { it.workspace == path }
    manager.openFile(existing ?: CommitReviewVirtualFile(path), true)
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
    private val commitPicker = JComboBox<ReviewCommit>()
    private val filePicker = JComboBox<ReviewCommitFile>()
    private val status = JBLabel("Loading commits…")
    private val explanation = JBPanel<JBPanel<*>>(BorderLayout())
    private val overview = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(JBLabel("Loading outcome…"), BorderLayout.NORTH)
    }
    private val guidePanel = JBPanel<JBPanel<*>>(BorderLayout())
    private val stepPicker = JComboBox<GuideStep>()
    private val layoutPicker = JComboBox(arrayOf("Auto layout", "Side by side", "Below code"))
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
        commitPicker.name = "shoaku.commitReview.commits"
        filePicker.name = "shoaku.commitReview.files"
        stepPicker.name = "shoaku.commitReview.steps"
        layoutPicker.name = "shoaku.commitReview.layout"
        layoutPicker.toolTipText = "Auto layout places the explanation below code when the editor is narrow. Drag the divider to resize."
        stepPicker.alignmentX = 0f
        guidePanel.name = "shoaku.commitReview.guide"
        val controls = JBPanel<JBPanel<*>>(BorderLayout(8, 4))
        val commitRow = JBPanel<JBPanel<*>>(BorderLayout(8, 0))
        commitRow.add(JBLabel("Change"), BorderLayout.WEST)
        commitRow.add(commitPicker, BorderLayout.CENTER)
        controls.add(commitRow, BorderLayout.NORTH)
        val fileRow = JBPanel<JBPanel<*>>(BorderLayout(8, 0))
        fileRow.add(JBLabel("File"), BorderLayout.WEST)
        fileRow.add(filePicker, BorderLayout.CENTER)
        fileRow.add(layoutPicker, BorderLayout.EAST)
        controls.add(fileRow, BorderLayout.CENTER)
        controls.add(status, BorderLayout.SOUTH)
        val changes = JBPanel<JBPanel<*>>(BorderLayout())
        changes.add(controls, BorderLayout.NORTH)
        split.firstComponent = diff.component
        split.secondComponent = JTabbedPane().apply {
            minimumSize = Dimension(0, 0)
            addTab("Explanation", guidePanel)
            addTab("Design details", explanation)
        }
        changes.add(split, BorderLayout.CENTER)
        root.add(JTabbedPane().apply {
            name = "shoaku.outcome.sections"
            addTab("Overview", overview)
            addTab("Changes", changes)
        }, BorderLayout.CENTER)
        layoutPicker.addActionListener { updateLayout() }
        root.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = updateLayout()
        })
        commitPicker.addActionListener { if (!changingPickers) loadCommit() }
        filePicker.addActionListener { if (!changingPickers) loadFile() }
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
            stamp(root.resolve("commits/$hash.md")), stamp(root.resolve("commits/$hash.review.json")))
    }

    private fun checkForUpdates() {
        if (disposed || project.isDisposed || !updateTimer.isRunning || loading || checkingUpdates) return
        checkingUpdates = true
        val hash = (commitPicker.selectedItem as? ReviewCommit)?.hash
        ApplicationManager.getApplication().executeOnPooledThread {
            val snapshot = runCatching { readSnapshot(hash) }
            ApplicationManager.getApplication().invokeLater {
                checkingUpdates = false
                if (disposed || project.isDisposed || !updateTimer.isRunning || loading || hash != (commitPicker.selectedItem as? ReviewCommit)?.hash) return@invokeLater
                val next = snapshot.getOrNull() ?: return@invokeLater
                val previous = lastSnapshot
                lastSnapshot = next
                val displayed = (0 until commitPicker.itemCount).map(commitPicker::getItemAt)
                if (next.commits != displayed) {
                    loadCommits(next.commits)
                } else {
                    if (previous == null || next.overview != previous.overview) {
                        showDocument(reviewFile.workspace.resolve(".shoaku/RESULTS.md"), overview,
                            "The outcome summary will appear as work progresses.")
                    }
                    if (hash != null && (previous == null || previous.hash == hash &&
                            (next.design != previous.design || next.map != previous.map))) {
                        loadCommit((filePicker.selectedItem as? ReviewCommitFile)?.path,
                            (stepPicker.selectedItem as? GuideStep)?.id)
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
        val previous = (commitPicker.selectedItem as? ReviewCommit)?.hash
        val previousFile = (filePicker.selectedItem as? ReviewCommitFile)?.path
        val previousStep = (stepPicker.selectedItem as? GuideStep)?.id
        selectedDetails = null
        changingPickers = true
        filePicker.model = DefaultComboBoxModel()
        changingPickers = false
        diff.setRequest(MessageDiffRequest("Loading commits…"))
        clearDocument()
        guide = null
        guideIssue = "Loading the design-to-code map…"
        renderGuide()
        load({
            val commits = updatedCommits ?: reader.commits()
            val selected = commits.firstOrNull { it.hash == previous } ?: commits.firstOrNull()
            readSnapshot(selected?.hash, commits)
        }) { snapshot ->
            lastSnapshot = snapshot
            val commits = snapshot.commits
            showDocument(reviewFile.workspace.resolve(".shoaku/RESULTS.md"), overview,
                "The outcome summary will appear as work progresses.")
            changingPickers = true
            commitPicker.model = DefaultComboBoxModel(commits.toTypedArray())
            commits.firstOrNull { it.hash == previous }?.let { commitPicker.selectedItem = it }
            changingPickers = false
            status.text = if (commits.isEmpty()) "No changes ready to review yet. Updates appear automatically." else "${commits.size} changes to review"
            if (commits.isEmpty()) diff.setRequest(MessageDiffRequest("No changes ready to review yet.")) else loadCommit(previousFile, previousStep)
        }
    }

    private fun loadCommit(preferredFile: String? = null, preferredStep: String? = null) {
        val commit = commitPicker.selectedItem as? ReviewCommit ?: return
        selectedDetails = null
        changingPickers = true
        filePicker.model = DefaultComboBoxModel()
        changingPickers = false
        status.text = "Loading ${commit.hash.take(12)}…"
        diff.setRequest(MessageDiffRequest("Loading commit…"))
        clearDocument()
        guide = null
        guideIssue = "Loading the design-to-code map…"
        renderGuide()
        load({
            val details = reader.details(commit)
            details to readReviewGuide(reviewFile.workspace, details, reader)
        }) { (details, result) ->
            selectedDetails = details
            guide = result.guide
            guideIssue = result.issue
            choosingStep = true
            stepPicker.model = DefaultComboBoxModel(guide?.steps?.toTypedArray() ?: emptyArray())
            choosingStep = false
            showDocument(commit)
            changingPickers = true
            filePicker.model = DefaultComboBoxModel(details.files.toTypedArray())
            details.files.firstOrNull { it.path == preferredFile }?.let { filePicker.selectedItem = it }
            changingPickers = false
            status.text = "${details.files.size} files · parent ${details.parent?.take(12) ?: "empty tree"}"
            status.toolTipText = commit.hash
            renderGuide()
            if (details.files.isEmpty()) diff.setRequest(MessageDiffRequest("This commit has no file changes."))
            else if (guide != null) {
                val selected = guide!!.steps.firstOrNull { it.id == preferredStep } ?: guide!!.steps.first()
                selectStep(selected, preferredFile == null)
                if (preferredFile != null) loadFile()
            }
            else loadFile()
        }
    }

    private fun loadFile(anchor: GuideAnchor? = null) {
        val details = selectedDetails ?: return
        val file = filePicker.selectedItem as? ReviewCommitFile ?: return
        if (anchor == null) {
            val related = guide?.steps?.filter { step -> step.anchors.any { it.path == file.path } }.orEmpty()
            val current = stepPicker.selectedItem as? GuideStep
            if (related.isNotEmpty()) selectStep(related.firstOrNull { it.id == current?.id } ?: related.first(), false)
            else renderGuide(file.path)
        }
        val step = stepPicker.selectedItem as? GuideStep
        if (step != null && guide != null && step.anchors.any { it.path == file.path }) showStepStatus(step, anchor ?: step.anchors.first { it.path == file.path })
        else status.text = "${details.files.size} files · parent ${details.parent?.take(12) ?: "empty tree"}"
        val map = guide
        val producers = details.files.map { changedFile ->
            object : DiffRequestProducer {
                override fun getName() = changedFile.path
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
                    request.putUserData(ReviewDiffPathKey, changedFile.path)
                    if (anchor?.path == changedFile.path) {
                        request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE,
                            Pair.create(if (anchor.side == "before") Side.LEFT else Side.RIGHT, anchor.startLine - 1))
                    }
                    map?.let {
                        request.putUserData(CodeTraceKey, CodeTraceData(it.steps, changedFile.path,
                            { (stepPicker.selectedItem as? GuideStep)?.id },
                            { selected -> if (!disposed && selectedDetails?.commit?.hash == details.commit.hash &&
                                (filePicker.selectedItem as? ReviewCommitFile)?.path == changedFile.path &&
                                (stepPicker.selectedItem as? GuideStep)?.id != selected.id) selectStep(selected, false) }))
                    }
                    return request
                }
            }
        }
        diff.setFiles(producers, details.files.indexOf(file))
    }

    private fun onDiffFileSelected(path: String) {
        if (disposed) return
        val file = selectedDetails?.files?.firstOrNull { it.path == path } ?: return
        changingPickers = true
        filePicker.selectedItem = file
        changingPickers = false
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
        val location = step.anchors.firstOrNull { it.path == (filePicker.selectedItem as? ReviewCommitFile)?.path } ?: step.anchors.first()
        showStepStatus(step, location)
        if (navigate) step.anchors.firstOrNull()?.let(::navigateToAnchor)
    }

    private fun showStepStatus(step: GuideStep, anchor: GuideAnchor) {
        status.text = "${guide?.steps?.indexOf(step)?.plus(1)}. ${step.title} → ${anchor.path}:${anchor.startLine}–${anchor.endLine} (${anchor.side})"
    }

    private fun navigateToAnchor(anchor: GuideAnchor) {
        val details = selectedDetails ?: return
        val file = details.files.firstOrNull { it.path == anchor.path } ?: return
        changingPickers = true
        filePicker.selectedItem = file
        changingPickers = false
        loadFile(anchor)
    }

    private fun renderGuide(unmappedFile: String? = null) {
        val previousPosition = (guidePanel.components.firstOrNull() as? JBScrollPane)?.verticalScrollBar?.value ?: 0
        guidePanel.removeAll()
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
            paragraph("Correspondence not available", guideIssue ?: "No design-to-code map is available.")
        } else {
            paragraph("Problem", map.problem)
            paragraph("Before → After", "${map.before}\n→ ${map.after}")
            val covered = map.steps.flatMap { it.anchors }.map { it.path }.distinct().size
            paragraph("Code correspondence", "$covered of ${selectedDetails?.files?.size ?: 0} changed files mapped. ${map.excluded.size} explicitly excluded.")
            body.add(JBLabel("Design flow — select a responsibility").apply { alignmentX = 0f })
            body.add(ReviewFlowDiagram(map.steps) { selectStep(it, true) }.apply {
                selectedId = (stepPicker.selectedItem as? GuideStep)?.id
                alignmentX = 0f
            })
            body.add(stepPicker)
            if (unmappedFile != null) {
                paragraph("No mapped design step for $unmappedFile", map.excluded.firstOrNull { it.path == unmappedFile }?.reason ?: "This file has no code correspondence.")
            } else {
                val step = stepPicker.selectedItem as? GuideStep
                if (step != null) {
                    paragraph("Why this choice?", step.why)
                    paragraph("Implementation", step.change)
                    body.add(JBLabel("Code — select a location to highlight it").apply { alignmentX = 0f })
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
                    paragraph("Verification — reported by the author", step.verification)
                }
            }
            body.add(JBLabel("Selecting mapped code updates this explanation.").apply { alignmentX = 0f })
        }
        val scroll = JBScrollPane(body)
        guidePanel.add(scroll, BorderLayout.CENTER)
        guidePanel.revalidate()
        guidePanel.repaint()
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && scroll.parent == guidePanel) scroll.verticalScrollBar.value = previousPosition
        }
    }

    private fun clearDocument(panel: JComponent = explanation) {
        documentEditors.remove(panel)?.let { (provider, editor) ->
            editor.deselectNotify()
            provider.disposeEditor(editor)
        }
        panel.removeAll()
        panel.revalidate()
        panel.repaint()
    }

    private fun showDocument(commit: ReviewCommit) {
        val path = reviewFile.workspace.resolve(".shoaku/commits/${commit.hash}.md")
        showDocument(path, explanation, "Explanation pending for ${commit.hash.take(12)}.")
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
    override fun getPreferredFocusedComponent(): JComponent = commitPicker
    override fun getName() = "Outcome"
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

private val ReviewDiffPathKey = Key.create<String>("shoaku.outcome.diff.path")

/** Uses the same request-chain processor as the IDE's multi-file diff editor. */
private class NativeReviewDiffPanel(private val project: Project, private val onFileSelected: (String) -> Unit) {
    val component = JBPanel<JBPanel<*>>(BorderLayout())
    private var processor: CacheDiffRequestChainProcessor? = null

    fun setRequest(request: DiffRequest) = install(SimpleDiffRequestChain(request))

    fun setFiles(producers: List<DiffRequestProducer>, selected: Int) =
        install(SimpleDiffRequestChain.fromProducers(producers, selected))

    private fun install(chain: SimpleDiffRequestChain) {
        processor?.let(Disposer::dispose)
        component.removeAll()
        val next = CacheDiffRequestChainProcessor(project, chain)
        processor = next
        next.putContextUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        next.addListener(object : DiffRequestProcessorListener {
            override fun onViewerChanged() {
                next.activeRequest?.getUserData(ReviewDiffPathKey)?.let(onFileSelected)
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
