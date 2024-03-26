package net.opendasharchive.openarchive.core.state

import kotlinx.coroutines.flow.Flow

interface Listener<Action> {
    val actions: Flow<Action>
}