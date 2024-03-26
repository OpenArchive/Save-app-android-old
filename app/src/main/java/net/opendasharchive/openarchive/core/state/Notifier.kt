package net.opendasharchive.openarchive.core.state


typealias Notify<A> = suspend (A) -> Unit

fun interface Notifier<Action> {
    suspend fun notify(action: Action)
}