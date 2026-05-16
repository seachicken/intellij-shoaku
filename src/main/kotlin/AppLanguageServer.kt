package shoaku

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageServer

interface AppLanguageServer : LanguageServer {
    @JsonNotification("shoaku/reply")
    fun reply(params: ReplyParams)
}

data class ReplyParams(
    val text: String
)