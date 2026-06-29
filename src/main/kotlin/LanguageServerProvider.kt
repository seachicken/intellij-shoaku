package shoaku

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
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
//        val basePath = project.basePath ?: error("Project base path is not available")
//        return GeneralCommandLine("node", "$basePath/server/src/index.js")
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("shoaku.intellij-shoaku"))
        val serverPath = plugin!!.pluginPath.resolve("node-module/shoaku-server.js")
        return GeneralCommandLine("node", serverPath.toString())
    }

    override fun createInitializationOptions(): Any {
        return object {
            val filePath = project.service<ShoakuSettings>().state.filePath
        }
    }

    override fun createLsp4jClient(handler: LspServerNotificationsHandler) = object : Lsp4jClient(handler) {
        @JsonNotification("shoaku/notification")
        fun shoaku(params: ShoakuNotificationParams) {
            project.service<ShoakuSettings>().viewModel.apply {
                items = params.lists
            }
        }

        @JsonNotification("shoaku/showDiff")
        fun showDiff(params: ShoakuShowDiffParams) {
            project.service<ShoakuSettings>().viewModel.apply {
                if (params.response.isNotBlank()) {
                    diffResponses[params.shoakuId] = params
                }
            }
        }
    }
}

data class ShoakuNotificationParams(
    val lists: List<Item>
)

data class Item(
    val type: String,
    val content: String,
    val checked: Boolean? = null,
    val children: List<Item> = emptyList(),
    val shoakuId: String? = null,
    val messages: List<Message>? = emptyList(),
    val tokenUsage: TokenUsageUi? = null,
    val status: AgentStatusUi? = null
)

data class Message(
    val type: String,
    val text: String? = null,
    val command: String? = null
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

data class ShoakuShowDiffParams(
    val shoakuId: String,
    val response: String,
    val diff: String
)
