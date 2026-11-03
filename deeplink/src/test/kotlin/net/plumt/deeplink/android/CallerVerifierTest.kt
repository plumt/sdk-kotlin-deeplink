package net.plumt.deeplink.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import net.plumt.deeplink.CallerIdentity
import net.plumt.deeplink.CallerVerification
import net.plumt.deeplink.android.internal.CallerSignals
import net.plumt.deeplink.android.internal.DefaultCallerVerifier
import net.plumt.deeplink.android.internal.SignatureChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 33, 35])
class CallerVerifierTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val partner = TrustedCaller("partner.app", setOf(digest(SIG_A)))
    private fun view(uri: String = "myapp://item/1") = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
    private fun verifier() = CallerVerifier.create(context, listOf(partner))

    @Test fun `OS 가 채운 referrer 와 맞는 서명 - SIGNATURE_VERIFIED`() {
        installPackage("partner.app", arrayOf(SIG_A))
        val activity = launch(view(), systemReferrer = "partner.app")
        assertEquals(CallerIdentity("partner.app", CallerVerification.SIGNATURE_VERIFIED), verifier().identify(activity))
    }

    @Test fun `서명이 다르면 SYSTEM_REPORTED 에 머문다 - 이름만 같은 위장 앱`() {
        installPackage("partner.app", arrayOf(SIG_B))
        val activity = launch(view(), systemReferrer = "partner.app")
        assertEquals(CallerIdentity("partner.app", CallerVerification.SYSTEM_REPORTED), verifier().identify(activity))
    }

    @Test fun `보이지 않는 패키지(가시성·미설치)는 올리지 않는다`() {
        val activity = launch(view(), systemReferrer = "partner.app")
        assertEquals(CallerIdentity("partner.app", CallerVerification.SYSTEM_REPORTED), verifier().identify(activity))
    }

    @Test fun `신뢰 목록 밖 패키지는 서명을 보지 않는다`() {
        installPackage("other.app", arrayOf(SIG_A))
        val activity = launch(view(), systemReferrer = "other.app")
        assertEquals(CallerIdentity("other.app", CallerVerification.SYSTEM_REPORTED), verifier().identify(activity))
    }

    @Test fun `EXTRA_REFERRER 로 주장한 신뢰 앱은 CLAIMED - 서명이 맞아도 올리지 않는다`() {
        installPackage("partner.app", arrayOf(SIG_A))
        val spoof = view().putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://partner.app"))
        val activity = launch(spoof, systemReferrer = "evil.app")
        assertEquals(CallerIdentity("partner.app", CallerVerification.CLAIMED), verifier().identify(activity))
    }

    @Test fun `EXTRA_REFERRER_NAME 문자열 주장도 CLAIMED`() {
        val spoof = view().putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://${context.packageName}")
        val activity = launch(spoof, systemReferrer = "evil.app")
        assertEquals(CallerIdentity(context.packageName, CallerVerification.CLAIMED), verifier().identify(activity))
    }

    @Test fun `http referrer extra 는 발신자 모름`() {
        val browser = view().putExtra(Intent.EXTRA_REFERRER, Uri.parse("https://example.com"))
        assertEquals(CallerIdentity.UNKNOWN, verifier().identify(launch(browser, systemReferrer = "browser.app")))
    }

    @Test fun `referrer 가 없으면 모름`() {
        assertEquals(CallerIdentity.UNKNOWN, verifier().identify(launch(view())))
    }

    @Test fun `자기 앱 - SYSTEM_REPORTED`() {
        val activity = launch(view(), systemReferrer = context.packageName)
        assertEquals(CallerIdentity(context.packageName, CallerVerification.SYSTEM_REPORTED), CallerVerifier.create(context).identify(activity))
    }

    @Test fun `onNewIntent - 이전 인텐트의 referrer extra 가 남아 있으면 CLAIMED`() {
        val first = view().putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://partner.app")
        val activity = launch(first, systemReferrer = "partner.app")
        installPackage("partner.app", arrayOf(SIG_A))
        assertEquals(CallerVerification.CLAIMED, verifier().identify(activity, view("myapp://item/2")).verification)
    }

    @Test fun `onNewIntent - 새 인텐트에만 referrer extra 가 있어도 CLAIMED`() {
        val activity = launch(view(), systemReferrer = "partner.app")
        val next = view("myapp://item/2").putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://partner.app")
        assertEquals(CallerVerification.CLAIMED, verifier().identify(activity, next).verification)
    }

    @Test fun `extras 를 풀 수 없으면 모름으로 낮춘다`() {
        val activity = launch(view(), systemReferrer = "partner.app")
        val throwing = DefaultCallerVerifier(emptyMap(), SignatureChecker(context.packageManager, Build.VERSION.SDK_INT)) { _, _ ->
            throw android.os.BadParcelableException("bad")
        }
        assertEquals(CallerIdentity.UNKNOWN, throwing.identify(activity, activity.intent))
    }

    @Test fun `OS_VERIFIED 도 서명 확인으로 올린다`() {
        installPackage("partner.app", arrayOf(SIG_A))
        val v = DefaultCallerVerifier(
            mapOf("partner.app" to setOf(digest(SIG_A))),
            SignatureChecker(context.packageManager, Build.VERSION.SDK_INT),
        ) { _, _ -> CallerSignals(false, null, "partner.app") }
        val activity = launch(view())
        assertEquals(CallerIdentity("partner.app", CallerVerification.SIGNATURE_VERIFIED), v.identify(activity, activity.intent))
        assertEquals(CallerIdentity.UNKNOWN, v.upgrade(CallerIdentity.UNKNOWN))
        assertEquals(
            CallerIdentity("partner.app", CallerVerification.NONE),
            v.upgrade(CallerIdentity("partner.app", CallerVerification.NONE)),
        )
    }

    @Test fun `create 인자 검사`() {
        assertIae("trustedCallers 에 같은 패키지가 두 번 있다: [partner.app]") {
            CallerVerifier.create(context, listOf(partner, TrustedCaller("partner.app", setOf(digest(SIG_B)))))
        }
    }

    @Test fun `TrustedCaller 정규화와 검증`() {
        val colon = digest(SIG_A).chunked(2).joinToString(":").lowercase()
        assertEquals(setOf(digest(SIG_A)), TrustedCaller("a.b", setOf(colon)).sha256Digests)
        assertEquals("TrustedCaller(a.b, 1 digests)", TrustedCaller("a.b", setOf(colon, digest(SIG_A))).toString())
        assertIae("패키지 이름 형식이 아니다: 'nodot'") { TrustedCaller("nodot", setOf(digest(SIG_A))) }
        assertIae("패키지 이름 형식이 아니다: '1a.b'") { TrustedCaller("1a.b", setOf(digest(SIG_A))) }
        assertIae("sha256Digests 가 비었다: a.b") { TrustedCaller("a.b", emptySet()) }
        assertIae("SHA-256 다이제스트 형식이 아니다(16진 64자): 'abc'") { TrustedCaller("a.b", setOf("abc")) }
        assertIae("SHA-256 다이제스트 형식이 아니다(16진 64자): '${"G".repeat(64)}'") { TrustedCaller("a.b", setOf("G".repeat(64))) }
    }
}

/** 판정 규칙만 순수하게 본다. Intent 를 쓰므로 Robolectric 위에서 돈다. */
@RunWith(RobolectricTestRunner::class)
class CallerSignalsTest {

    @Test fun `판정 행렬`() {
        assertEquals(CallerIdentity("a.b", CallerVerification.OS_VERIFIED), CallerSignals(true, "android-app://x.y", "a.b").decide())
        assertEquals(CallerIdentity("x.y", CallerVerification.SYSTEM_REPORTED), CallerSignals(false, "android-app://x.y", "bad").decide())
        assertEquals(CallerIdentity("x.y", CallerVerification.SYSTEM_REPORTED), CallerSignals(false, "android-app://x.y", null).decide())
        assertEquals(CallerIdentity("x.y", CallerVerification.CLAIMED), CallerSignals(true, "android-app://x.y", null).decide())
        assertEquals(CallerIdentity.UNKNOWN, CallerSignals(false, "https://x.y", null).decide())
        assertEquals(CallerIdentity.UNKNOWN, CallerSignals(true, null, null).decide())
    }

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

    @Test fun `referrer extra 감지`() {
        assertFalse(CallerSignals.hasReferrerExtra(null))
        assertFalse(CallerSignals.hasReferrerExtra(Intent()))
        assertTrue(CallerSignals.hasReferrerExtra(Intent().putExtra(Intent.EXTRA_REFERRER, Uri.parse("x://y"))))
        assertTrue(CallerSignals.hasReferrerExtra(Intent().putExtra(Intent.EXTRA_REFERRER_NAME, "x")))
    }

    @Test fun `API 34 미만 분기는 launchedFrom 을 읽지 않는다`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("myapp://x"))
        val activity: Activity = launch(intent, systemReferrer = "a.b")
        assertEquals(CallerSignals(false, "android-app://a.b", null), CallerSignals.read(activity, activity.intent, 33))
    }

    @Test
    @Config(sdk = [34, 35])
    fun `API 34+ 는 launchedFromPackage 를 먼저 본다 - 신원 공유가 없으면 null`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("myapp://x"))
        val activity: Activity = launch(intent, systemReferrer = "a.b")
        val signals = CallerSignals.read(activity, activity.intent, Build.VERSION.SDK_INT)
        assertEquals("android-app://a.b", signals.referrer)
        // 새 인텐트(onNewIntent)에는 실행 시점 정보를 쓰지 않는다
        assertNull(CallerSignals.read(activity, Intent(intent), Build.VERSION.SDK_INT).launchedFromPackage)
    }
}
