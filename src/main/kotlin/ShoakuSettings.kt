package shoaku

import com.intellij.openapi.components.*

@Service
@State(name = "shoaku-project", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ShoakuSettings : PersistentStateComponent<ShoakuSettings.State> {
    private var settings = State()

    override fun getState() = settings

    override fun loadState(settings: State) {
        this.settings = settings
    }

    data class State(
        var filePath: String = ""
    )
}
