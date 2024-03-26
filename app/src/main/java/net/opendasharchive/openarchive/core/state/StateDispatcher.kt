package net.opendasharchive.openarchive.core.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StateDispatcher<T, A>(
    private val scope: CoroutineScope,
    initialState: T,
    private val reducer: Reducer<T, A>,
    private val effects: Effects<T, A>
) : Dispatcher<A>, Stateful<T> {
    private val _state = MutableStateFlow(initialState)

    override val state = _state.asStateFlow()

    override fun dispatch(action: A) {
        val state = _state.apply(action, reducer)
        scope.launch(Dispatchers.Default) {
            effects(state, action)
        }
    }
}
