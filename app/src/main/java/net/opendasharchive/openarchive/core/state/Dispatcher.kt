package net.opendasharchive.openarchive.core.state

typealias Dispatch<A> = (A) -> Unit

fun interface Dispatcher<Action> {

    fun dispatch(action: Action)
}
