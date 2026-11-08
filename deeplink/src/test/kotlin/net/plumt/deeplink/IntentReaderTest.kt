package net.plumt.deeplink

import net.plumt.deeplink.internal.AndroidDeepLinks
import net.plumt.deeplink.internal.DeepLinkIntentReader
import net.plumt.deeplink.internal.SafeExtras
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.plumt.deeplink.CallerIdentity
import net.plumt.deeplink.CallerVerification
import net.plumt.deeplink.DeepLinkOutcome
import net.plumt.deeplink.DeepLinkRoute
import net.plumt.deeplink.Rejection
import net.plumt.deeplink.TrustLevel
import net.plumt.deeplink.deepLinkHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 33, 35])
class IntentReaderTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val reader get() = DeepLinkIntentReader.create(context)
    private fun view(uri: String = "myapp://item/1") = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    @Test fun `딥링크가 아닌 인텐트`() {
        val activity = launch(view())
        assertEquals(IntentReadResult.NotDeepLink, reader.readNewIntent(activity, Intent(Intent.ACTION_MAIN, Uri.parse("myapp://x"))))
        assertEquals(IntentReadResult.NotDeepLink, reader.readNewIntent(activity, Intent(Intent.ACTION_VIEW)))
        assertEquals(IntentReadResult.NotDeepLink, reader.readNewIntent(activity, Intent()))
        assertEquals(IntentReadResult.NotDeepLink, reader.readLaunch(launch(Intent(Intent.ACTION_MAIN)), null))
    }

    @Test fun `최근 앱에서 다시 연 실행은 링크가 아니다`() {
        val intent = view().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertEquals(IntentReadResult.NotDeepLink, reader.readLaunch(launch(intent), null))
        // onNewIntent 는 플랫폼이 다시 주는 실행이 아니다
        assertTrue(reader.readNewIntent(launch(view()), intent) is IntentReadResult.Link)
    }

    @Test fun `data 와 발신자만 꺼낸다`() {
        val activity = launch(view("myapp://item/7?t=secret").putExtra("redirect", Intent()), systemReferrer = context.packageName)
        val link = reader.readLaunch(activity, null) as IntentReadResult.Link
        assertEquals("myapp://item/7?t=secret", link.request.uri)
        // C1 — 위조할 수 있는 referrer 는 자기 패키지여도 CLAIMED
        assertEquals(CallerIdentity(context.packageName, CallerVerification.CLAIMED), link.request.caller)
    }

    @Test fun `셀렉터가 붙은 인텐트는 Unsafe`() {
        val intent = view().apply { selector = Intent(Intent.ACTION_MAIN).setComponent(ComponentName("a.b", "a.b.C")) }
        assertEquals(IntentReadResult.Unsafe(UnsafeIntentReason.HAS_SELECTOR), reader.readNewIntent(launch(view()), intent))
    }

    @Test fun `풀 수 없는 extras 는 Unsafe`() {
        val hostile = object : Intent(ACTION_VIEW, Uri.parse("myapp://item/1")) {
            override fun getExtras(): Bundle = throw BadParcelableException("bad")
        }
        assertEquals(IntentReadResult.Unsafe(UnsafeIntentReason.UNREADABLE_EXTRAS), reader.readNewIntent(launch(view()), hostile))
    }

    @Test fun `허용 액션 지정과 사용자 판정기`() {
        val fixed = CallerIdentity("a.b", CallerVerification.OS_VERIFIED)
        val custom = DeepLinkIntentReader.create(object : CallerVerifier {
            override fun identify(activity: android.app.Activity, intent: Intent, initialLaunch: Boolean) = fixed
        }, setOf("my.action.OPEN"))
        val activity = launch(view())
        assertEquals(IntentReadResult.NotDeepLink, custom.readNewIntent(activity, view()))
        val read = custom.readNewIntent(activity, Intent("my.action.OPEN", Uri.parse("myapp://x"))) as IntentReadResult.Link
        assertEquals(fixed, read.request.caller)
        assertIae("allowedActions 가 비었다") { DeepLinkIntentReader.create(context, allowedActions = emptySet()) }
        DeepLinkIntentReader.create(context, listOf(TrustedCaller("a.b", setOf(digest(SIG_A)))))
    }

    @Test fun `인텐트부터 라우터까지 - SELF 경로는 자기 앱만`() = runBlocking {
        val routed = mutableListOf<String>()
        val handler = deepLinkHandler {
            route(DeepLinkRoute.builder("notice", "myapp://notice").requiredTrust(TrustLevel.SELF).build())
            trustPolicy(AndroidDeepLinks.trustPolicy(context))
            router { routed += it.route.id; true }
        }
        val denied = DeepLinkOutcome.Rejected(Rejection.InsufficientTrust("notice", TrustLevel.SELF, TrustLevel.PUBLIC))
        // 자기 앱이 발급한 링크 — 처음 실행과 onNewIntent 모두 SELF, 토큰은 떼고 넘긴다
        val issued = AndroidDeepLinks.selfIntent(context, "myapp://notice")
        val own = reader.readLaunch(launch(issued), null) as IntentReadResult.Link
        assertEquals("myapp://notice", own.request.uri)
        assertTrue(handler.handle(own.request) is DeepLinkOutcome.Handled)
        val ownNew = reader.readNewIntent(launch(view()), issued) as IntentReadResult.Link
        assertTrue(handler.handle(ownNew.request) is DeepLinkOutcome.Handled)
        // C1 — OS referrer 를 자기 패키지로 위조
        val forgedReferrer = reader.readLaunch(launch(view("myapp://notice"), systemReferrer = context.packageName), null) as IntentReadResult.Link
        assertEquals(denied, handler.handle(forgedReferrer.request))
        // EXTRA_REFERRER 로 주장
        val spoof = view("myapp://notice").putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://${context.packageName}")
        val claimed = reader.readLaunch(launch(spoof, systemReferrer = "evil.app"), null) as IntentReadResult.Link
        assertEquals(denied, handler.handle(claimed.request))
        // 위조 토큰 — 토큰 프래그먼트는 떼지만 SELF 는 아니다
        val fake = reader.readNewIntent(launch(view()), view("myapp://notice#plumt-self=" + "a".repeat(64))) as IntentReadResult.Link
        assertEquals("myapp://notice", fake.request.uri)
        assertEquals(denied, handler.handle(fake.request))
        assertEquals(listOf("notice", "notice"), routed)
    }

    @Test fun `무작위 인텐트 5천 건 - 예외 없이 결과`() {
        val r = Random(1102)
        val activity = launch(view())
        val actions = listOf(Intent.ACTION_VIEW, Intent.ACTION_MAIN, null, "x")
        val datas = listOf("myapp://item/1", "", "%%%", "https://example.com/a?b=%ZZ", "\u0000", "a".repeat(5000), null)
        repeat(5_000) {
            val intent = Intent(actions[r.nextInt(actions.size)])
            datas[r.nextInt(datas.size)]?.let { intent.data = Uri.parse(it) }
            when (r.nextInt(6)) {
                0 -> intent.putExtra(Intent.EXTRA_REFERRER, Uri.parse(datas[r.nextInt(datas.size - 1)]))
                1 -> intent.putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://" + "x".repeat(r.nextInt(300)))
                2 -> intent.putExtra("nested", Intent(Intent.ACTION_VIEW))
                3 -> intent.putExtra("bundle", Bundle().apply { putInt("k", r.nextInt()) })
                4 -> intent.selector = Intent(Intent.ACTION_MAIN)
                else -> intent.putExtra("n", r.nextLong())
            }
            val result = reader.readNewIntent(activity, intent)
            assertTrue(result is IntentReadResult.Link || result is IntentReadResult.NotDeepLink || result is IntentReadResult.Unsafe)
            SafeExtras.string(intent, "nested")
            SafeExtras.long(intent, "n")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 33, 35])
class SafeExtrasTest {

    private val intent = Intent()
        .putExtra("s", "hello")
        .putExtra("long", "x".repeat(SafeExtras.DEFAULT_MAX_LENGTH + 1))
        .putExtra("i", 7)
        .putExtra("l", 8L)
        .putExtra("b", true)
        .putExtra("nested", Intent(Intent.ACTION_VIEW))
        .putExtra("nul", null as String?)

    @Test fun `문자열`() {
        assertEquals(ExtraRead.Present("hello"), SafeExtras.string(intent, "s"))
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.TOO_LONG), SafeExtras.string(intent, "s", maxLength = 4))
        assertEquals(ExtraRead.Present("hello"), SafeExtras.string(intent, "s", maxLength = 5))
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.TOO_LONG), SafeExtras.string(intent, "long"))
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.WRONG_TYPE), SafeExtras.string(intent, "i"))
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.WRONG_TYPE), SafeExtras.string(intent, "nested"))
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.WRONG_TYPE), SafeExtras.string(intent, "nul"))
        assertEquals(ExtraRead.Absent, SafeExtras.string(intent, "missing"))
        assertEquals(ExtraRead.Absent, SafeExtras.string(Intent(), "s"))
        assertIae("maxLength 는 1 이상이어야 한다: 0") { SafeExtras.string(intent, "s", 0) }
    }

    @Test fun `원시 타입`() {
        assertEquals(ExtraRead.Present(7), SafeExtras.int(intent, "i"))
        assertEquals(ExtraRead.Present(8L), SafeExtras.long(intent, "l"))
        assertEquals(ExtraRead.Present(true), SafeExtras.boolean(intent, "b"))
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.WRONG_TYPE), SafeExtras.int(intent, "l"))
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.WRONG_TYPE), SafeExtras.long(intent, "i"))
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.WRONG_TYPE), SafeExtras.boolean(intent, "s"))
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.WRONG_TYPE), SafeExtras.int(intent, "nul"))
        assertEquals(ExtraRead.Absent, SafeExtras.boolean(intent, "missing"))
    }

    @Test fun `풀 수 없는 extras`() {
        val hostile = object : Intent() {
            override fun getExtras(): Bundle = throw BadParcelableException("bad")
        }
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.UNREADABLE), SafeExtras.string(hostile, "s"))
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.UNREADABLE), SafeExtras.int(hostile, "i"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 31, 35])
class AndroidDeepLinksTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun `자기 앱 전용 인텐트`() {
        val intent = AndroidDeepLinks.selfIntent(context, "myapp://notice?id=1")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(context.packageName, intent.`package`)
        assertTrue(intent.dataString!!.matches(Regex("myapp://notice\\?id=1#plumt-self=[0-9a-f]{64}")))
        assertNull(intent.extras)
        // 같은 URI 는 같은 토큰, 다른 URI 는 다른 토큰
        assertEquals(intent.dataString, AndroidDeepLinks.selfIntent(context, "myapp://notice?id=1").dataString)
        assertTrue(intent.dataString != AndroidDeepLinks.selfIntent(context, "myapp://notice?id=2").dataString?.replace("id=2", "id=1"))
        assertIae("URI 를 받을 수 없다: Malformed(reason=USERINFO_NOT_ALLOWED)") { AndroidDeepLinks.selfIntent(context, "https://a@example.net") }
        assertIae("자기 링크에는 프래그먼트(#)를 쓸 수 없다") { AndroidDeepLinks.selfIntent(context, "myapp://notice#plumt-self=x") }
    }

    @Test fun `PendingIntent 는 항상 IMMUTABLE`() {
        val pi = AndroidDeepLinks.selfPendingIntent(context, "myapp://notice", 3)
        val shadow = shadowOf(pi)
        assertTrue(shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(shadow.flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
        assertEquals(3, shadow.requestCode)
        assertEquals(context.packageName, shadow.savedIntent.`package`)
        val oneShot = shadowOf(AndroidDeepLinks.selfPendingIntent(context, "myapp://notice", 4, PendingIntent.FLAG_ONE_SHOT))
        assertTrue(oneShot.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertIae("FLAG_MUTABLE 은 쓸 수 없다: flags=${1 shl 25}") { AndroidDeepLinks.selfPendingIntent(context, "myapp://n", 1, 1 shl 25) }
        assertIae(null) { AndroidDeepLinks.selfPendingIntent(context, "bad", 1) }
    }

    @Test fun `trustPolicy 는 자기 패키지를 SELF 로`() {
        val policy = AndroidDeepLinks.trustPolicy(context)
        assertEquals(TrustLevel.SELF, policy.resolve(CallerIdentity(context.packageName, CallerVerification.SELF_ISSUED)))
        assertEquals(TrustLevel.PUBLIC, policy.resolve(CallerIdentity(context.packageName, CallerVerification.OS_VERIFIED)))
        assertEquals(TrustLevel.PUBLIC, policy.resolve(CallerIdentity(context.packageName, CallerVerification.CLAIMED)))
    }

    @Test fun `읽기 결과 값 동등성`() {
        assertEquals(ExtraRead.Present(1), ExtraRead.Present(1))
        assertEquals(IntentReadResult.Unsafe(UnsafeIntentReason.HAS_SELECTOR), IntentReadResult.Unsafe(UnsafeIntentReason.HAS_SELECTOR))
    }
}
