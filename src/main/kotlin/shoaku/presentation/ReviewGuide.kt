package shoaku.presentation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

internal data class GuideAnchor(val path: String, val side: String, val startLine: Int, val endLine: Int, val label: String)
internal data class GuideLink(val to: String, val label: String)
internal data class GuideStep(val id: String, val title: String, val why: String, val change: String,
                              val verification: String, val anchors: List<GuideAnchor>, val links: List<GuideLink>) {
    override fun toString() = title
}
internal data class GuideExclusion(val path: String, val reason: String)
internal data class ReviewGuide(val problem: String, val before: String, val after: String,
                               val steps: List<GuideStep>, val excluded: List<GuideExclusion>)
internal data class ReviewGuideResult(val guide: ReviewGuide?, val issue: String?)

internal fun readReviewGuide(workspace: Path, details: ReviewCommitDetails, reader: GitCommitReviewReader): ReviewGuideResult {
    val path = workspace.resolve(".shoaku/commits/${details.commit.hash}.review.json")
    if (!Files.exists(path)) return ReviewGuideResult(null, "Design-to-code links have not been generated for this commit. Read the design details; links will appear automatically when available.")
    return try {
        require(Files.size(path) <= 512 * 1024) { "Design map is too large." }
        val json = JsonParser.parseString(Files.readString(path)).asJsonObject
        fun JsonObject.text(key: String): String {
            val value = get(key)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Missing design field: $key" }
            return value.asString.also { require(it.isNotBlank() && it.length <= 4000) { "Invalid design field: $key" } }
        }
        fun JsonObject.integer(key: String): Int {
            val value = get(key)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Invalid number: $key" }
            return value.asBigDecimal.intValueExact()
        }
        require(json.integer("schemaVersion") == 1 && json.text("commit") == details.commit.hash) { "Design map belongs to a different commit." }
        val steps = json.getAsJsonArray("steps").map { element ->
            val step = element.asJsonObject
            val anchors = step.getAsJsonArray("anchors").map { anchorElement ->
                val anchor = anchorElement.asJsonObject
                GuideAnchor(anchor.text("path"), anchor.text("side"), anchor.integer("startLine"), anchor.integer("endLine"), anchor.text("label"))
            }
            val links = step.getAsJsonArray("links").map { linkElement ->
                val link = linkElement.asJsonObject
                GuideLink(link.text("to"), link.text("label"))
            }
            GuideStep(step.text("id"), step.text("title"), step.text("why"), step.text("change"), step.text("verification"), anchors, links)
        }
        require(steps.size in (if (details.files.isEmpty()) 0 else 1)..24 && steps.map { it.id }.distinct().size == steps.size && steps.all { it.id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) }) { "Design steps need unique IDs." }
        val markdownPath = workspace.resolve(".shoaku/commits/${details.commit.hash}.md")
        val markdown = Files.readString(markdownPath)
        require(steps.all { markdown.contains(it.title) }) { "Design step titles do not match the full design document." }
        val excluded = json.getAsJsonArray("excludedFiles").map { element ->
            val item = element.asJsonObject
            GuideExclusion(item.text("path"), item.text("reason"))
        }
        val blobs = mutableMapOf<String, ReviewFileContent>()
        for (step in steps) {
            require(step.anchors.size in 1..20) { "${step.title} needs a concrete code location." }
            require(step.links.all { link -> steps.any { it.id == link.to } }) { "Design flow has an unknown target." }
            for (anchor in step.anchors) {
                val file = details.files.firstOrNull { it.path == anchor.path } ?: error("Unchanged or unknown file: ${anchor.path}")
                require(anchor.side in listOf("before", "after") && anchor.startLine >= 1 && anchor.endLine >= anchor.startLine) { "Invalid code location." }
                val content = blobs.getOrPut(anchor.path) { reader.content(details, file) }
                require(content.unavailableReason == null) { content.unavailableReason.orEmpty() }
                val text = if (anchor.side == "before") content.before else content.after
                val lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1
                require(anchor.endLine <= lines) { "${anchor.path}:${anchor.startLine}-${anchor.endLine} does not exist in the selected revision." }
            }
        }
        val covered = steps.flatMap { it.anchors }.map { it.path }.toSet()
        require(excluded.all { it.path in details.files.map { file -> file.path } && it.path !in covered }) { "Invalid excluded files." }
        require(details.files.all { it.path in covered || excluded.any { excludedFile -> excludedFile.path == it.path } }) { "Some changed files have no design correspondence." }
        ReviewGuideResult(ReviewGuide(json.text("problem"), json.text("before"), json.text("after"), steps, excluded), null)
    } catch (error: Exception) {
        ReviewGuideResult(null, "Design-to-code links are unavailable: ${error.message}")
    }
}
