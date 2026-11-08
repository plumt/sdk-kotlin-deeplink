package net.plumt.deeplink

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import net.plumt.deeplink.internal.AppLinkReader
import net.plumt.deeplink.internal.DeepLinkIntentReader
import net.plumt.deeplink.internal.DefaultDeepLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** README 의 0.1.0 Activity 예제와 같은 구성 — 공개 API 만 쓴다. */
class PublicApiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        links.offerLaunch(this, savedInstanceState)
        links.consumeWhileResumed(this, { link -> routed += link.route.id + ":" + link.trust; true }) { outcomes += it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        links.offerNewIntent(this, intent)
    }

    fun deliver(intent: Intent) = onNewIntent(intent)

    companion object {
        lateinit var links: DeepLinks
        val routed = ArrayList<String>()
        val outcomes = ArrayList<DeepLinkOutcome>()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 31, 35])
class DeepLinksTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val config = deepLinkConfig {
        route(deepLinkRoute("item", "myapp://item/{id}") { pathParam(Param.long("id")) })
        route(deepLinkRoute("notice", "myapp://notice") { requiredTrust(TrustLevel.SELF) })
        externalHost("example.com")
    }
    private val links get() = DeepLinks.create(context, config)
    private val ok = DeepLinkRouter { true }

    @Before fun reset() {
        PublicApiActivity.routed.clear()
        PublicApiActivity.outcomes.clear()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun `공개 API 만으로 콜드 스타트와 웜 스타트를 한 번씩 처리한다`() {
        PublicApiActivity.links = links
        val controller = Robolectric.buildActivity(
            PublicApiActivity::class.java,
            Intent(Intent.ACTION_VIEW, Uri.parse("myapp://item/1")),
        ).setup()
        idle()
        assertEquals(listOf("item:PUBLIC"), PublicApiActivity.routed)
        controller.get().deliver(Intent(Intent.ACTION_VIEW, Uri.parse("myapp://item/2")))
        idle()
        assertEquals(listOf("item:PUBLIC", "item:PUBLIC"), PublicApiActivity.routed)
        // 알림 탭 링크는 자기 앱 토큰으로 SELF 가 된다. 토큰 없는 같은 URI 는 거부된다
        controller.get().deliver(PublicApiActivity.links.selfIntent("myapp://notice"))
        controller.get().deliver(Intent(Intent.ACTION_VIEW, Uri.parse("myapp://notice")))
        idle()
        assertEquals("item:PUBLIC,item:PUBLIC,notice:SELF", PublicApiActivity.routed.joinToString(","))
        assertTrue(PublicApiActivity.outcomes.last() is DeepLinkOutcome.Rejected)
        // 회전 — 다시 처리하지 않는다
        controller.recreate()
        idle()
        assertEquals(3, PublicApiActivity.routed.size)
        assertEquals(0, PublicApiActivity.links.pendingCount)
        controller.pause().stop().destroy()
    }

    @Test fun `대기열 offer clear pendingCount`() {
        val l = DeepLinks.create(context, deepLinkConfig { route(deepLinkRoute("item", "myapp://item")); queueCapacity(1) })
        val first = DeepLinkRequest("myapp://item")
        assertNull(l.offer(first))
        assertSame(first, l.offer(DeepLinkRequest("myapp://item")))
        assertEquals(1, l.pendingCount)
        l.clearPending()
        assertEquals(0, l.pendingCount)
        assertTrue(l.toString().startsWith("DeepLinks(routes=1"))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        l.offer(first)
        val consumer = l.consumeWhileResumed(activity, ok)
        idle()
        assertEquals(0, l.pendingCount)
        consumer.close()
        assertEquals(IntentReadResult.NotDeepLink, l.offerLaunch(activity, Bundle()))
        assertEquals(IntentReadResult.NotDeepLink, l.offerNewIntent(activity, Intent(Intent.ACTION_MAIN)))
        assertEquals(0, l.pendingCount)
    }

    @Test fun `소비는 메인 스레드에서만`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var error: Throwable? = null
        val t = Thread { error = runCatching { links.consumeWhileResumed(activity, ok) }.exceptionOrNull() }
        t.start()
        t.join()
        assertEquals("consumeWhileResumed 는 메인 스레드에서 불러야 한다", error?.message)
    }

    @Test fun `suspend 처리와 콜백 처리는 같은 결과`() = runBlocking {
        val l = links
        val request = DeepLinkRequest("myapp://item/3")
        assertTrue(l.resolve(request) is DeepLinkResolution.Resolved)
        val suspended = l.handle(request, ok)
        var called: DeepLinkOutcome? = null
        l.handle(request, ok) { called = it }
        idle()
        assertEquals(suspended.javaClass, called?.javaClass)
        assertTrue(called is DeepLinkOutcome.Handled)
    }

    @Test fun `콜백 처리는 취소하면 부르지 않는다`() {
        val gate = CompletableDeferred<Unit>()
        val waiting = DeepLinkRouter { gate.await(); true }
        var called = false
        val handle = links.handle(DeepLinkRequest("myapp://item/3"), waiting) { called = true }
        idle()
        handle.cancel()
        handle.cancel()
        gate.complete(Unit)
        idle()
        assertFalse(called)
        assertTrue(handle.toString().startsWith("DeepLinkHandle(active="))
    }

    @Test fun `콜백 재개와 다른 인스턴스 링크 거부`() = runBlocking {
        val deferring = deepLinkConfig {
            route(deepLinkRoute("item", "myapp://item"))
            interceptor(object : DeepLinkInterceptor {
                var open = false
                override suspend fun intercept(chain: DeepLinkChain): DeepLinkOutcome =
                    if (open) chain.proceed() else DeepLinkOutcome.Deferred(chain.link, "login").also { open = true }
            })
        }
        val l = DeepLinks.create(context, deepLinkConfig { route(deepLinkRoute("item", "myapp://item")) })
        val owner = DeepLinks.create(context, deferring)
        val deferred = owner.handle(DeepLinkRequest("myapp://item"), ok) as DeepLinkOutcome.Deferred
        assertIae(null) { l.resume(deferred.link, ok) { } }
        var resumed: DeepLinkOutcome? = null
        owner.resume(deferred.link, ok) { resumed = it }
        idle()
        assertTrue(resumed is DeepLinkOutcome.Handled)
        assertTrue(owner.resume(deferred.link, ok) is DeepLinkOutcome.Handled)
    }

    @Test fun `자기 링크와 PendingIntent`() {
        val l = links
        val intent = l.selfIntent("myapp://notice")
        assertEquals(context.packageName, intent.`package`)
        assertTrue(intent.dataString!!.startsWith("myapp://notice#plumt-self="))
        val pi = shadowOf(l.selfPendingIntent("myapp://notice", 1))
        assertTrue(pi.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(pi.flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
        val oneShot = shadowOf(l.selfPendingIntent("myapp://notice", 2, PendingIntent.FLAG_ONE_SHOT))
        assertTrue(oneShot.flags and PendingIntent.FLAG_ONE_SHOT != 0)
        assertIae("FLAG_MUTABLE 은 쓸 수 없다: flags=${1 shl 25}") { l.selfPendingIntent("myapp://notice", 3, 1 shl 25) }
    }

    @Test fun `extras 원시 타입 읽기`() {
        val l = links
        val intent = Intent().putExtra("s", "abc").putExtra("i", 1).putExtra("l", 2L).putExtra("b", true)
        assertEquals(ExtraRead.Present("abc"), l.stringExtra(intent, "s"))
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.TOO_LONG), l.stringExtra(intent, "s", 2))
        assertEquals(ExtraRead.Present(1), l.intExtra(intent, "i"))
        assertEquals(ExtraRead.Present(2L), l.longExtra(intent, "l"))
        assertEquals(ExtraRead.Present(true), l.booleanExtra(intent, "b"))
        assertEquals(ExtraRead.Absent, l.booleanExtra(intent, "none"))
    }

    @Test fun `App Link 상태는 플랫폼 판독기를 거친다`() {
        val status = links.appLinkStatus()
        if (android.os.Build.VERSION.SDK_INT < 31) {
            assertEquals(AppLinkStatus.Unsupported, status)
        } else {
            // Robolectric 에는 도메인 검증 서비스가 없다 — 어느 쪽이든 크래시 없이 값으로 온다
            assertTrue(status is AppLinkStatus.Unavailable || status is AppLinkStatus.Available)
        }
        val available = AppLinkStatus.Available(true, mapOf("example.com" to AppLinkHostState.VERIFIED))
        val injected = DefaultDeepLinks(context, config, DeepLinkIntentReader.create(context), AppLinkReader { available })
        assertSame(available, injected.appLinkStatus())
    }

    @Test fun `App Link 설정 화면 인텐트`() {
        val intent = links.appLinkSettingsIntent()
        val expected = if (android.os.Build.VERSION.SDK_INT >= 31) {
            Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
        } else {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
        assertEquals(expected, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test fun `외부 링크 인텐트는 허용 호스트의 https 만`() {
        val l = links
        val ready = l.externalIntent("https://EXAMPLE.com/a%20b?q=1#frag") as ExternalLinkResult.Ready
        assertEquals("https://example.com/a%20b?q=1", ready.intent.dataString)
        assertEquals(Intent.ACTION_VIEW, ready.intent.action)
        assertTrue(ready.intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(ready.intent.component)
        assertNull(ready.intent.`package`)
        assertNull(ready.intent.selector)
        assertNull(ready.intent.extras)
        assertEquals("Ready", ready.toString())
        fun rejected(uri: String) = (l.externalIntent(uri) as ExternalLinkResult.Rejected).rejection
        assertEquals(Rejection.UnknownScheme("intent"), rejected("intent://example.com/#Intent;scheme=https;package=evil.app;end"))
        assertEquals(Rejection.UnknownScheme("http"), rejected("http://example.com/"))
        assertEquals(Rejection.UnknownScheme("myapp"), rejected("myapp://item/1"))
        assertEquals(Rejection.Malformed(MalformedReason.MISSING_AUTHORITY), rejected("javascript:alert(1)"))
        assertEquals(Rejection.Malformed(MalformedReason.INVALID_HOST), rejected("file:///data/data/x"))
        assertEquals(Rejection.UnknownScheme("content"), rejected("content://media/external/1"))
        assertEquals(Rejection.UnknownHost("https", "evil.com", null), rejected("https://evil.com/"))
        assertEquals(Rejection.UnknownHost("https", "a.example.com", null), rejected("https://a.example.com/"))
        assertEquals(Rejection.UnknownHost("https", "example.com", 8443), rejected("https://example.com:8443/"))
        assertEquals(Rejection.Malformed(MalformedReason.USERINFO_NOT_ALLOWED), rejected("https://example.com@example.net/"))
    }

    @Test fun `외부 링크 열기`() {
        val l = links
        assertEquals(ExternalOpenResult.Rejected(Rejection.UnknownScheme("intent")), l.openExternal(context, "intent://x/#Intent;end"))
        // 애플리케이션 컨텍스트면 새 태스크로 연다
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE),
            android.content.pm.ResolveInfo().apply {
                activityInfo = android.content.pm.ActivityInfo().apply { packageName = "browser.app"; name = "Browser" }
            },
        )
        val result = l.openExternal(context, "https://example.com/")
        assertTrue(result == ExternalOpenResult.Opened || result == ExternalOpenResult.NoActivity)
        if (result == ExternalOpenResult.Opened) {
            val started = shadowOf(context).nextStartedActivity
            assertNotNull(started)
            assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val fromActivity = l.openExternal(activity, "https://example.com/x")
        assertTrue(fromActivity == ExternalOpenResult.Opened || fromActivity == ExternalOpenResult.NoActivity)
    }

    @Test fun `열 앱이 없으면 NoActivity`() {
        shadowOf(context).checkActivities(true)
        assertEquals(ExternalOpenResult.NoActivity, links.openExternal(context, "https://example.com/none"))
    }

    @Test fun `자기 패키지를 신뢰 목록에 넣을 수 없다`() {
        assertIae("trustedCallers 에 자기 패키지를 넣을 수 없다: ${context.packageName}") {
            DeepLinks.create(context, deepLinkConfig {
                route(deepLinkRoute("item", "myapp://item"))
                trustedCaller(TrustedCaller(context.packageName, setOf(digest(SIG_A))))
            })
        }
    }

    @Test fun `직접 구현한 발신자 판정과 정책`() {
        val custom = CallerVerification("SERVER_SIGNED")
        val l = DeepLinks.create(context, deepLinkConfig {
            route(deepLinkRoute("partner", "myapp://partner") { requiredTrust(TrustLevel.FIRST_PARTY) })
            callerVerifier(object : CallerVerifier {
                override fun identify(activity: android.app.Activity, intent: Intent, initialLaunch: Boolean) =
                    CallerIdentity("partner.app", custom)
            })
            trustPolicy { caller -> if (caller.verification == custom) TrustLevel.FIRST_PARTY else TrustLevel.PUBLIC }
        })
        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse("myapp://partner")))
        val link = l.readLaunch(activity, null) as IntentReadResult.Link
        assertEquals(custom, link.request.caller.verification)
        assertTrue(l.resolve(link.request) is DeepLinkResolution.Resolved)
        val read = l.readNewIntent(activity, Intent(Intent.ACTION_VIEW, Uri.parse("myapp://partner")))
        assertTrue(read is IntentReadResult.Link)
    }
}
