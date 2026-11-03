package net.plumt.deeplink

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class DeepLinkHandlerTest {

    private val itemId = Param.long("id", 1L..1_000_000L)
    private val ref = Param.string("ref", maxLength = 8)
    private val page = Param.int("page", 1..100)
    private val next = Param.httpsUrl("next", setOf("example.com"))

    private val item = DeepLinkRoute.builder("item", "myapp://item/{id}").pathParam(itemId).queryParam(ref).build()
    private val itemNew = deepLinkRoute("item-new", "myapp://item/new")
    private val list = DeepLinkRoute.builder("list", "myapp://list").queryParam(page, required = true).build()
    private val strict = DeepLinkRoute.builder("strict", "myapp://strict/{code}")
        .unknownQueryParameters(UnknownQueryPolicy.REJECT).build()
    private val internal = DeepLinkRoute.builder("internal", "myapp://internal").requiredTrust(TrustLevel.SELF).build()
    private val partner = DeepLinkRoute.builder("partner", "myapp://partner").requiredTrust(TrustLevel.FIRST_PARTY).build()
    private val web = DeepLinkRoute.builder("web", "https://example.com/p/{slug}").queryParam(next).build()

    private val routed = Collections.synchronizedList(mutableListOf<String>())
    private val outcomes = Collections.synchronizedList(mutableListOf<DeepLinkOutcome>())

    private fun handler(
        routerResult: Boolean = true,
        vararg interceptors: DeepLinkInterceptor,
        router: DeepLinkRouter = DeepLinkRouter { routed += it.route.id; routerResult },
    ) = DeepLinkHandler.builder()
        .routes(listOf(item, list, strict, internal, partner, web))
        .route(itemNew)
        .trustPolicy(TrustPolicy.standard("my.app"))
        .router(router)
        .listener { outcomes += it }
        .apply { interceptors.forEach { interceptor(it) } }
        .build()

    private fun resolve(uri: String, caller: CallerIdentity = CallerIdentity.UNKNOWN): DeepLinkResolution =
        handler().resolve(DeepLinkRequest(uri, caller))

    private fun rejection(uri: String, caller: CallerIdentity = CallerIdentity.UNKNOWN): Rejection =
        (resolve(uri, caller) as DeepLinkResolution.Rejected).rejection

    private fun link(uri: String, caller: CallerIdentity = CallerIdentity.UNKNOWN): ResolvedDeepLink =
        (resolve(uri, caller) as DeepLinkResolution.Resolved).link

    private val self = CallerIdentity("my.app", CallerVerification.SYSTEM_REPORTED)
    private val partnerApp = CallerIdentity("partner.app", CallerVerification.SIGNATURE_VERIFIED)

    @Test fun `정상 - 타입 있는 경로·쿼리 값`() {
        val l = link("myapp://item/42?ref=push&utm=x")
        assertSame(item, l.route)
        assertEquals(42L, l[itemId])
        assertEquals("push", l.getOrNull(ref))
        assertEquals(TrustLevel.PUBLIC, l.trust)
        assertEquals(CallerIdentity.UNKNOWN, l.caller)
        assertEquals("myapp://item/*?ref=*&utm=*", l.uri.toString())
        assertEquals("ResolvedDeepLink(route=item, pattern=myapp://item/{id}, trust=PUBLIC)", l.toString())
        assertNull(link("myapp://item/42").getOrNull(ref))
    }

    @Test fun `선언하지 않은 경로 파라미터는 기본 문자열 검증`() {
        val l = link("myapp://strict/ab-c")
        assertEquals("ab-c", l.pathValue("code"))
        assertNull(l.pathValue("none"))
        assertEquals(Rejection.TooLong(LimitKind.SEGMENT_LENGTH, 256, 257), rejection("myapp://strict/" + "a".repeat(257)))
        val wide = deepLinkHandler { route(strict); router { true }; uriRules(UriRules(maxSegmentLength = 1000)) }
        assertEquals(
            DeepLinkResolution.Rejected(Rejection.InvalidParameter("strict", "code", "길이 257 가 상한 256 을 넘는다")),
            wide.resolve(DeepLinkRequest("myapp://strict/" + "a".repeat(257))),
        )
        // 파서가 이중 인코딩을 허용해도 기본 문자열 검증기가 한 번 더 막는다
        val doubleOk = deepLinkHandler { route(strict); router { true }; uriRules(UriRules(allowDoubleEncoding = true)) }
        assertEquals(
            DeepLinkResolution.Rejected(Rejection.InvalidParameter("strict", "code", "이중 인코딩된 값이다")),
            doubleOk.resolve(DeepLinkRequest("myapp://strict/%2541")),
        )
    }

    @Test fun `get·getOrNull 오류`() {
        val l = link("myapp://item/42")
        assertIae("'ref' 값이 없다: route 'item'") { l[ref] }
        assertIae("'id' 는 route 'item' 에 선언한 Param 이 아니다") { l.getOrNull(Param.long("id")) }
        assertIae("'page' 는 route 'item' 에 선언한 Param 이 아니다") { l.getOrNull(page) }
        assertNull(l.pathValue("id")) // 선언한 경로 파라미터는 Long 이라 문자열로 읽히지 않는다
    }

    @Test fun `구체적인 경로가 이긴다 - 등록 순서와 무관`() {
        assertSame(itemNew, link("myapp://item/new").route)
        assertSame(item, link("myapp://item/7").route)
        assertEquals(Rejection.InvalidParameter("item", "id", "정수가 아니다"), rejection("myapp://item/newer"))
    }

    @Test fun `구체성이 같으면 먼저 등록한 경로`() {
        val a = deepLinkRoute("a", "myapp://x/{p}")
        val b = deepLinkRoute("b", "myapp://x/{q}")
        val h = deepLinkHandler { route(a); route(b); router { true } }
        assertSame(a, (h.resolve(DeepLinkRequest("myapp://x/1")) as DeepLinkResolution.Resolved).link.route)
        assertEquals(listOf(a, b), h.routes)
    }

    @Test fun `스킴·호스트·경로 거부 사유`() {
        assertEquals(Rejection.UnknownScheme("other"), rejection("other://item/1"))
        assertEquals(Rejection.UnknownScheme("http"), rejection("http://example.com/p/a"))
        assertEquals(Rejection.UnknownHost("myapp", "nope", null), rejection("myapp://nope/1"))
        assertEquals(Rejection.UnknownHost("myapp", "item", 8080), rejection("myapp://item:8080/1"))
        assertEquals(Rejection.UnknownHost("https", "evil.com", null), rejection("https://evil.com/p/a"))
        assertEquals(Rejection.UnknownHost("https", "example.com", 8443), rejection("https://example.com:8443/p/a"))
        assertEquals(Rejection.NoMatchingRoute("myapp", "item"), rejection("myapp://item"))
        assertEquals(Rejection.NoMatchingRoute("myapp", "item"), rejection("myapp://item/1/2"))
        assertEquals(Rejection.NoMatchingRoute("https", "example.com"), rejection("https://example.com/P/a"))
        assertEquals(Rejection.Malformed(MalformedReason.EMPTY), rejection(""))
        assertEquals(Rejection.Malformed(MalformedReason.ENCODED_SLASH), rejection("myapp://item/1%2F2"))
        assertEquals(Rejection.Malformed(MalformedReason.USERINFO_NOT_ALLOWED), rejection("https://example.com@evil.com/p/a"))
        // 대소문자 섞인 스킴·호스트, 기본 포트는 같은 것으로 본다
        assertSame(web, link("HTTPS://Example.COM:443/p/a").route)
    }

    @Test fun `신뢰 수준 - 기본 정책`() {
        assertEquals(Rejection.InsufficientTrust("internal", TrustLevel.SELF, TrustLevel.PUBLIC), rejection("myapp://internal"))
        assertEquals(
            Rejection.InsufficientTrust("internal", TrustLevel.SELF, TrustLevel.PUBLIC),
            rejection("myapp://internal", CallerIdentity("my.app", CallerVerification.CLAIMED)),
        )
        assertEquals(
            Rejection.InsufficientTrust("internal", TrustLevel.SELF, TrustLevel.FIRST_PARTY),
            rejection("myapp://internal", partnerApp),
        )
        assertEquals(TrustLevel.SELF, link("myapp://internal", self).trust)
        assertEquals(TrustLevel.FIRST_PARTY, link("myapp://partner", partnerApp).trust)
        assertEquals(TrustLevel.SELF, link("myapp://partner", self).trust)
        assertEquals(
            Rejection.InsufficientTrust("partner", TrustLevel.FIRST_PARTY, TrustLevel.PUBLIC),
            rejection("myapp://partner", CallerIdentity("partner.app", CallerVerification.SYSTEM_REPORTED)),
        )
        // https(App Link) 라는 사실만으로 신뢰가 오르지 않는다
        assertEquals(TrustLevel.PUBLIC, link("https://example.com/p/a").trust)
    }

    @Test fun `신뢰를 파라미터 검증보다 먼저 본다`() {
        val calls = mutableListOf<String>()
        val probe = Param("v", ParamValidator { calls += it; ParamCheck.Valid(it) })
        val r = DeepLinkRoute.builder("r", "myapp://r/{v}").pathParam(probe).requiredTrust(TrustLevel.SELF).build()
        val h = deepLinkHandler { route(r); router { true } }
        assertTrue(h.resolve(DeepLinkRequest("myapp://r/x")) is DeepLinkResolution.Rejected)
        assertTrue(calls.isEmpty())
    }

    @Test fun `기본 빌더 정책은 SELF 를 주지 않는다`() {
        val h = deepLinkHandler { route(internal); router { true } }
        val r = h.resolve(DeepLinkRequest("myapp://internal", CallerIdentity("my.app", CallerVerification.OS_VERIFIED)))
        assertEquals(DeepLinkResolution.Rejected(Rejection.InsufficientTrust("internal", TrustLevel.SELF, TrustLevel.PUBLIC)), r)
    }

    @Test fun `쿼리 - 필수·중복·선언 외·검증 실패`() {
        assertEquals(3, link("myapp://list?page=3")[page])
        assertEquals(Rejection.MissingParameter("list", "page"), rejection("myapp://list"))
        assertEquals(Rejection.MissingParameter("list", "page"), rejection("myapp://list?other=1"))
        assertEquals(Rejection.DuplicateParameter("list", "page"), rejection("myapp://list?page=1&page=2"))
        assertEquals(Rejection.InvalidParameter("list", "page", "범위 1..100 밖이다"), rejection("myapp://list?page=0"))
        assertEquals(Rejection.InvalidParameter("list", "page", "정수가 아니다"), rejection("myapp://list?page="))
        assertEquals(Rejection.InvalidParameter("item", "ref", "길이 9 가 상한 8 을 넘는다"), rejection("myapp://item/1?ref=123456789"))
        assertEquals(Rejection.InvalidParameter("item", "ref", "이중 인코딩된 값이다"), rejection("myapp://item/1?ref=%252F"))
        // 선언 외 이름은 DROP 이면 버리고, 중복이어도 상관없다
        val dropped = link("myapp://item/1?x=1&x=2")
        assertEquals(listOf("1", "2"), dropped.uri.queryParameters["x"])
        assertEquals(Rejection.UnexpectedParameter("strict", "x"), rejection("myapp://strict/a?x=1"))
        assertEquals(Rejection.InvalidParameter("item", "id", "범위 1..1000000 밖이다"), rejection("myapp://item/0"))
    }

    @Test fun `오픈 리다이렉트 - next 파라미터`() {
        assertEquals("example.com", link("https://example.com/p/a?next=https%3A%2F%2Fexample.com%2Fhome")[next].host)
        assertEquals(
            Rejection.InvalidParameter("web", "next", "허용하지 않은 호스트다"),
            rejection("https://example.com/p/a?next=https%3A%2F%2Fevil.com"),
        )
        assertEquals(
            Rejection.InvalidParameter("web", "next", "URL 형식이 아니다"),
            rejection("https://example.com/p/a?next=%2F%2Fevil.com"),
        )
    }

    @Test fun `검증기 예외는 거부로 바뀌고 값은 싣지 않는다`() {
        val boom = Param<String>("v", ParamValidator { throw IllegalStateException("secret-$it") })
        val r = DeepLinkRoute.builder("r", "myapp://r/{v}").pathParam(boom).build()
        val h = deepLinkHandler { route(r); router { true } }
        assertEquals(
            DeepLinkResolution.Rejected(Rejection.InvalidParameter("r", "v", "검증기 예외 IllegalStateException")),
            h.resolve(DeepLinkRequest("myapp://r/token")),
        )
    }

    @Test fun `사용자 매처`() {
        val matcher = object : RouteMatcher {
            override val scheme = "myapp"
            override val host = "m"
            override val pattern = "myapp://m/<digits>"
            override val specificity = 5
            override fun match(uri: DeepLinkUri) =
                uri.pathSegments.singleOrNull()?.takeIf { s -> s.all { it.isDigit() } }?.let { mapOf("n" to it) }
        }
        val n = Param.int("n")
        val r = DeepLinkRoute.builder("m", matcher).pathParam(n).build()
        val h = deepLinkHandler { route(r); router { true } }
        assertEquals(12, (h.resolve(DeepLinkRequest("myapp://m/12")) as DeepLinkResolution.Resolved).link[n])
        assertEquals(DeepLinkResolution.Rejected(Rejection.NoMatchingRoute("myapp", "m")), h.resolve(DeepLinkRequest("myapp://m/a")))
        // 매처가 선언하지 않은 이름을 내면 기본 문자열 검증
        val loose = DeepLinkRoute.builder("l", FakeMatcher("x", "h", mapOf("free" to "v"))).build()
        val h2 = deepLinkHandler { route(loose); router { true } }
        assertEquals("v", (h2.resolve(DeepLinkRequest("x://h/anything")) as DeepLinkResolution.Resolved).link.pathValue("free"))
    }

    @Test fun `처리 결과 - Handled·Unhandled 와 리스너`() = runBlocking {
        val h = handler()
        val handled = h.handle(DeepLinkRequest("myapp://item/1"))
        assertTrue(handled is DeepLinkOutcome.Handled)
        assertEquals(listOf("item"), routed)
        val rejected = h.handle(DeepLinkRequest("myapp://nope"))
        assertEquals(DeepLinkOutcome.Rejected(Rejection.UnknownHost("myapp", "nope", null)), rejected)
        assertEquals(listOf(handled, rejected), outcomes)
        val unhandled = handler(routerResult = false).handle(DeepLinkRequest("myapp://item/1"))
        assertTrue(unhandled is DeepLinkOutcome.Unhandled)
        assertEquals("item", (unhandled as DeepLinkOutcome.Unhandled).link.route.id)
    }

    @Test fun `리스너 예외는 전파된다`() = runBlocking {
        val h = deepLinkHandler { route(item); router { true }; listener { throw IllegalStateException("listener") } }
        val e = runCatching { h.handle(DeepLinkRequest("myapp://item/1")) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        assertEquals("listener", e!!.message)
    }

    private class Recording(override val priority: Int, val name: String, val log: MutableList<String>) : DeepLinkInterceptor {
        override suspend fun intercept(chain: DeepLinkChain): DeepLinkOutcome {
            log += "in:$name"
            val result = chain.proceed()
            log += "out:$name"
            return result
        }
    }

    @Test fun `인터셉터 순서 - priority 오름차순, 같으면 등록 순서, 바깥이 결과를 관찰`() = runBlocking {
        val log = mutableListOf<String>()
        val h = handler(
            true,
            Recording(10, "c", log), Recording(-5, "a", log), Recording(0, "b1", log), Recording(0, "b2", log),
            router = DeepLinkRouter { log += "router"; true },
        )
        h.handle(DeepLinkRequest("myapp://item/1"))
        assertEquals(listOf("in:a", "in:b1", "in:b2", "in:c", "router", "out:c", "out:b2", "out:b1", "out:a"), log)
        assertEquals(0, object : DeepLinkInterceptor { override suspend fun intercept(chain: DeepLinkChain) = chain.proceed() }.priority)
    }

    @Test fun `인터셉터 거부는 라우터를 막는다`() = runBlocking {
        val gate = object : DeepLinkInterceptor {
            override suspend fun intercept(chain: DeepLinkChain) = DeepLinkOutcome.Rejected(Rejection.Intercepted("update"))
        }
        val out = handler(true, gate).handle(DeepLinkRequest("myapp://item/1"))
        assertEquals(DeepLinkOutcome.Rejected(Rejection.Intercepted("update")), out)
        assertTrue(routed.isEmpty())
        assertEquals(listOf(out), outcomes)
    }

    @Test fun `로그인 필요 - 미뤘다가 resume`() = runBlocking {
        var loggedIn = false
        val auth = object : DeepLinkInterceptor {
            override suspend fun intercept(chain: DeepLinkChain): DeepLinkOutcome =
                if (loggedIn) chain.proceed() else DeepLinkOutcome.Deferred(chain.link, "login")
        }
        val h = handler(true, auth)
        val deferred = h.handle(DeepLinkRequest("myapp://item/9", self)) as DeepLinkOutcome.Deferred
        assertEquals("login", deferred.reason)
        assertTrue(routed.isEmpty())
        // 아직 로그인 전이면 다시 미뤄진다
        assertTrue(h.resume(deferred.link) is DeepLinkOutcome.Deferred)
        loggedIn = true
        val resumed = h.resume(deferred.link) as DeepLinkOutcome.Handled
        assertSame(deferred.link, resumed.link)
        assertEquals(9L, resumed.link[itemId])
        assertEquals(TrustLevel.SELF, resumed.link.trust)
        assertEquals(listOf("item"), routed)
        assertEquals(3, outcomes.size)
    }

    @Test fun `다른 처리기가 만든 링크는 resume 할 수 없다`() = runBlocking {
        val l = (handler().resolve(DeepLinkRequest("myapp://item/1")) as DeepLinkResolution.Resolved).link
        val e = runCatching { handler().resume(l) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertEquals("이 처리기가 만든 링크가 아니다: ResolvedDeepLink(route=item, pattern=myapp://item/{id}, trust=PUBLIC)", e!!.message)
    }

    @Test fun `proceed 두 번은 Failed - 이동이 두 번 일어나지 않는다`() = runBlocking {
        val twice = object : DeepLinkInterceptor {
            override suspend fun intercept(chain: DeepLinkChain): DeepLinkOutcome {
                chain.proceed()
                return chain.proceed()
            }
        }
        val out = handler(true, twice).handle(DeepLinkRequest("myapp://item/1")) as DeepLinkOutcome.Failed
        assertEquals(listOf("item"), routed)
        assertTrue(out.cause.cause is IllegalStateException)
        assertEquals("proceed() 는 한 번만 부를 수 있다", out.cause.cause!!.message)
    }

    @Test fun `라우터·인터셉터 예외는 Failed 로, 원인 보존`() = runBlocking {
        val boom = IllegalStateException("router")
        val out = handler(router = DeepLinkRouter { throw boom }).handle(DeepLinkRequest("myapp://item/1")) as DeepLinkOutcome.Failed
        assertSame(boom, out.cause.cause)
        assertEquals("딥링크 처리 실패: route 'item'", out.cause.message)
        assertEquals("item", out.link.route.id)
        assertEquals(listOf<DeepLinkOutcome>(out), outcomes)

        val failing = object : DeepLinkInterceptor {
            override suspend fun intercept(chain: DeepLinkChain): DeepLinkOutcome = throw UnsupportedOperationException("i")
        }
        val out2 = handler(true, failing).handle(DeepLinkRequest("myapp://item/1")) as DeepLinkOutcome.Failed
        assertTrue(out2.cause.cause is UnsupportedOperationException)
    }

    @Test fun `취소는 삼키지 않는다`() = runBlocking {
        val h = handler(router = DeepLinkRouter { throw CancellationException("stop") })
        val e = runCatching { h.handle(DeepLinkRequest("myapp://item/1")) }.exceptionOrNull()
        assertTrue(e is CancellationException)
        assertTrue(outcomes.isEmpty())
        val cancelValidator = Param<String>("v", ParamValidator { throw CancellationException("v") })
        val r = DeepLinkRoute.builder("r", "myapp://r/{v}").pathParam(cancelValidator).build()
        val h2 = deepLinkHandler { route(r); router { true } }
        assertTrue(runCatching { h2.resolve(DeepLinkRequest("myapp://r/x")) }.exceptionOrNull() is CancellationException)
    }

    @Test fun `Error 계열은 잡지 않는다`() = runBlocking {
        val h = handler(router = DeepLinkRouter { throw StackOverflowError() })
        assertTrue(runCatching { h.handle(DeepLinkRequest("myapp://item/1")) }.exceptionOrNull() is StackOverflowError)
    }

    @Test fun `동시 처리 - 결과가 섞이지 않는다`() = runBlocking {
        val h = handler()
        val results = withContext(Dispatchers.Default) {
            (1..500).map { n -> async { h.handle(DeepLinkRequest("myapp://item/$n")) } }.awaitAll()
        }
        results.forEachIndexed { index, outcome ->
            assertEquals((index + 1).toLong(), (outcome as DeepLinkOutcome.Handled).link[itemId])
        }
        assertEquals(500, routed.size)
        assertEquals(500, outcomes.size)
    }

    @Test fun `빌더 오류`() {
        assertIae("경로를 하나 이상 등록해야 한다") { deepLinkHandler { router { true } } }
        assertIae("경로 id 가 겹친다: [item]") { deepLinkHandler { route(item); route(item); router { true } } }
        assertIae("router 를 정해야 한다") { deepLinkHandler { route(item) } }
    }

    @Test fun `toString 은 URI·값을 드러내지 않는다`() {
        val request = DeepLinkRequest("myapp://reset/TOKEN?code=SECRET", self)
        assertEquals("DeepLinkRequest(uriLength=31, caller=$self)", request.toString())
        assertSame(CallerIdentity.UNKNOWN, DeepLinkRequest("x").caller)
        val l = link("myapp://item/123456?ref=SECRET")
        listOf(l.toString(), l.uri.toString(), DeepLinkOutcome.Handled(l).toString()).forEach {
            assertFalse(it, it.contains("SECRET") || it.contains("123456"))
        }
        val e = DeepLinkException("m", null)
        assertNull(e.cause)
    }
}
