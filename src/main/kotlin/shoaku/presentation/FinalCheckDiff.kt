package shoaku.shoaku.presentation

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.util.Side
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import shoaku.shoaku.ReviewComment
import shoaku.shoaku.ShoakuSettings
import java.net.URI
import java.util.WeakHashMap

private data class ReviewDiffData(
    val path: String,
    val selectedLine: Int
)

data class ReviewLocation(
    val path: String,
    val line: Int
)

private val ReviewDiffDataKey = Key.create<ReviewDiffData>("shoaku.review.diff.data")
private data class ReviewDiffTabState(
    val file: ChainDiffVirtualFile
)

private val reviewDiffTabs = WeakHashMap<Project, ReviewDiffTabState>()

internal fun openReviewDiff(
    project: Project,
    temporaryWorkspace: String?,
    link: String,
    comments: List<ReviewComment>
) {
    val target = parseReviewTarget(link) ?: return
    val projectPath = project.basePath ?: return
    val workspacePath = temporaryWorkspace?.takeIf { it.isNotBlank() } ?: return
    val contentFactory = DiffContentFactory.getInstance()
    val paths = buildList {
        add(target.path)
        comments.asSequence()
            .map { it.path }
            .filter { it.isNotBlank() && it != target.path }
            .distinct()
            .forEach(::add)
    }

    val requests = paths.mapNotNull { path ->
        val projectFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath("$projectPath/$path") ?: return@mapNotNull null
        val workspaceFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath("$workspacePath/$path") ?: return@mapNotNull null
        val projectText = String(projectFile.contentsToByteArray(), projectFile.charset)
        val workspaceText = String(workspaceFile.contentsToByteArray(), workspaceFile.charset)
        val fileComments = comments.filter { it.path == path || it.path == projectFile.path }
    val selectedLine = if (path == target.path) {
            target.line ?: fileComments.firstOrNull()?.line ?: 1
        } else {
            fileComments.firstOrNull()?.line ?: 1
        }
        SimpleDiffRequest(
            "Final Check: ${projectFile.name}",
            contentFactory.create(project, projectText, projectFile),
            contentFactory.create(project, workspaceText, workspaceFile),
            "Project",
            "Explorer workspace"
        ).apply {
            putUserData(ReviewDiffDataKey, ReviewDiffData(path, selectedLine))
        }
    }
    if (requests.isEmpty()) return

    project.service<ShoakuSettings>().viewModel.selectedReviewLocation =
        ReviewLocation(target.path, target.line ?: comments.firstOrNull { it.path == target.path }?.line ?: 1)

    val fileEditorManager = FileEditorManager.getInstance(project)
    reviewDiffTabs.remove(project)?.file?.let { previousFile ->
        if (fileEditorManager.isFileOpen(previousFile)) {
            fileEditorManager.closeFile(previousFile)
        }
    }

    val diffFile = ChainDiffVirtualFile(
        SimpleDiffRequestChain(requests, 0),
        "Review Diff"
    )
    reviewDiffTabs[project] = ReviewDiffTabState(diffFile)
    fileEditorManager.openFile(diffFile, true)
}

private data class ReviewTarget(val path: String, val line: Int?)

private fun parseReviewTarget(link: String): ReviewTarget? {
    val uri = runCatching { URI(link) }.getOrNull()
    if (uri != null && uri.scheme == "shoaku-review") {
        val path = buildString {
            uri.host?.takeIf { it.isNotBlank() }?.let(::append)
            append(uri.path.orEmpty())
        }.trimStart('/')
        return path.takeIf { it.isNotBlank() }?.let { ReviewTarget(it, uri.fragment?.removePrefix("L")?.toIntOrNull()) }
    }

    val normalized = link.substringBefore('#').trimStart('/')
    val line = link.substringAfter("#L", "").toIntOrNull()
    return normalized.takeIf { it.contains('/') && it.isNotBlank() }?.let { ReviewTarget(it, line) }
}

class ReviewDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val data = request.getUserData(ReviewDiffDataKey) ?: return
        val simpleViewer = viewer as? SimpleDiffViewer ?: return
        val editor = simpleViewer.getEditor(Side.RIGHT)

        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            val project = context.project ?: return@invokeLater
            fun updateSelectedLocation(line: Int) {
                project.service<ShoakuSettings>().viewModel.selectedReviewLocation =
                    ReviewLocation(data.path, line)
            }
            if (editor.document.lineCount > 0) {
                val selectedLine = (data.selectedLine - 1).coerceIn(0, editor.document.lineCount - 1)
                editor.caretModel.moveToLogicalPosition(LogicalPosition(selectedLine, 0))
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                updateSelectedLocation(selectedLine + 1)
            }
            editor.caretModel.addCaretListener(object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    updateSelectedLocation(event.newPosition.line + 1)
                }
            })
        }
    }
}
