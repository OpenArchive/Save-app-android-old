package net.opendasharchive.openarchive.services.gdrive

import android.accounts.Account
import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.SignInClient
import com.google.android.gms.auth.api.identity.SignInCredential
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.databinding.FragmentGdriveBinding
import net.opendasharchive.openarchive.db.Space
import timber.log.Timber
import java.util.Collections

class GDriveFragment : Fragment() {

    private lateinit var mBinding: FragmentGdriveBinding

    private lateinit var oneTapClient: SignInClient
    private lateinit var signInRequest: BeginSignInRequest
    private lateinit var legacyClient: GoogleSignInClient

    private val legacyLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            processLegacySignIn(it.data)
        }

    private val oneTapLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            processOneTapSignIn(it.data)
        }

    companion object {
        const val RESP_CANCEL = "gdrive_fragment_resp_cancel"
        const val RESP_AUTHENTICATED = "gdrive_fragment_resp_authenticated"
        const val REQUEST_CODE_GOOGLE_AUTH = 21701
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mBinding = FragmentGdriveBinding.inflate(inflater)

        mBinding.disclaimer1.text = HtmlCompat.fromHtml(
            getString(
                R.string.gdrive_disclaimer_1,
                getString(R.string.app_name),
                getString(R.string.google_name),
                getString(R.string.gdrive_sudp_name),
            ), HtmlCompat.FROM_HTML_MODE_COMPACT
        )
        mBinding.disclaimer1.movementMethod = LinkMovementMethod.getInstance()
        mBinding.disclaimer2.text = getString(
            R.string.gdrive_disclaimer_2,
            getString(R.string.google_name),
            getString(R.string.gdrive),
            getString(R.string.app_name),
        )
        mBinding.error.visibility = View.GONE

        mBinding.btBack.setOnClickListener {
            setFragmentResult(RESP_CANCEL, bundleOf())
        }

        mBinding.btAuthenticate.setOnClickListener {
            mBinding.error.visibility = View.GONE
            startOneTapSignIn()
            // authenticate()
            mBinding.btBack.isEnabled = false
            mBinding.btAuthenticate.isEnabled = false
        }

        prepareOneTapSignIn()
//        prepareLegacySignIn()

        return mBinding.root
    }

    private fun authenticate() {
        if (!GDriveConduit.permissionsGranted(requireContext())) {
            GoogleSignIn.requestPermissions(
                requireActivity(),
                REQUEST_CODE_GOOGLE_AUTH,
                GoogleSignIn.getLastSignedInAccount(requireActivity()),
                *GDriveConduit.SCOPES
            )
        } else {
            // permission was already granted, we're already signed in, continue.
            setFragmentResult(RESP_AUTHENTICATED, bundleOf())
        }
    }

//    @Deprecated("Deprecated in Java")
//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
//
//        Timber.d("Auth cancelled")
//
//        if (requestCode == REQUEST_CODE_GOOGLE_AUTH) {
//            when (resultCode) {
//                RESULT_CANCELED -> {
//                    mBinding.btBack.isEnabled = true
//                    mBinding.btAuthenticate.isEnabled = true
//                    authFailed(
//                        "Cancelled?!"
//                    )
//                }
//
//                RESULT_OK -> {
//                    CoroutineScope(Dispatchers.IO).launch {
//                        val space = Space(Space.Type.GDRIVE)
//                        // we don't really know the host here, that's hidden by Drive Api
//                        space.host = "what's the host of google drive? :shrug:"
//                        data?.let {
//                            val result = Auth.GoogleSignInApi.getSignInResultFromIntent(it)
//                            if (result?.isSuccess == true) {
//                                result.signInAccount?.let { account ->
//                                    space.displayname = account.email ?: ""
//                                }
//                            }
//                        }
//
//                        if (GDriveConduit.permissionsGranted(requireContext())) {
//                            space.save()
//                            Space.current = space
//
//                            MainScope().launch {
//                                setFragmentResult(RESP_AUTHENTICATED, bundleOf())
//                            }
//                        } else {
//                            authFailed(
//                                getString(
//                                    R.string.gdrive_auth_insufficient_permissions,
//                                    getString(R.string.app_name),
//                                    getString(R.string.gdrive)
//                                )
//                            )
//                        }
//                    }
//                }
//
//                else -> authFailed()
//            }
//        }
//    }

    private fun processOneTapSignIn(data: Intent?) {
        try {
            val oneTapCredential: SignInCredential = oneTapClient.getSignInCredentialFromIntent(data)

            Timber.d("Signed in as " + oneTapCredential.displayName)

            GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE_APPDATA)
            ).setSelectedAccount(Account(oneTapCredential.id, activity?.packageName ?: ""))

            CoroutineScope(Dispatchers.IO).launch {
                val space = Space(Space.Type.GDRIVE)
                space.displayname = "GDrive"
                space.save()

                Space.current = space

                MainScope().launch {
                    setFragmentResult(RESP_AUTHENTICATED, bundleOf())
                }
            }
        } catch (e: ApiException) {
            Timber.e("Credentials API error", e)
        }
    }

    private fun prepareLegacySignIn() {
        Timber.d("Preparing legacy SignIn")
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        legacyClient = activity?.let { GoogleSignIn.getClient(it, signInOptions) }!!
    }

    private fun prepareOneTapSignIn() {
        oneTapClient = activity?.let { Identity.getSignInClient(it) }!!
        signInRequest = BeginSignInRequest.builder()
            .setGoogleIdTokenRequestOptions(
                BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                    .setSupported(true)
                    // Your server's client ID, not your Android client ID.
                    .setServerClientId(getString(R.string.web_client_id))
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            )
            // Automatically sign in when exactly one credential is retrieved.
            .setAutoSelectEnabled(true)
            .build()
    }

    private fun processLegacySignIn(data: Intent?) {
        if (data == null) return
        GoogleSignIn.getSignedInAccountFromIntent(data)
            .addOnSuccessListener { googleAccount: GoogleSignInAccount ->
                Timber.d("Signed in as " + googleAccount.email)
                val account = googleAccount.account
            }
            .addOnFailureListener { exception: Exception? ->
                Timber.e("Unable to sign in.", exception)
            }
    }

    private fun startOneTapSignIn() {
        activity?.let {
            oneTapClient.beginSignIn(signInRequest).addOnSuccessListener(it) { result ->
                try {
                    oneTapLauncher.launch(
                        IntentSenderRequest.Builder(result.pendingIntent.intentSender).build()
                    )
                } catch (e: IntentSender.SendIntentException) {
                    Timber.e("Couldn't start One Tap UI: ${e.localizedMessage}")
                } catch (e: ActivityNotFoundException) {
                    Timber.e("Couldn't start One Tap UI: ${e.localizedMessage}")
                }
                Timber.d("SignIn started")
            }.addOnFailureListener(it) { e ->
                Timber.d("SignIn failed", e)
            }
        }
    }

    private fun startLegacySignIn() {
        legacyLauncher.launch(legacyClient.signInIntent)
    }

    private fun authFailed() {
        authFailed(null)
    }

    private fun authFailed(errorMessage: String?) {
        MainScope().launch {
            errorMessage?.let {
                mBinding.error.text = errorMessage
                mBinding.error.visibility = View.VISIBLE
            }
            mBinding.btBack.isEnabled = true
            mBinding.btAuthenticate.isEnabled = true
        }
    }
}