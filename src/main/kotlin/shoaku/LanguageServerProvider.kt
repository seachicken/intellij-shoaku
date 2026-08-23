package shoaku

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import java.nio.file.Files
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

class LanguageServerProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter
    ) {
        serverStarter.ensureServerStarted(LanguageServerDescriptor(project))
    }
}

private open class AppLanguageServerDescriptor(project: Project, name: String) : ProjectWideLspServerDescriptor(project, name) {
    override fun isSupportedFile(file: VirtualFile) = true

    override val lsp4jServerClass = AppLanguageServer::class.java
}

private class LanguageServerDescriptor(project: Project) : AppLanguageServerDescriptor(project, "Shoaku") {
    override fun createCommandLine(): GeneralCommandLine {
        // Allow paths to be overridden so that script changes can be applied immediately during development.
        val localServerPath = System.getenv("SHOAKU_SERVER_PATH")
        if (localServerPath.isNullOrBlank()) {
            val pluginJar = requireNotNull(PathManager.getJarForClass(this::class.java)) {
                "Failed to locate plugin jar for ${this::class.java.name}"
            }
            val serverPath = pluginJar.parent.parent.resolve("node-module/shoaku-server.js")
            require(Files.isRegularFile(serverPath)) {
                "Failed to locate bundled server: $serverPath"
            }
            return GeneralCommandLine("node", serverPath.toString())
        } else {
            return GeneralCommandLine("node", localServerPath)
        }
    }

    override fun createInitializationOptions(): Any {
        return object {
            val filePath = project.service<ShoakuSettings>().state.filePath
        }
    }

    override fun createLsp4jClient(handler: LspServerNotificationsHandler) = object : Lsp4jClient(handler) {
        @JsonNotification("shoaku/syncGoals")
        fun syncGoals(params: SyncGoalsParams) {
            project.service<ShoakuSettings>().viewModel.apply {
                items = params.lists
                hasReceivedGoals = true
            }
        }
    }
}

data class SyncGoalsParams(
    val lists: List<Item>
)

data class Item(
    val type: String,
    val content: String,
    val checked: Boolean? = null,
    val children: List<Item> = emptyList(),
    val shoakuId: String? = null,
    val sessionId: String? = null,
    val messages: List<Message>? = emptyList(),
    val tokenUsage: TokenUsageUi? = null,
    val status: AgentStatusUi? = null,
    val temporaryWorkspace: String? = null
)

data class TaskComparisonRowUi(
    val id: String,
    val humanTask: ComparedTaskUi? = null,
    val explorerTasks: List<ComparedExplorerTaskUi> = emptyList(),
    val difference: String,
)

data class ComparedExplorerTaskUi(
    val task: ComparedTaskUi,
    val patchFullPath: String? = null
)

data class ComparedTaskUi(
    val id: String,
    val content: String,
    val checked: Boolean = false
)

data class Message(
    val type: String,
    val turnId: String? = null,
    val phase: String? = null,
    val text: String? = null,
    val command: String? = null,
    val alignmentScore: Double? = null,
    val inlineReviewComments: List<ReviewComment>? = emptyList(),
    val taskComparison: List<TaskComparison>? = null
)

data class TaskComparison(
    val humanTaskName: String,
    val explorerTaskIndex: Int,
    val explorerTaskName: String,
    val explorerPatchFullPath: String
)

data class ReviewComment(
    val path: String,
    val line: Int,
    val text: String
)

data class TokenUsageUi(
    val maxTokens: Int,
    val navigatorTokens: Int = 0,
    val explorerTokens: Int = 0
)

data class AgentStatusUi(
    val navigator: String? = null,
    val explorer: String? = null
)
