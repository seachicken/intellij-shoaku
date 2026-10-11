package shoaku.presentation

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.CubicCurve2D
import javax.swing.JComponent

/** Nodes and edges come from the commit's explicit design map, never filenames. */
internal class ReviewFlowDiagram(private val steps: List<GuideStep>, private val onSelect: (GuideStep) -> Unit) : JComponent() {
    var selectedId: String? = null
        set(value) { field = value; repaint() }

    init {
        preferredSize = Dimension(320, steps.size * 58 + 12)
        minimumSize = Dimension(170, preferredSize.height)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        toolTipText = "Select a responsibility to inspect its rationale and code."
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                val index = (event.y - 8) / 58
                if (event.y >= 8 && index in steps.indices && (event.y - 8) % 58 < 42 && event.x in 8..(width - 45)) onSelect(steps[index])
            }
        })
    }

    override fun getToolTipText(event: MouseEvent): String? = steps.getOrNull((event.y - 8) / 58)?.let { "${it.title}: ${it.why}" }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.stroke = BasicStroke(1.5f)
            val right = (width - 45).coerceAtLeast(120)
            g.color = JBColor.GRAY
            steps.forEachIndexed { index, step ->
                step.links.forEach { link ->
                    val target = steps.indexOfFirst { it.id == link.to }
                    g.color = if (step.id == selectedId || steps[target].id == selectedId) JBColor(0x3469AD, 0x83B4F3) else JBColor.GRAY
                    val fromY = index * 58 + 29
                    val toY = target * 58 + 29
                    g.draw(CubicCurve2D.Double(right.toDouble(), fromY.toDouble(), (right + 30).toDouble(), fromY.toDouble(), (right + 30).toDouble(), toY.toDouble(), right.toDouble(), toY.toDouble()))
                    g.drawLine(right, toY, right + 7, toY - 4)
                    g.drawLine(right, toY, right + 7, toY + 4)
                }
            }
            steps.forEachIndexed { index, step ->
                val y = index * 58 + 8
                g.color = if (step.id == selectedId) JBColor(0xDCEBFF, 0x344967) else JBColor(0xF2F4F7, 0x34373B)
                g.fillRoundRect(8, y, right - 8, 42, 12, 12)
                g.color = JBColor.foreground()
                val title = "${index + 1}. ${step.title}"
                var label = title
                while (label.length > 1 && g.fontMetrics.stringWidth(label) > right - 28) label = label.dropLast(1)
                if (label != title && label.length > 3) label = label.dropLast(3) + "…"
                g.drawString(label, 17, y + 26)
            }
        } finally { g.dispose() }
    }
}
