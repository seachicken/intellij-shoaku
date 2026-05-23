package shoaku

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.openapi.components.*

@Service
@State(name = "shoaku-project", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ShoakuSettings : PersistentStateComponent<ShoakuSettings.State> {
    private var settings = State()
    var viewModel: ShoakuViewModel = ShoakuViewModel()

    override fun getState() = settings

    override fun loadState(settings: State) {
        this.settings = settings
    }

    data class State(
        var filePath: String = ""
    )
}

class ShoakuViewModel {
    var items by mutableStateOf<List<Item>>(emptyList())
    var response by mutableStateOf("")
}
