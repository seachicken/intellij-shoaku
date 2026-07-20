package shoaku

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.openapi.components.*
import shoaku.presentation.ReviewLocation

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
    var goalFilter by mutableStateOf(GoalFilter.All)
    var selectedReviewLocation by mutableStateOf<ReviewLocation?>(null)
    val diffResponses = mutableStateMapOf<String, ShowDiffParams>()
    val tokenBudgetOverrides = mutableStateMapOf<String, Int>()
}

enum class GoalFilter(val label: String) {
    All("All"),
    Open("Open");

    fun matches(item: Item): Boolean = when (this) {
        All -> true
        Open -> item.checked != true
    }
}
