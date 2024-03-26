package net.opendasharchive.openarchive.features.internetarchive.presentation.details

import com.google.gson.Gson
import net.opendasharchive.openarchive.core.presentation.StatefulViewModel
import net.opendasharchive.openarchive.db.Space
import net.opendasharchive.openarchive.features.internetarchive.domain.model.InternetArchive
import net.opendasharchive.openarchive.features.internetarchive.presentation.details.InternetArchiveDetailsViewModel.Action

class InternetArchiveDetailsViewModel(
    private val gson: Gson,
    private val space: Space
) : StatefulViewModel<InternetArchiveDetailsState, Action>(InternetArchiveDetailsState()) {

    init {
       dispatch(Action.Load(space))
    }

    override fun reduce(state: InternetArchiveDetailsState, action: Action) = when(action) {
        is Action.Loaded -> state.copy(
            userName = action.value.userName,
            email = action.value.email,
            screenName = action.value.screenName
        )
        else -> state
    }

    override suspend fun effects(state: InternetArchiveDetailsState, action: Action) {
        when (action) {
            is Action.Remove -> {
                space.delete()
                notify(action)
            }

            is Action.Load -> {
                val metaData = gson.fromJson(space.metaData, InternetArchive.MetaData::class.java)
                dispatch(Action.Loaded(metaData))
            }

            is Action.Cancel -> notify(action)
            else -> Unit
        }
    }

    sealed interface Action {

        data class Load(val value: Space) : Action

        data class Loaded(val value: InternetArchive.MetaData) : Action

        data object Remove : Action

        data object Cancel : Action
    }
}
