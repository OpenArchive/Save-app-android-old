package net.opendasharchive.openarchive.core.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.opendasharchive.openarchive.core.state.StateDispatcher
import net.opendasharchive.openarchive.core.state.StoreObserver
import net.opendasharchive.openarchive.core.state.Stateful
import net.opendasharchive.openarchive.core.state.Store

abstract class StatefulViewModel<State, Action>(
    initialState: State,
) : ViewModel(), Store<Action>, Stateful<State> {

    private val dispatcher =
        StateDispatcher(viewModelScope, initialState, ::reduce, ::effects)

    private val observer = StoreObserver<Action>()

    override val state = dispatcher.state
    override val actions = observer.actions

    abstract fun reduce(state: State, action: Action): State

    abstract suspend fun effects(state: State, action: Action)

    override fun dispatch(action: Action) = dispatcher.dispatch(action)

    override suspend fun notify(action: Action) = observer.notify(action)
}
