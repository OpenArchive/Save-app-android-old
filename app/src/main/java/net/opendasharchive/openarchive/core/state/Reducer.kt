package net.opendasharchive.openarchive.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

typealias Reducer<T, A> = (T, A) -> T

fun <T, A> MutableStateFlow<T>.apply(action: A, reducer: Reducer<T, A>) =
    updateAndGet { reducer(it, action) }