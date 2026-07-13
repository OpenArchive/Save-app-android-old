package net.opendasharchive.openarchive.util

import android.content.Context
import java.io.File

object SafetyNetHelper {
    // Play Integrity API requires GMS — not available on FOSS builds.
    fun requestGst(context: Context, mediaHash: String, outFile: File) = Unit
}
