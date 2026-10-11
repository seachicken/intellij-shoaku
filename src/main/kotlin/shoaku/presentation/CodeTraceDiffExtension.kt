package shoaku.presentation

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor

internal data class CodeTraceData(val steps: List<GuideStep>, val path: String,
                                  val selectedStep: () -> String?, val onSelect: (GuideStep) -> Unit)
internal val CodeTraceKey = Key.create<CodeTraceData>("shoaku.code.trace")

class CodeTraceDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val data = request.getUserData(CodeTraceKey) ?: return
        val anchors = data.steps.flatMap { step -> step.anchors.filter { it.path == data.path }.map { step to it } }
        fun install(editor: Editor, positions: (GuideAnchor) -> IntRange?, originalLine: (GuideAnchor, Int) -> Int) {
            var marks = emptyList<Pair<GuideStep, RangeHighlighter>>()
            fun refreshMarks() {
                if (editor.isDisposed) return
                marks.forEach { (_, marker) -> editor.markupModel.removeHighlighter(marker) }
                marks = anchors.mapNotNull { (step, anchor) ->
                    val range = positions(anchor) ?: return@mapNotNull null
                    if (range.first < 0 || range.last >= editor.document.lineCount) return@mapNotNull null
                    val marker = editor.markupModel.addRangeHighlighter(editor.document.getLineStartOffset(range.first),
                        editor.document.getLineEndOffset(range.last), HighlighterLayer.SELECTION - 1,
                        attributes(step.id == data.selectedStep()), HighlighterTargetArea.LINES_IN_RANGE)
                    marker.errorStripeTooltip = "${step.title}: ${step.why}"
                    step to marker
                }
            }
            refreshMarks()
            val listener = object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    val matches = anchors.filter { (_, anchor) -> originalLine(anchor, event.newPosition.line) + 1 in anchor.startLine..anchor.endLine }
                    val step = matches.firstOrNull { it.first.id == data.selectedStep() }?.first ?: matches.firstOrNull()?.first ?: return
                    data.onSelect(step)
                    marks.forEach { (mappedStep, marker) -> (editor.markupModel as? MarkupModelEx)?.setRangeHighlighterAttributes(marker, attributes(mappedStep.id == data.selectedStep())) }
                }
            }
            editor.caretModel.addCaretListener(listener)
            val diffListener = object : DiffViewerListener() { override fun onAfterRediff() { refreshMarks() } }
            (viewer as? DiffViewerBase)?.addListener(diffListener)
            Disposer.register(viewer, Disposable {
                (viewer as? DiffViewerBase)?.removeListener(diffListener)
                if (!editor.isDisposed) {
                    editor.caretModel.removeCaretListener(listener)
                    marks.forEach { (_, marker) -> editor.markupModel.removeHighlighter(marker) }
                }
            })
        }
        when (viewer) {
            is SimpleDiffViewer -> for (side in listOf(Side.LEFT, Side.RIGHT)) {
                val name = if (side == Side.LEFT) "before" else "after"
                install(viewer.getEditor(side),
                    { anchor -> if (anchor.side == name) (anchor.startLine - 1)..(anchor.endLine - 1) else null },
                    { anchor, line -> if (anchor.side == name) line else -1 })
            }
            is UnifiedDiffViewer -> install(viewer.editor,
                { anchor ->
                    val side = if (anchor.side == "before") Side.LEFT else Side.RIGHT
                    val start = viewer.transferLineToOnesideStrict(side, anchor.startLine - 1)
                    val end = viewer.transferLineToOnesideStrict(side, anchor.endLine - 1)
                    if (start < 0 || end < start) null else start..end
                },
                { anchor, line -> viewer.transferLineFromOnesideStrict(if (anchor.side == "before") Side.LEFT else Side.RIGHT, line) })
        }
    }

    private fun attributes(selected: Boolean) = TextAttributes().apply {
        backgroundColor = if (selected) JBColor(0xDDEBFF, 0x354C6A) else JBColor(0xEDF3FA, 0x323C49)
    }
}
