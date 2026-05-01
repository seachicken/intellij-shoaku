package shoaku

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor

class LanguageServerProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter
    ) {
        if (file.extension == "md") {
            serverStarter.ensureServerStarted(LanguageServerDescriptor(project))
        }
    }
}

private class LanguageServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "Shoaku") {
    override fun isSupportedFile(file: VirtualFile) = file.extension == "md"
    override fun createCommandLine(): GeneralCommandLine {
        return GeneralCommandLine("node", "")
    }
    override fun createInitializationOptions(): Any {
        return object {
            val filePath: String? = project?.service<ShoakuSettings>()?.state?.filePath
        }
    }
}