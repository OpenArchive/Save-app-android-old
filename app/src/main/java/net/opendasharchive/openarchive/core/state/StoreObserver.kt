package net.opendasharchive.openarchive.core.state

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class StoreObserver<T> : Notifier<T>, Listener<T> {
    private val _actions = Channel<T>()
    override val actions = _actions.receiveAsFlow()

    override suspend fun notify(action: T) {
        _actions.send(action)
    }
}
