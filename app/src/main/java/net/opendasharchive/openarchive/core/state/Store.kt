package net.opendasharchive.openarchive.core.state

interface Store<Action> : Dispatcher<Action>, Listener<Action>, Notifier<Action> {

    operator fun invoke(action: Action) = dispatch(action)
}