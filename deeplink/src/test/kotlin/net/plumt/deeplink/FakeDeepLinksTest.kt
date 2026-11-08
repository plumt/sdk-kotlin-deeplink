package net.plumt.deeplink

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기기·Robolectric 없이 — 앱 단위 테스트가 쓰는 방식 그대로. */
class FakeDeepLinksTest {

    private val itemId = Param.long("id", 1L..100L)
    private val config = deepLinkConfig {
        route(deepLinkRoute("item", "myapp://item/{id}") { pathParam(itemId) })
        route(deepLinkRoute("notice", "myapp://notice") { requiredTrust(TrustLevel.SELF) })
        externalHost("example.com")
    }
    private val routed = ArrayList<String>()
    private val router = DeepLinkRouter { routed += it.route.id; true }

    @Test fun `기본 구현과 같은 검증으로 처리한다`() = runBlocking {
        val fake = FakeDeepLinks(config)
        assertEquals(FakeDeepLinks.DEFAULT_SELF_PACKAGE, fake.selfPackage)
        val handled = fake.handle(DeepLinkRequest("myapp://item/7"), DeepLinkRouter { link -> routed += "item:${link[itemId]}"; true })
        assertTrue(handled is DeepLinkOutcome.Handled)
        assertEquals(listOf("item:7"), routed)
        assertEquals(
            DeepLinkOutcome.Rejected(Rejection.InvalidParameter("item", "id", "범위 1..100 밖이다")),
            fake.handle(DeepLinkRequest("myapp://item/700"), router),
        )
        // 알림 링크 — 자기 패키지의 SELF_ISSUED 만 연다
        val self = CallerIdentity(FakeDeepLinks.DEFAULT_SELF_PACKAGE, CallerVerification.SELF_ISSUED)
        assertTrue(fake.resolve(DeepLinkRequest("myapp://notice", self)) is DeepLinkResolution.Resolved)
        assertTrue(fake.resolve(DeepLinkRequest("myapp://notice")) is DeepLinkResolution.Rejected)
        assertEquals(2, fake.outcomes.size)
    }

    @Test fun `자기 패키지 지정과 재개`() = runBlocking {
        var open = false
        val fake = FakeDeepLinks(
            deepLinkConfig {
                route(deepLinkRoute("item", "myapp://item/{id}") { pathParam(itemId) })
                interceptor(object : DeepLinkInterceptor {
                    override suspend fun intercept(chain: DeepLinkChain): DeepLinkOutcome =
                        if (open) chain.proceed() else DeepLinkOutcome.Deferred(chain.link, "login")
                })
            },
            "my.app",
        )
        val deferred = fake.handle(DeepLinkRequest("myapp://item/1"), router) as DeepLinkOutcome.Deferred
        open = true
        assertTrue(fake.resume(deferred.link, router) is DeepLinkOutcome.Handled)
        assertIae("이 DeepLinks 가 만든 링크가 아니다: ${deferred.link}") {
            runBlocking { FakeDeepLinks(config).resume(deferred.link, router) }
        }
        assertIae(null) { FakeDeepLinks(config).resume(deferred.link, router) { } }
    }

    @Test fun `정해 둔 값을 돌려준다`() {
        val fake = FakeDeepLinks(config)
        assertEquals(AppLinkStatus.Unsupported, fake.appLinkStatus())
        val status = AppLinkStatus.Available(false, mapOf("example.com" to AppLinkHostState.VERIFIED))
        fake.appLinkStatusResult = status
        assertSame(status, fake.appLinkStatus())
        val link = IntentReadResult.Link(DeepLinkRequest("myapp://item/1"))
        assertEquals(IntentReadResult.NotDeepLink, fake.launchResult)
        fake.launchResult = link
        fake.newIntentResult = IntentReadResult.Unsafe(UnsafeIntentReason.HAS_SELECTOR)
        assertEquals(IntentReadResult.Unsafe(UnsafeIntentReason.HAS_SELECTOR), fake.newIntentResult)
        assertNull(fake.pendingIntentContext)
    }

    @Test fun `대기열과 초기화`() = runBlocking {
        val fake = FakeDeepLinks(deepLinkConfig { route(deepLinkRoute("item", "myapp://item/{id}")); queueCapacity(2) })
        assertNull(fake.offer(DeepLinkRequest("myapp://item/1")))
        fake.offer(DeepLinkRequest("myapp://item/2"))
        assertEquals("myapp://item/1", fake.offer(DeepLinkRequest("myapp://item/3"))?.uri)
        assertEquals(2, fake.pendingCount)
        fake.handle(DeepLinkRequest("myapp://item/1"), DeepLinkRouter { true })
        assertTrue(fake.toString().startsWith("FakeDeepLinks(routes=1"))
        fake.clearPending()
        assertEquals(0, fake.pendingCount)
        fake.offer(DeepLinkRequest("myapp://item/2"))
        fake.reset()
        assertEquals(0, fake.pendingCount)
        assertTrue(fake.outcomes.isEmpty())
        assertTrue(fake.openedExternal.isEmpty())
        assertTrue(fake.issuedSelfLinks.isEmpty())
    }

    @Test fun `PendingIntent 는 컨텍스트가 있어야 한다`() {
        val fake = FakeDeepLinks(config)
        assertIae("FLAG_MUTABLE 은 쓸 수 없다: flags=${1 shl 25}") { fake.selfPendingIntent("myapp://notice", 1, 1 shl 25) }
        val e = runCatching { fake.selfPendingIntent("myapp://notice", 1) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        assertEquals("selfPendingIntent 를 쓰려면 pendingIntentContext 를 정해야 한다", e!!.message)
    }
}
