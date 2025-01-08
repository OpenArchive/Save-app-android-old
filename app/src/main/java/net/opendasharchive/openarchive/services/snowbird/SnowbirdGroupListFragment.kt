package net.opendasharchive.openarchive.services.snowbird

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.databinding.FragmentSnowbirdListGroupsBinding
import net.opendasharchive.openarchive.db.SnowbirdError
import net.opendasharchive.openarchive.db.SnowbirdGroup
import net.opendasharchive.openarchive.services.CommonServiceFragment.Companion.RESP_CREATED
import net.opendasharchive.openarchive.util.SpacingItemDecoration
import net.opendasharchive.openarchive.util.Utility
import timber.log.Timber

class SnowbirdGroupListFragment : BaseSnowbirdFragment() {

    private lateinit var viewBinding: FragmentSnowbirdListGroupsBinding
    private lateinit var adapter: SnowbirdGroupsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        viewBinding = FragmentSnowbirdListGroupsBinding.inflate(inflater)

        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupMenu()
        setupSwipeRefresh()
        setupRecyclerView()
        initializeViewModelObservers()

        snowbirdGroupViewModel.fetchGroups()
    }

    private fun setupSwipeRefresh() {
        viewBinding.swipeRefreshLayout.setOnRefreshListener {
            snowbirdGroupViewModel.fetchGroups(true)
        }

        viewBinding.swipeRefreshLayout.setColorSchemeResources(
            R.color.colorPrimary,
            R.color.colorPrimaryDark
        )
    }

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_snowbird, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_add -> {
                        findNavController().navigate(SnowbirdGroupListFragmentDirections.navigateToSnowbirdCreateGroupScreen())
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun setupRecyclerView() {
        adapter = SnowbirdGroupsAdapter(
            onClickListener = { groupKey -> onClick(groupKey) },
            onLongPressListener = { groupKey -> onLongPress(groupKey) }
        )

        val spacingInPixels = resources.getDimensionPixelSize(R.dimen.list_item_spacing)
        viewBinding.groupList.addItemDecoration(SpacingItemDecoration(spacingInPixels))

        viewBinding.groupList.layoutManager = LinearLayoutManager(requireContext())
        viewBinding.groupList.adapter = adapter

        viewBinding.groupList.setEmptyView(R.layout.view_empty_state)
    }

    private fun onClick(groupKey: String) {
        findNavController().navigate(SnowbirdGroupListFragmentDirections.navigateToSnowbirdListReposScreen(groupKey))
    }

    private fun onLongPress(groupKey: String) {
        Timber.d("Lomg press!")
        Utility.showMaterialPrompt(
            requireContext(),
            title = "Share Group",
            message = "Would you like to share this group?",
            positiveButtonText = "Yes",
            negativeButtonText = "No") { affirm ->
            if (affirm) {
                findNavController().navigate(SnowbirdGroupListFragmentDirections.navigateToSnowbirdShareScreen(groupKey))
            }
        }
    }

    private fun initializeViewModelObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { snowbirdGroupViewModel.groupState.collect { state -> handleGroupStateUpdate(state) } }
            }
        }
    }

    override fun handleError(error: SnowbirdError) {
        handleLoadingStatus(false)
        viewBinding.swipeRefreshLayout.isRefreshing = false
        super.handleError(error)
    }

    private fun handleGroupStateUpdate(state: SnowbirdGroupViewModel.GroupState) {
        when (state) {
            is SnowbirdGroupViewModel.GroupState.Loading -> onLoading()
            is SnowbirdGroupViewModel.GroupState.MultiGroupSuccess -> onGroupsFetched(state.groups, state.isRefresh)
            is SnowbirdGroupViewModel.GroupState.Error -> handleError(state.error)
            else -> Unit
        }
    }

    private fun onGroupsFetched(groups: List<SnowbirdGroup>, isRefresh: Boolean) {
        handleLoadingStatus(false)

        if (isRefresh) {
            Timber.d("Clearing SnowbirdGroups")
            SnowbirdGroup.clear()
            saveGroups(groups)
        }

        adapter.submitList(groups)
    }

    private fun onLoading() {
        handleLoadingStatus(true)
        viewBinding.swipeRefreshLayout.isRefreshing = false
    }

    private fun saveGroups(groups: List<SnowbirdGroup>) {
        groups.forEach { group ->
            group.save()
        }
    }

    private fun showSuccess() {
        Utility.showMaterialMessage(
            requireContext(),
            "Woo!",
            "New backend was successfully created."
        ) {
            setFragmentResult(RESP_CREATED, bundleOf())
        }
    }

//    override fun groupSelected(group: SnowbirdGroup) {
//        showSuccess()
//    }
}