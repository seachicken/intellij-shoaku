package shoaku.presentation

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Files
import java.nio.file.Path

/** Opens the existing checkout in a project window or a terminal in the source IDE. */
internal object WorktreeProjectOpener {
    private suspend fun workspacePath(workspace: String): Path = withContext(Dispatchers.IO) {
        val candidate = Path.of(workspace)
        require(Files.isDirectory(candidate)) { "The worktree directory is no longer available: $workspace" }
        require(Files.exists(candidate.resolve(".git"))) { "The worktree has no Git metadata: $workspace" }
        candidate.toRealPath()
    }

    suspend fun openTerminal(sourceProject: Project, workspace: String) {
        val path = workspacePath(workspace)
        withContext(Dispatchers.EDT) {
            check(!sourceProject.isDisposed) { "The source project is already closed." }
            TerminalToolWindowManager.getInstance(sourceProject)
                .createShellWidget(path.toString(), "Implementation: ${path.fileName}", true, true)
        }
    }

    suspend fun open(sourceProject: Project, workspace: String): Project? {
        val path = workspacePath(workspace)
        return withContext(Dispatchers.EDT) {
            if (sourceProject.isDisposed) return@withContext null
            // forceOpenInNewFrame skips automatic reuse, so focus an existing
            // project first. Leave the source window open to keep its agents running.
            val opened = ProjectUtil.findAndFocusExistingProjectForPath(path)
                ?: ProjectUtil.openOrImport(path, null, true)
                ?: return@withContext null
            opened.service<WorktreeReviewStartup>().runWhenReady {
                ApplicationManager.getApplication().invokeLater {
                    if (!opened.isDisposed) {
                        ToolWindowManager.getInstance(opened).getToolWindow(ToolWindowId.VCS)?.show()
                        val reviewDocument = listOf("COMMITS.md", "RESULTS.md", "PLANS.md")
                            .map { path.resolve(".shoaku").resolve(it) }
                            .firstOrNull(Files::isRegularFile)
                        reviewDocument?.let {
                            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)?.let { file ->
                                FileEditorManager.getInstance(opened).openFile(file, true)
                                    .filterIsInstance<TextEditorWithPreview>()
                                    .forEach { editor -> editor.setLayout(TextEditorWithPreview.Layout.SHOW_PREVIEW) }
                            }
                        }
                    }
                }
            }
            opened
        }
    }
}

/** Requests and startup completion are serialized on EDT, including already-open projects. */
@Service(Service.Level.PROJECT)
internal class WorktreeReviewStartup {
    private var ready = false
    private val pending = mutableListOf<() -> Unit>()

    fun runWhenReady(action: () -> Unit) {
        if (ready) action() else pending.add(action)
    }

    fun projectOpened() {
        ready = true
        val actions = pending.toList()
        pending.clear()
        actions.forEach { it() }
    }
}

internal class WorktreeReviewStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) project.service<WorktreeReviewStartup>().projectOpened()
        }
    }
}
