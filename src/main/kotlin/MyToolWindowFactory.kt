package shoaku

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab(MyMessageBundle.message("toolwindow.stripe.MyToolWindow"), focusOnClickInside = true) {
            LaunchedEffect(Unit) {
                // initial data loading
            }
            MyToolWindowContent(project)
        }
    }
}

@Composable
private fun MyToolWindowContent(project: Project? = null) {
    val state = project?.service<ShoakuSettings>()?.state
    val filePathState = rememberTextFieldState(initialText = state?.filePath ?: "")

    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(MyMessageBundle.message("toolwindow.MyToolWindow.filePath.label"))
            TextField(
                state = filePathState,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = {
                if (project != null) {
                    val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                    FileChooser.chooseFile(descriptor, project, null) { virtualFile ->
                        filePathState.setTextAndPlaceCursorAtEnd(virtualFile.path)
                    }
                }
            }) {
                Text(MyMessageBundle.message("toolwindow.MyToolWindow.filePath.browse.button"))
            }
        }
    }

    LaunchedEffect(filePathState.text) {
        state?.filePath = filePathState.text as String
    }
}

@Composable
@Preview
fun MyToolWindowContentPreview() {
    MyToolWindowContent()
}
