package net.opendasharchive.openarchive.core.state


typealias Effects<T, A> = suspend (T, A) -> Unit