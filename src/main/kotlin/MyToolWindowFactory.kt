package shoaku

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.platform.lsp.api.LspServerManager
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
            MyToolWindowContent(
                project.service<ShoakuSettings>().viewModel,
                project.service<ShoakuSettings>().state,
                project
            )
        }
    }
}

@Composable
private fun MyToolWindowContent(viewModel: ShoakuViewModel, state: ShoakuSettings.State, project: Project? = null) {
    val filePathState = rememberTextFieldState(initialText = state.filePath)
    val vm = remember { viewModel }
    val instructionState = rememberTextFieldState()

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

        Text(
            text = vm.items.firstOrNull()?.content ?: "Not Found",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(vm.items.firstOrNull()?.children ?: emptyList()) { model ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (model.checked ?: false) Color(0xFF1E1E2A) else Color(0xFF2D2D3F),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = model.status ?: ""
                        )
                        Text(model.content)
                        Text(model.response ?: "")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextField(
                state = instructionState,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Input...") }
            )
            OutlinedButton(onClick = {
                val instruction = instructionState.text.toString()
                if (instruction.isNotBlank()) {
                    project?.let {
                        LspServerManager.getInstance(project)
                            .getServersForProvider(LanguageServerProvider::class.java)
                            .first()
                            .sendNotification { (it as AppLanguageServer).reply(ReplyParams(instruction)) }
                    }

                    instructionState.setTextAndPlaceCursorAtEnd("")
                }
            }) {
                Text("Send")
            }
        }
    }

    LaunchedEffect(filePathState.text) {
        state.filePath = filePathState.text as String
    }
}

@Composable
@Preview
fun MyToolWindowContentPreview() {
    MyToolWindowContent(ShoakuViewModel().apply {
        items = listOf(
            Item(
                "", "aaa", children = listOf(
                    Item("", "bbb?", checked = true),
                    Item("", "ccc?", checked = false, status = "In Progress")
                )
            )
        )
    }, ShoakuSettings.State())
}
