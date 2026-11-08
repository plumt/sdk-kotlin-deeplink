package net.plumt.deeplink

import net.plumt.deeplink.internal.AndroidDeepLinks
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import net.plumt.deeplink.CallerIdentity
import net.plumt.deeplink.CallerVerification
import net.plumt.deeplink.internal.CallerSignals
import net.plumt.deeplink.internal.DefaultCallerVerifier
import net.plumt.deeplink.internal.SelfLinkSigner
import net.plumt.deeplink.internal.SignatureChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private const val PARTNER_UID = 10_123
private const val EVIL_UID = 10_666

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 33, 34, 35])
class CallerVerifierTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val partner = TrustedCaller("partner.app", setOf(digest(SIG_A)))
    private fun view(uri: String = "myapp://item/1") = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
    private fun verifier() = CallerVerifier.create(context, listOf(partner))
    private val sdk get() = Build.VERSION.SDK_INT

    // C1 — Activity.referrer(mReferrer)는 위조할 수 있다. 서명이 맞는 진짜 제휴 앱이 설치돼 있어도 올리지 않는다

    @Test fun `C1 referrer 가 제휴 앱이어도 CLAIMED - 서명이 맞아도 FIRST_PARTY 근거가 아니다`() {
        installPackage("partner.app", arrayOf(SIG_A))
        val activity = launch(view(), systemReferrer = "partner.app")
        assertEquals(CallerIdentity("partner.app", CallerVerification.CLAIMED), verifier().identify(activity, activity.intent, true))
    }

    @Test fun `C1 referrer 가 자기 패키지여도 CLAIMED`() {
        val activity = launch(view(), systemReferrer = context.packageName)
        assertEquals(
            CallerIdentity(context.packageName, CallerVerification.CLAIMED),
            CallerVerifier.create(context).identify(activity, activity.intent, true),
        )
    }

    @Test fun `EXTRA_REFERRER 주장도 CLAIMED`() {
        val spoof = view().putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://partner.app"))
        val activity = launch(spoof, systemReferrer = "evil.app")
        assertEquals(CallerIdentity("partner.app", CallerVerification.CLAIMED), verifier().identify(activity, activity.intent, true))
    }

    @Test fun `http referrer 와 referrer 없음은 모름`() {
        val browser = view().putExtra(Intent.EXTRA_REFERRER, Uri.parse("https://example.com"))
        val a = launch(browser, systemReferrer = "browser.app")
        assertEquals(CallerIdentity.UNKNOWN, verifier().identify(a, a.intent, true))
        val b = launch(view())
        assertEquals(CallerIdentity.UNKNOWN, verifier().identify(b, b.intent, true))
    }

    // C1·M5 — Android 14+ 는 OS 가 uid 로 보증한 발신자만 쓴다

    @Test @Config(sdk = [34, 35])
    fun `M5 launchedFromUid 의 패키지와 서명이 맞으면 SIGNATURE_VERIFIED`() {
        installPackage("partner.app", arrayOf(SIG_A))
        setUidPackages(PARTNER_UID, "partner.app")
        val activity = launchFrom(view(), PARTNER_UID, "partner.app")
        assertEquals(CallerIdentity("partner.app", CallerVerification.SIGNATURE_VERIFIED), verifier().identify(activity, activity.intent, true))
    }

    @Test @Config(sdk = [34, 35])
    fun `M5 uid 가 그 패키지를 갖지 않으면 보증이 아니다`() {
        installPackage("partner.app", arrayOf(SIG_A))
        setUidPackages(EVIL_UID, "evil.app")
        val activity = launchFrom(view(), EVIL_UID, "partner.app", systemReferrer = "partner.app")
        assertEquals(CallerIdentity("partner.app", CallerVerification.CLAIMED), verifier().identify(activity, activity.intent, true))
    }

    @Test @Config(sdk = [34, 35])
    fun `M5 신원 공유가 없으면(INVALID_UID) 보증이 아니다`() {
        val activity = launchFrom(view(), Process.INVALID_UID, null)
        assertEquals(CallerIdentity.UNKNOWN, verifier().identify(activity, activity.intent, true))
        val noPkg = launchFrom(view(), PARTNER_UID, null)
        assertEquals(CallerIdentity.UNKNOWN, verifier().identify(noPkg, noPkg.intent, true))
        val badPkg = launchFrom(view(), PARTNER_UID, "nodot")
        assertEquals(CallerIdentity.UNKNOWN, verifier().identify(badPkg, badPkg.intent, true))
    }

    @Test @Config(sdk = [34, 35])
    fun `서명이 다르거나 신뢰 목록 밖이면 OS_VERIFIED 에 머문다`() {
        installPackage("partner.app", arrayOf(SIG_B))
        setUidPackages(PARTNER_UID, "partner.app")
        val a = launchFrom(view(), PARTNER_UID, "partner.app")
        assertEquals(CallerIdentity("partner.app", CallerVerification.OS_VERIFIED), verifier().identify(a, a.intent, true))
        setUidPackages(EVIL_UID, "other.app")
        val b = launchFrom(view(), EVIL_UID, "other.app")
        assertEquals(CallerIdentity("other.app", CallerVerification.OS_VERIFIED), verifier().identify(b, b.intent, true))
    }

    @Test @Config(sdk = [34, 35])
    fun `H2 같은 uid(자기 앱)가 띄운 링크는 OS_VERIFIED 자기 패키지 - SELF 가 아니다`() {
        setUidPackages(Process.myUid(), context.packageName)
        val activity = launchFrom(view(), Process.myUid(), context.packageName)
        assertEquals(
            CallerIdentity(context.packageName, CallerVerification.OS_VERIFIED),
            CallerVerifier.create(context).identify(activity, activity.intent, true),
        )
    }

    // C2 — launchedFrom 은 처음 띄운 앱이다. onNewIntent 로 온 인텐트에 붙이지 않는다

    @Test @Config(sdk = [34, 35])
    fun `C2 처음 실행이 아니면 launchedFrom 을 쓰지 않는다 - setIntent 로 같은 객체여도`() {
        installPackage("partner.app", arrayOf(SIG_A))
        setUidPackages(PARTNER_UID, "partner.app")
        val activity = launchFrom(view(), PARTNER_UID, "partner.app")
        val next = view("myapp://item/2")
        activity.intent = next  // onNewIntent 에서 흔히 하는 setIntent
        assertEquals(CallerIdentity.UNKNOWN, verifier().identify(activity, next, false))
        // 처음 실행이라고 해도 인텐트가 activity.intent 가 아니면 쓰지 않는다
        assertEquals(CallerIdentity.UNKNOWN, verifier().identify(activity, view("myapp://item/3"), true))
    }

    @Test fun `launchedFrom 은 API 34+ 의 처음 실행에서만 읽는다`() {
        val activity = launchFrom(view(), PARTNER_UID, "partner.app", systemReferrer = "a.b")
        assertEquals(CallerSignals("android-app://a.b", null, null), CallerSignals.read(activity, activity.intent, 33, true))
        if (sdk < 34) return
        assertEquals(CallerSignals("android-app://a.b", PARTNER_UID, "partner.app"), CallerSignals.read(activity, activity.intent, sdk, true))
        assertEquals(CallerSignals("android-app://a.b", null, null), CallerSignals.read(activity, activity.intent, sdk, false))
    }

    // H2 — 자기 앱은 스스로 발급한 토큰으로만 SELF_ISSUED

    @Test fun `H2 selfIntent 토큰은 SELF_ISSUED - 처음 실행·onNewIntent 모두`() {
        val intent = AndroidDeepLinks.selfIntent(context, "myapp://notice?id=1")
        val activity = launch(intent, systemReferrer = "evil.app")
        val self = CallerIdentity(context.packageName, CallerVerification.SELF_ISSUED)
        assertEquals(self, verifier().identify(activity, activity.intent, true))
        assertEquals(self, verifier().identify(launch(view()), intent, false))
    }

    @Test fun `H2 토큰을 다른 URI 에 옮기거나 위조하면 SELF 가 아니다`() {
        val token = AndroidDeepLinks.selfIntent(context, "myapp://notice?id=1").dataString!!.substringAfter('#')
        val moved = view("myapp://notice?id=2#$token")
        assertEquals(CallerIdentity.UNKNOWN, verifier().identify(launch(moved), moved, true))
        val forged = view("myapp://notice?id=1#plumt-self=" + "0".repeat(64))
        assertEquals(CallerIdentity.UNKNOWN, verifier().identify(launch(forged), forged, true))
        val short = view("myapp://notice?id=1#plumt-self=abc")
        assertEquals(CallerIdentity.UNKNOWN, verifier().identify(launch(short), short, true))
        val noData = Intent(Intent.ACTION_VIEW)
        assertEquals(CallerIdentity.UNKNOWN, verifier().identify(launch(noData), noData, true))
    }

    @Test fun `extras 를 풀 수 없으면 모름으로 낮춘다`() {
        val activity = launch(view(), systemReferrer = "partner.app")
        val throwing = defaultVerifier { _, _, _ -> throw android.os.BadParcelableException("bad") }
        assertEquals(CallerIdentity.UNKNOWN, throwing.identify(activity, activity.intent, true))
    }

    @Test fun `uid 조회가 SecurityException 이면 보증이 아니다`() {
        val v = defaultVerifier(packagesForUid = { throw SecurityException("no") }) { _, _, _ -> CallerSignals(null, PARTNER_UID, "partner.app") }
        val activity = launch(view())
        assertEquals(CallerIdentity.UNKNOWN, v.identify(activity, activity.intent, true))
        val none = defaultVerifier(packagesForUid = { null }) { _, _, _ -> CallerSignals(null, PARTNER_UID, "partner.app") }
        assertEquals(CallerIdentity.UNKNOWN, none.identify(activity, activity.intent, true))
    }

    // M1 — 키 교체(v3.1)

    @Test fun `M1 옛 인증서는 rotationMinSdk 미만 기기에서만 받는다`() {
        installPackage("partner.app", arrayOf(SIG_A))  // 이 기기가 보고하는 현재 서명 = 옛 키 A
        val rotated = TrustedCaller("partner.app", setOf(digest(SIG_B)), previousSha256Digests = setOf(digest(SIG_A)))
        val v = defaultVerifier(trusted = rotated) { _, _, _ -> CallerSignals(null, PARTNER_UID, "partner.app") }
        val activity = launch(view())
        val expected = if (sdk < 33) CallerVerification.SIGNATURE_VERIFIED else CallerVerification.OS_VERIFIED
        assertEquals(expected, v.identify(activity, activity.intent, true).verification)
        assertEquals(setOf(digest(SIG_B), digest(SIG_A)), rotated.acceptedDigests(32))
        assertEquals(setOf(digest(SIG_B)), rotated.acceptedDigests(33))
        val v28 = TrustedCaller("partner.app", setOf(digest(SIG_B)), setOf(digest(SIG_A)), rotationMinSdk = 28)
        assertEquals(setOf(digest(SIG_B), digest(SIG_A)), v28.acceptedDigests(27))
        assertEquals(setOf(digest(SIG_B)), v28.acceptedDigests(28))
    }

    @Test fun `create 인자 검사`() {
        assertIae("trustedCallers 에 같은 패키지가 두 번 있다: [partner.app]") {
            CallerVerifier.create(context, listOf(partner, TrustedCaller("partner.app", setOf(digest(SIG_B)))))
        }
        assertIae("trustedCallers 에 자기 패키지를 넣을 수 없다: ${context.packageName}") {
            CallerVerifier.create(context, listOf(TrustedCaller(context.packageName, setOf(digest(SIG_A)))))
        }
    }

    @Test fun `TrustedCaller 정규화와 검증`() {
        val colon = digest(SIG_A).chunked(2).joinToString(":").lowercase()
        val caller = TrustedCaller("a.b", setOf(colon, digest(SIG_A)), setOf(digest(SIG_B).lowercase()))
        assertEquals(setOf(digest(SIG_A)), caller.sha256Digests)
        assertEquals(setOf(digest(SIG_B)), caller.previousSha256Digests)
        assertEquals(33, caller.rotationMinSdk)
        assertEquals("TrustedCaller(a.b, 1 digests, 1 previous, rotationMinSdk=33)", caller.toString())
        assertIae("패키지 이름 형식이 아니다: 'nodot'") { TrustedCaller("nodot", setOf(digest(SIG_A))) }
        assertIae("패키지 이름 형식이 아니다: '1a.b'") { TrustedCaller("1a.b", setOf(digest(SIG_A))) }
        assertIae("sha256Digests 가 비었다: a.b") { TrustedCaller("a.b", emptySet()) }
        assertIae("SHA-256 다이제스트 형식이 아니다(16진 64자): 'abc'") { TrustedCaller("a.b", setOf("abc")) }
        assertIae("SHA-256 다이제스트 형식이 아니다(16진 64자): 'xyz'") { TrustedCaller("a.b", setOf(digest(SIG_A)), setOf("xyz")) }
        assertIae("SHA-256 다이제스트 형식이 아니다(16진 64자): '${"G".repeat(64)}'") { TrustedCaller("a.b", setOf("G".repeat(64))) }
        assertIae("rotationMinSdk 는 28 이상이어야 한다: 27") { TrustedCaller("a.b", setOf(digest(SIG_A)), emptySet(), 27) }
        TrustedCaller("a.b", setOf(digest(SIG_A)), emptySet(), 28)
    }

    private fun defaultVerifier(
        trusted: TrustedCaller = partner,
        packagesForUid: (Int) -> Array<String>? = { arrayOf("partner.app") },
        readSignals: (Activity, Intent, Boolean) -> CallerSignals,
    ) = DefaultCallerVerifier(
        selfPackage = context.packageName,
        trusted = mapOf(trusted.packageName to trusted),
        sdkInt = sdk,
        signatures = SignatureChecker(context.packageManager, sdk),
        selfLinks = SelfLinkSigner(File(context.noBackupFilesDir, "test.key")),
        packagesForUid = packagesForUid,
        readSignals = readSignals,
    )
}

/** 판정 재료만 순수하게 본다. Intent 를 쓰므로 Robolectric 위에서 돈다. */
@RunWith(RobolectricTestRunner::class)
class CallerSignalsTest {

    @Test fun `referrer 에서 패키지 뽑기`() {
        assertEquals("com.example.app", CallerSignals.packageFromReferrer("android-app://com.example.app"))
        assertEquals("com.example.app", CallerSignals.packageFromReferrer("ANDROID-APP://com.example.app/https/host"))
        assertNull(CallerSignals.packageFromReferrer(null))
        assertNull(CallerSignals.packageFromReferrer(""))
        assertNull(CallerSignals.packageFromReferrer("android-app:/"))
        assertNull(CallerSignals.packageFromReferrer("android-app://"))
        assertNull(CallerSignals.packageFromReferrer("android-app:///x"))
        assertNull(CallerSignals.packageFromReferrer("https://com.example.app"))
        assertNull(CallerSignals.packageFromReferrer("android-app://nodot"))
        assertNull(CallerSignals.packageFromReferrer("android-app://a.b@c.d"))
        assertNull(CallerSignals.packageFromReferrer("android-app://a..b"))
        assertNull(CallerSignals.packageFromReferrer("android-app://" + "a.".repeat(130) + "b"))
    }

    @Test fun `패키지 이름 규칙`() {
        assertTrue(CallerSignals.isPackageName("a.b"))
        assertTrue(CallerSignals.isPackageName("A_1.b_2.C"))
        assertFalse(CallerSignals.isPackageName("a"))
        assertFalse(CallerSignals.isPackageName("a.1b"))
        assertFalse(CallerSignals.isPackageName("a.b."))
        assertFalse(CallerSignals.isPackageName("a-b.c"))
        assertTrue(CallerSignals.isPackageName("a." + "b".repeat(253)))
        assertFalse(CallerSignals.isPackageName("a." + "b".repeat(254)))
    }
}
