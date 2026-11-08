package net.plumt.deeplink

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** 가짜 구현 가운데 Android 클래스를 만드는 부분 — Robolectric 에서만. */
@RunWith(RobolectricTestRunner::class)
class FakeDeepLinksAndroidTest {
    private val config = deepLinkConfig {
        route(deepLinkRoute("notice", "myapp://notice") { requiredTrust(TrustLevel.SELF) })
        externalHost("example.com")
    }

    @Test fun `외부 링크는 검증만 하고 기록한다`() {
        val fake = FakeDeepLinks(config)
        val dummy: android.content.Context = ApplicationProvider.getApplicationContext()
        assertEquals(ExternalOpenResult.Opened, fake.openExternal(dummy, "https://example.com/a?b=1#c"))
        assertEquals(listOf("https://example.com/a?b=1"), fake.openedExternal)
        assertEquals(ExternalOpenResult.Rejected(Rejection.UnknownHost("https", "evil.com", null)), fake.openExternal(dummy, "https://evil.com"))
        assertEquals(ExternalLinkResult.Rejected(Rejection.UnknownScheme("intent")), fake.externalIntent("intent://x/#Intent;end"))
        fake.externalActivityAvailable = false
        assertEquals(ExternalOpenResult.NoActivity, fake.openExternal(dummy, "https://example.com/"))
        assertEquals(1, fake.openedExternal.size)
    }

    @Test fun `자기 링크 인텐트와 설정 화면`() {
        val fake = FakeDeepLinks(config, "my.app")
        val intent = fake.selfIntent("myapp://notice")
        assertEquals("myapp://notice", intent.dataString)
        assertEquals("my.app", intent.`package`)
        assertEquals(listOf("myapp://notice"), fake.issuedSelfLinks)
        assertIae("자기 링크에는 프래그먼트(#)를 쓸 수 없다") { fake.selfIntent("myapp://notice#x") }
        fake.pendingIntentContext = ApplicationProvider.getApplicationContext()
        assertTrue(shadowOf(fake.selfPendingIntent("myapp://notice", 1)).isActivityIntent)
        assertEquals("package:my.app", fake.appLinkSettingsIntent().dataString)
        assertTrue(fake.externalIntent("https://example.com/") is ExternalLinkResult.Ready)
    }

    @Test fun `콜백 처리와 앞 화면 소비`() {
        val fake = FakeDeepLinks(config, "my.app")
        var outcome: DeepLinkOutcome? = null
        fake.handle(DeepLinkRequest("myapp://notice", CallerIdentity("my.app", CallerVerification.SELF_ISSUED)), DeepLinkRouter { true }) { outcome = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(outcome is DeepLinkOutcome.Handled)
        val activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        val seen = ArrayList<DeepLinkOutcome>()
        fake.offer(DeepLinkRequest("myapp://notice"))
        val consumer = fake.consumeWhileResumed(activity, DeepLinkRouter { true }) { seen += it }
        shadowOf(Looper.getMainLooper()).idle()
        consumer.close()
        assertTrue(seen.single() is DeepLinkOutcome.Rejected)
        assertEquals(2, fake.outcomes.size)
        // 콜백 재개
        val deferred = deepLinkConfig {
            route(deepLinkRoute("notice", "myapp://notice"))
            interceptor(object : DeepLinkInterceptor {
                var open = false
                override suspend fun intercept(chain: DeepLinkChain): DeepLinkOutcome =
                    if (open) chain.proceed() else DeepLinkOutcome.Deferred(chain.link, "wait").also { open = true }
            })
        }
        val waiting = FakeDeepLinks(deferred)
        var first: DeepLinkOutcome? = null
        waiting.handle(DeepLinkRequest("myapp://notice"), DeepLinkRouter { true }) { first = it }
        shadowOf(Looper.getMainLooper()).idle()
        var resumed: DeepLinkOutcome? = null
        waiting.resume((first as DeepLinkOutcome.Deferred).link, DeepLinkRouter { true }) { resumed = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(resumed is DeepLinkOutcome.Handled)
        assertEquals(IntentReadResult.NotDeepLink, fake.offerLaunch(activity, null))
        assertEquals(IntentReadResult.NotDeepLink, fake.readNewIntent(activity, Intent()))
    }
}
