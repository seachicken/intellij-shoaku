package shoaku

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.service
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
        val basePath = project.basePath ?: error("Project base path is not available")
        return GeneralCommandLine("node", "$basePath/server/index.js")
    }

    override fun createInitializationOptions(): Any {
        return object {
            val filePath = project.service<ShoakuSettings>().state.filePath
        }
    }

    override fun createLsp4jClient(handler: LspServerNotificationsHandler) = object : Lsp4jClient(handler) {
        @JsonNotification("shoaku/notification")
        fun shoaku(params: ShoakuNotificationParams) {
            if (params.lists[0].status != null && project.service<ShoakuSettings>().viewModel.items.isNotEmpty()) {
                project.service<ShoakuSettings>().viewModel.items[0].status = params.lists[0].status
            } else {
                project.service<ShoakuSettings>().viewModel.apply {
                    items = params.lists
                    response = params.response
                }
            }
        }
    }
}

data class ShoakuNotificationParams(
    val lists: List<Item>,
    val response: String
)

data class Item(
    val type: String,
    val content: String,
    val checked: Boolean? = null,
    var status: String = "",
    val children: List<Item> = emptyList()
)