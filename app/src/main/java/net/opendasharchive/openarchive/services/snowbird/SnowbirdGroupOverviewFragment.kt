package net.opendasharchive.openarchive.services.snowbird

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import net.opendasharchive.openarchive.databinding.FragmentSnowbirdGroupOverviewBinding

class SnowbirdGroupOverviewFragment: BaseSnowbirdFragment() {
    private lateinit var viewBinding: FragmentSnowbirdGroupOverviewBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        viewBinding = FragmentSnowbirdGroupOverviewBinding.inflate(inflater)

        return viewBinding.root
    }
}