package shoaku

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

interface AppLanguageServer : LanguageServer {
    @JsonNotification("shoaku/startSession")
    fun startSession(params: StartSessionParams)
    @JsonNotification("shoaku/didChangeGoalsFilePath")
    fun didChangeGoalsFilePath(params: DidChangeGoalsFilePath)
    @JsonNotification("shoaku/applyDiff")
    fun applyDiff(params: ApplyDiffParams)
    @JsonNotification("shoaku/reply")
    fun reply(params: ReplyParams)
    @JsonNotification("shoaku/didChangeMaxTokens")
    fun didChangeMaxTokens(params: DidChangeMaxTokens)
    @JsonRequest("shoaku/createDiff")
    fun createDiff(params: CreateDiffParams): CompletableFuture<CreateDiffResult>
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

data class MakeMeExplainParams(
    val shoakuId: String?
)

data class ApplyDiffParams(
    val shoakuId: String,
    val response: String
)

data class CreateDiffParams(
    val shoakuId: String,
    val explorerTaskIndex: Int
)

data class CreateDiffResult(
    val explorerTaskPath: String
)

data class RequestTaskGuidanceParams(
    val shoakuId: String,
    val mode: String
)

data class DidChangeMaxTokens(
    val shoakuId: String,
    val tokens: Int
)
