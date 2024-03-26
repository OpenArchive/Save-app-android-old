package net.opendasharchive.openarchive.core.state

import kotlinx.coroutines.flow.StateFlow

interface Stateful<T> {
    val state: StateFlow<T>
}