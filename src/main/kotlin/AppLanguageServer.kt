package shoaku

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageServer

interface AppLanguageServer : LanguageServer {
    @JsonNotification("shoaku/startSession")
    fun startSession(params: StartSessionParams)

    @JsonNotification("shoaku/reply")
    fun reply(params: ReplyParams)
}

data class StartSessionParams(
    val shoakuId: String?
)

data class ReplyParams(
    val shoakuId: String,
    val text: String
)