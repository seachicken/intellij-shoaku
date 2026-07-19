package shoaku.shoaku

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageServer

interface AppLanguageServer : LanguageServer {
    @JsonNotification("shoaku/startSession")
    fun startSession(params: StartSessionParams)
    @JsonNotification("shoaku/didChangeGoalsFilePath")
    fun didChangeGoalsFilePath(params: DidChangeGoalsFilePath)
    @JsonNotification("shoaku/applyDiff")
    fun applyDiff(params: ApplyDiffParams)
    @JsonNotification("shoaku/reply")
    fun reply(params: ReplyParams)
    @JsonNotification("shoaku/startFinalCheck")
    fun startFinalCheck(params: StartFinalCheckParams)
}

data class DidChangeGoalsFilePath(
    val filePath: String
)

data class StartSessionParams(
    val shoakuId: String?
)

data class ReplyParams(
    val shoakuId: String,
    val text: String
)

data class ApplyDiffParams(
    val shoakuId: String,
    val response: String
)

data class StartFinalCheckParams(
    val shoakuId: String?
)
