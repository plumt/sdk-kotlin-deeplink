package net.plumt.deeplink.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import org.robolectric.util.ReflectionHelpers
import java.security.MessageDigest

internal val SIG_A = Signature(ByteArray(64) { 1 })
internal val SIG_B = Signature(ByteArray(64) { 2 })
internal val SIG_C = Signature(ByteArray(64) { 3 })

internal fun digest(sig: Signature): String =
    MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02X".format(it.toInt() and 0xFF) }

internal fun assertIae(expectedMessage: String?, block: () -> Unit) {
    val e = runCatching(block).exceptionOrNull()
    assertTrue("IllegalArgumentException 이어야 한다: $e", e is IllegalArgumentException)
    if (expectedMessage != null) assertEquals(expectedMessage, e!!.message)
}

/** 가짜 PackageManager 에 패키지를 설치한다. 28+ 에서는 SigningInfo 도 채운다. */
internal fun installPackage(packageName: String, signers: Array<Signature>, past: Array<Signature>? = null) {
    val info = PackageInfo().apply {
        this.packageName = packageName
        @Suppress("DEPRECATION")
        signatures = signers
    }
    if (Build.VERSION.SDK_INT >= 28) info.signingInfo = signingInfo(signers, past)
    shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().packageManager).installPackage(info)
}

private fun signingInfo(signers: Array<Signature>, past: Array<Signature>?): SigningInfo {
    val info = Shadow.newInstanceOf(SigningInfo::class.java)
    val shadow = Shadow.extract<ShadowSigningInfo>(info)
    shadow.setSignatures(signers)
    if (past != null) shadow.setPastSigningCertificates(past)
    return info
}

/** [intent] 로 띄운 Activity. [systemReferrer] 는 OS 가 채우는 발신 패키지(mReferrer)를 흉내 낸다. */
internal fun launch(intent: Intent, systemReferrer: String? = null): Activity {
    val activity = Robolectric.buildActivity(Activity::class.java, intent).create().get()
    ReflectionHelpers.setField(activity, "mReferrer", systemReferrer)
    return activity
}
