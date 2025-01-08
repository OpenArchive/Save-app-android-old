package net.opendasharchive.openarchive.services.snowbird

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import net.opendasharchive.openarchive.databinding.FragmentSnowbirdShareGroupBinding
import net.opendasharchive.openarchive.db.SnowbirdGroup
import net.opendasharchive.openarchive.extensions.asQRCode
import net.opendasharchive.openarchive.extensions.urlEncode

class SnowbirdShareFragment: BaseSnowbirdFragment() {
    private lateinit var viewBinding: FragmentSnowbirdShareGroupBinding
    private lateinit var groupKey: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            groupKey = it.getString("groupKey", "")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        viewBinding = FragmentSnowbirdShareGroupBinding.inflate(inflater)

        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val group = SnowbirdGroup.get(groupKey)
        val groupName = group?.name ?: "Unknown group"

        viewBinding.groupName.text = groupName

        SnowbirdGroup.get(groupKey)?.uri?.let { uriString ->
            val qrCode = "$uriString&name=${groupName.urlEncode()}".asQRCode(size = 1024)
            viewBinding.qrCode.setImageBitmap(qrCode)
        }
    }
}