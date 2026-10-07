package androidx.lifecycle

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

open class ViewModel {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    fun clearForTest() = scope.cancel()
}

open class AndroidViewModel(private val app: Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    fun <T : Application> getApplication(): T = app as T
}

val ViewModel.viewModelScope: CoroutineScope get() = scope
