package shoaku.presentation

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal data class ReviewCommit(val hash: String, val subject: String) {
    override fun toString() = "$subject  · ${hash.take(12)}"
}

internal data class ReviewCommitFile(val path: String, val status: String) {
    override fun toString() = "[$status] $path"
}

internal data class ReviewCommitDetails(val commit: ReviewCommit, val parent: String?, val files: List<ReviewCommitFile>)
internal data class ReviewFileContent(val before: String, val after: String, val unavailableReason: String? = null)

/** All commands are read-only and use argument arrays, including file paths. */
internal class GitCommitReviewReader(private val workspace: Path) {
    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "--no-pager", "-C", workspace.toString()) + args)
            .redirectErrorStream(true).apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }.start()
        process.outputStream.close()
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }
        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Git review command timed out." }
            val text = output.get(5, TimeUnit.SECONDS)
            check(process.exitValue() == 0) { text.trim().ifEmpty { "Git could not read the commit." } }
            return text
        } finally {
            if (process.isAlive) process.destroyForcibly()
            output.cancel(true)
        }
    }

    private fun validateHash(hash: String) {
        require(Regex("[a-f0-9]{40,64}").matches(hash)) { "Invalid commit revision." }
    }

    private fun prSnapshot() = workspace.resolve(".shoaku/pr-review.json").let { path ->
        if (Files.isRegularFile(path)) JsonParser.parseString(Files.readString(path)).asJsonObject else null
    }

    fun commits(): List<ReviewCommit> {
        prSnapshot()?.let { pr ->
            val head = pr.get("head").asString
            validateHash(head)
            return listOf(ReviewCommit(head, pr.get("title").asString))
        }
        // Direct multi-repository workspaces show commit summaries in RESULTS.md.
        // They have no root Git history or per-commit diff navigation.
        if (Files.isRegularFile(workspace.resolve(".shoaku/repositories.json"))) return emptyList()
        val base = Files.readString(workspace.resolve(".shoaku/base-revision")).trim()
        validateHash(base)
        git("merge-base", "--is-ancestor", base, "HEAD")
        return git("log", "--no-color", "--reverse", "--format=%H%x09%s", "$base..HEAD", "--")
            .lineSequence().filter { it.isNotBlank() }.map { line ->
                ReviewCommit(line.substringBefore('\t'), line.substringAfter('\t')).also { validateHash(it.hash) }
            }.toList()
    }

    fun details(commit: ReviewCommit): ReviewCommitDetails {
        validateHash(commit.hash)
        val parent = prSnapshot()?.let { pr ->
            require(pr.get("head").asString == commit.hash) { "PR snapshot changed." }
            pr.get("base").asString.also(::validateHash)
        } ?: git("rev-list", "--parents", "-n", "1", commit.hash, "--").trim().split(' ').getOrNull(1)
        val changed = if (parent == null) {
            git("diff-tree", "--root", "--no-commit-id", "--name-status", "--no-renames", "-r", "-z", commit.hash, "--")
        } else {
            git("diff", "--name-status", "--no-renames", "-z", parent, commit.hash, "--")
        }
        val fields = changed.split('\u0000').dropLastWhile { it.isEmpty() }
        check(fields.size % 2 == 0) { "Git returned an invalid changed-file list." }
        val files = fields.chunked(2).map { ReviewCommitFile(it[1], it[0]) }
        return ReviewCommitDetails(commit, parent, files)
    }

    fun content(details: ReviewCommitDetails, file: ReviewCommitFile): ReviewFileContent {
        validateHash(details.commit.hash)
        details.parent?.let(::validateHash)
        fun blob(revision: String): String? {
            val objectName = "$revision:${file.path}"
            val size = git("cat-file", "-s", objectName).trim().toLong()
            if (size > 2 * 1024 * 1024) return null
            return git("show", objectName)
        }
        val before = if (file.status == "A" || details.parent == null) "" else blob(details.parent)
        val after = if (file.status == "D") "" else blob(details.commit.hash)
        if (before == null || after == null) return ReviewFileContent("", "", "This file is larger than 2 MB. Review it in Git Log.")
        if ('\u0000' in before || '\u0000' in after) return ReviewFileContent("", "", "Binary file changed. Review it in Git Log.")
        return ReviewFileContent(before, after)
    }
}
