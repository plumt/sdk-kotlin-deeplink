package net.plumt.deeplink

import net.plumt.deeplink.internal.AppLinkReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigAndTypesTest {

    private val item = deepLinkRoute("item", "myapp://item/{id}")

    @Test fun `설정 기본값`() {
        val config = deepLinkConfig { route(item) }
        assertEquals(listOf(item), config.routes)
        assertNull(config.trustPolicy)
        assertNull(config.callerVerifier)
        assertEquals(UriRules.DEFAULT, config.uriRules)
        assertEquals(setOf("android.intent.action.VIEW"), config.allowedActions)
        assertEquals(DeepLinkConfig.DEFAULT_QUEUE_CAPACITY, config.queueCapacity)
        assertTrue(config.externalHosts.isEmpty())
        assertTrue(config.trustedCallers.isEmpty())
        assertTrue(config.rules.isEmpty())
        assertEquals(
            "DeepLinkConfig(routes=[item], interceptors=0, listeners=0, rules=0, trustedCallers=[], " +
                "allowedActions=[android.intent.action.VIEW], queueCapacity=8, externalHosts=0)",
            config.toString(),
        )
    }

    @Test fun `설정 빌더와 검증`() {
        val a = object : DeepLinkInterceptor {
            override val priority = 5
            override suspend fun intercept(chain: DeepLinkChain) = chain.proceed()
        }
        val b = object : DeepLinkInterceptor {
            override suspend fun intercept(chain: DeepLinkChain) = chain.proceed()
        }
        val config = DeepLinkConfig.builder()
            .routes(listOf(item))
            .interceptor(a)
            .interceptor(b)
            .listener { }
            .rule { null }
            .allowedActions(setOf("x.y.OPEN"))
            .queueCapacity(1)
            .externalHosts(listOf("example.com", "a.example.com"))
            .trustedCallers(listOf(TrustedCaller("a.b", setOf("A".repeat(64)))))
            .build()
        assertEquals(listOf(b, a), config.interceptors)
        assertEquals(setOf("example.com", "a.example.com"), config.externalHosts)
        assertIae("경로를 하나 이상 등록해야 한다") { deepLinkConfig { } }
        assertIae("경로 id 가 겹친다: [item]") { deepLinkConfig { route(item); route(item) } }
        assertIae("allowedActions 가 비었다") { deepLinkConfig { route(item); allowedActions(emptySet()) } }
        assertIae("queueCapacity 는 1 이상이어야 한다: 0") { deepLinkConfig { route(item); queueCapacity(0) } }
        assertIae("trustedCallers 에 같은 패키지가 두 번 있다: [a.b]") {
            deepLinkConfig {
                route(item)
                trustedCaller(TrustedCaller("a.b", setOf("A".repeat(64))))
                trustedCaller(TrustedCaller("a.b", setOf("B".repeat(64))))
            }
        }
        val verifier = object : CallerVerifier {
            override fun identify(activity: android.app.Activity, intent: android.content.Intent, initialLaunch: Boolean) = CallerIdentity.UNKNOWN
        }
        assertIae("callerVerifier 를 직접 정하면 trustedCallers 는 그 구현이 다룬다 — 함께 쓸 수 없다") {
            deepLinkConfig { route(item); callerVerifier(verifier); trustedCaller(TrustedCaller("a.b", setOf("A".repeat(64)))) }
        }
        listOf("Example.com", "example.com:443", "example.com/path", "a@example.net", "", "*.example.com").forEach { host ->
            assertIae("externalHost 는 소문자 호스트여야 한다: '$host'") { deepLinkConfig { route(item); externalHost(host) } }
        }
    }

    @Test fun `URI 규칙 빌더`() {
        val rules = UriRules.builder().maxUriLength(10).allowEncodedSlash(true).build()
        assertEquals(10, rules.maxUriLength)
        assertTrue(rules.allowEncodedSlash)
        val copy = rules.toBuilder().maxPathSegments(2).build()
        assertEquals(10, copy.maxUriLength)
        assertEquals(2, copy.maxPathSegments)
        assertTrue(copy.allowEncodedSlash)
        assertEquals(UriRules.DEFAULT.toString(), UriRules.builder().build().toString())
    }

    @Test fun `규칙은 resolve 에도 적용되고 예외는 거부로`() {
        val blocked = Rejection.Custom("campaign_closed", "기간 지남")
        val handler = deepLinkHandler {
            route(item)
            route(deepLinkRoute("other", "myapp://other"))
            rule { link -> if (link.route.id == "item") blocked else null }
            router { true }
        }
        assertEquals(DeepLinkResolution.Rejected(blocked), handler.resolve(DeepLinkRequest("myapp://item/1")))
        assertTrue(handler.resolve(DeepLinkRequest("myapp://other")) is DeepLinkResolution.Resolved)
        assertEquals("custom:campaign_closed", blocked.code)
        val throwing = deepLinkHandler { route(item); rule { throw IllegalStateException("값") }; router { true } }
        assertEquals(
            DeepLinkResolution.Rejected(Rejection.Custom("rule_exception", "IllegalStateException")),
            throwing.resolve(DeepLinkRequest("myapp://item/1")),
        )
    }

    @Test fun `열린 집합 값`() {
        assertEquals("PUBLIC", TrustLevel.PUBLIC.name)
        assertEquals("SELF", TrustLevel.SELF.toString())
        assertTrue(TrustLevel.SELF.satisfies(TrustLevel.FIRST_PARTY))
        assertFalse(TrustLevel.PUBLIC.satisfies(TrustLevel.FIRST_PARTY))
        assertEquals(CallerVerification("SELF_ISSUED"), CallerVerification.SELF_ISSUED)
        assertEquals(CallerVerification("NONE").hashCode(), CallerVerification.NONE.hashCode())
        assertNotEquals(CallerVerification("X"), CallerVerification.NONE)
        assertFalse(CallerVerification.NONE.equals("NONE"))
        assertIae("verification 이름은 영문 대문자·숫자·_ 1~64자여야 한다: 'bad'") { CallerVerification("bad") }
        assertEquals("REJECT", UnknownQueryPolicy.REJECT.name)
        assertEquals("DROP", UnknownQueryPolicy.DROP.toString())
        assertEquals("ENCODED_SLASH", MalformedReason.ENCODED_SLASH.name)
        assertEquals("URI_LENGTH", LimitKind.URI_LENGTH.name)
        assertEquals("HAS_SELECTOR", UnsafeIntentReason.HAS_SELECTOR.name)
        assertEquals("UNREADABLE", ExtraInvalidReason.UNREADABLE.name)
        assertEquals("VERIFIED", AppLinkHostState.VERIFIED.name)
    }

    @Test fun `App Link 상태`() {
        val status = AppLinkStatus.Available(
            true,
            mapOf("a.com" to AppLinkHostState.VERIFIED, "b.com" to AppLinkHostState.SELECTED, "c.com" to AppLinkHostState.NONE),
        )
        assertTrue(status.isVerified("a.com"))
        assertFalse(status.isVerified("b.com"))
        assertTrue(status.opensInApp("a.com"))
        assertTrue(status.opensInApp("b.com"))
        assertFalse(status.opensInApp("c.com"))
        assertFalse(status.opensInApp("d.com"))
        val off = AppLinkStatus.Available(false, status.hosts)
        assertFalse(off.opensInApp("a.com"))
        assertTrue(off.isVerified("a.com"))
        assertEquals(status, AppLinkStatus.Available(true, status.hosts))
        assertEquals(status.hashCode(), AppLinkStatus.Available(true, status.hosts).hashCode())
        assertNotEquals(status, off)
        assertTrue(status.toString().startsWith("Available(linkHandlingAllowed=true"))
        assertEquals("Unsupported", AppLinkStatus.Unsupported.toString())
        assertEquals("Unavailable", AppLinkStatus.Unavailable.toString())
        assertEquals(AppLinkHostState.NONE, AppLinkReader.hostState(0))
        assertEquals(AppLinkHostState.SELECTED, AppLinkReader.hostState(1))
        assertEquals(AppLinkHostState.VERIFIED, AppLinkReader.hostState(2))
        assertEquals(AppLinkHostState.NONE, AppLinkReader.hostState(99))
    }

    @Test fun `결과 타입의 동등성과 문자열`() {
        val r = Rejection.UnknownScheme("x")
        val values: List<Pair<Any, Any>> = listOf(
            DeepLinkResolution.Rejected(r) to DeepLinkResolution.Rejected(Rejection.UnknownScheme("x")),
            DeepLinkOutcome.Rejected(r) to DeepLinkOutcome.Rejected(Rejection.UnknownScheme("x")),
            ExternalLinkResult.Rejected(r) to ExternalLinkResult.Rejected(Rejection.UnknownScheme("x")),
            ExternalOpenResult.Rejected(r) to ExternalOpenResult.Rejected(Rejection.UnknownScheme("x")),
            ParamCheck.Valid(1) to ParamCheck.Valid(1),
            ParamCheck.Invalid("a") to ParamCheck.Invalid("a"),
            ExtraRead.Present("a") to ExtraRead.Present("a"),
            ExtraRead.Invalid(ExtraInvalidReason.TOO_LONG) to ExtraRead.Invalid(ExtraInvalidReason.TOO_LONG),
            IntentReadResult.Unsafe(UnsafeIntentReason.HAS_SELECTOR) to IntentReadResult.Unsafe(UnsafeIntentReason.HAS_SELECTOR),
            IntentReadResult.Link(DeepLinkRequest("a://b").let { it }) to IntentReadResult.Link(DeepLinkRequest("a://b")),
            TrustedCaller("a.b", setOf("A".repeat(64))) to TrustedCaller("a.b", setOf("A".repeat(64)), emptySet()),
        )
        values.forEachIndexed { i, (a, b) ->
            if (i == 9) {
                // 요청은 같은 문자열이어도 다른 객체다(신뢰가 옮겨 붙지 않게 동일성으로 비교한다)
                assertNotEquals(a, b)
            } else {
                assertEquals(a, b)
                assertEquals(a.hashCode(), b.hashCode())
            }
            assertFalse(a.equals(Any()))
        }
        assertEquals("Valid", ParamCheck.Valid("secret").toString())
        assertEquals("Present", ExtraRead.Present("secret").toString())
        assertEquals("Absent", ExtraRead.Absent.toString())
        assertEquals("NotDeepLink", IntentReadResult.NotDeepLink.toString())
        assertEquals("Opened", ExternalOpenResult.Opened.toString())
        assertEquals("NoActivity", ExternalOpenResult.NoActivity.toString())
        assertEquals("Rejected(rejection=UnknownScheme(scheme=x))", ExternalOpenResult.Rejected(r).toString())
        assertEquals("Rejected(rejection=UnknownScheme(scheme=x))", ExternalLinkResult.Rejected(r).toString())
        assertEquals("Invalid(reason=TOO_LONG)", ExtraRead.Invalid(ExtraInvalidReason.TOO_LONG).toString())
        assertEquals("Unsafe(reason=HAS_SELECTOR)", IntentReadResult.Unsafe(UnsafeIntentReason.HAS_SELECTOR).toString())
        assertTrue(IntentReadResult.Link(DeepLinkRequest("a://secret")).toString().contains("uriLength=10"))
        assertNotEquals(TrustedCaller("a.b", setOf("A".repeat(64))), TrustedCaller("a.b", setOf("B".repeat(64))))
    }

    @Test fun `모든 거부 사유의 동등성과 문자열`() {
        val samples: List<Rejection> = listOf(
            Rejection.Malformed(MalformedReason.EMPTY),
            Rejection.TooLong(LimitKind.URI_LENGTH, 1, 2),
            Rejection.UnknownScheme("s"),
            Rejection.UnknownHost("s", "h", 1),
            Rejection.NoMatchingRoute("s", "h"),
            Rejection.InsufficientTrust("r", TrustLevel.SELF, TrustLevel.PUBLIC),
            Rejection.MissingParameter("r", "n"),
            Rejection.InvalidParameter("r", "n", "x"),
            Rejection.DuplicateParameter("r", "n"),
            Rejection.UnexpectedParameter("r", "n"),
            Rejection.Intercepted("x"),
            Rejection.Custom("c", "d"),
        )
        samples.forEach { r ->
            assertEquals(r, copyOf(r))
            assertEquals(r.hashCode(), copyOf(r).hashCode())
            assertFalse(r.equals(Any()))
            assertTrue(r.toString().startsWith(r.javaClass.simpleName + "("))
            assertEquals(r.code, codeOf(r))
        }
    }

    /** 새 사유를 더하면 `else` 가 없어 여기서 컴파일이 깨진다 — 문서 일치 테스트와 함께 고친다. */
    private fun copyOf(r: Rejection): Rejection = when (r) {
        is Rejection.Malformed -> Rejection.Malformed(r.reason)
        is Rejection.TooLong -> Rejection.TooLong(r.limit, r.max, r.actual)
        is Rejection.UnknownScheme -> Rejection.UnknownScheme(r.scheme)
        is Rejection.UnknownHost -> Rejection.UnknownHost(r.scheme, r.host, r.port)
        is Rejection.NoMatchingRoute -> Rejection.NoMatchingRoute(r.scheme, r.host)
        is Rejection.InsufficientTrust -> Rejection.InsufficientTrust(r.routeId, r.required, r.actual)
        is Rejection.MissingParameter -> Rejection.MissingParameter(r.routeId, r.name)
        is Rejection.InvalidParameter -> Rejection.InvalidParameter(r.routeId, r.name, r.reason)
        is Rejection.DuplicateParameter -> Rejection.DuplicateParameter(r.routeId, r.name)
        is Rejection.UnexpectedParameter -> Rejection.UnexpectedParameter(r.routeId, r.name)
        is Rejection.Intercepted -> Rejection.Intercepted(r.reason)
        is Rejection.Custom -> Rejection.Custom(r.customCode, r.detail)
    }

    private fun codeOf(r: Rejection): String = when (r) {
        is Rejection.Malformed -> "malformed"
        is Rejection.TooLong -> "too_long"
        is Rejection.UnknownScheme -> "unknown_scheme"
        is Rejection.UnknownHost -> "unknown_host"
        is Rejection.NoMatchingRoute -> "no_matching_route"
        is Rejection.InsufficientTrust -> "insufficient_trust"
        is Rejection.MissingParameter -> "missing_parameter"
        is Rejection.InvalidParameter -> "invalid_parameter"
        is Rejection.DuplicateParameter -> "duplicate_parameter"
        is Rejection.UnexpectedParameter -> "unexpected_parameter"
        is Rejection.Intercepted -> "intercepted"
        is Rejection.Custom -> "custom:${r.customCode}"
    }

    @Test fun `처리 결과 동등성`() {
        val handler = deepLinkHandler { route(item); router { true } }
        val link = (handler.resolve(DeepLinkRequest("myapp://item/1")) as DeepLinkResolution.Resolved).link
        val e = DeepLinkException("x", null)
        val outcomes = listOf(
            DeepLinkOutcome.Handled(link) to DeepLinkOutcome.Handled(link),
            DeepLinkOutcome.Unhandled(link) to DeepLinkOutcome.Unhandled(link),
            DeepLinkOutcome.Deferred(link, "r") to DeepLinkOutcome.Deferred(link, "r"),
            DeepLinkOutcome.Failed(link, e) to DeepLinkOutcome.Failed(link, e),
            DeepLinkResolution.Resolved(link) to DeepLinkResolution.Resolved(link),
        )
        outcomes.forEach { (a, b) ->
            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
            assertFalse(a.equals(Any()))
            assertTrue(a.toString().contains("route=item"))
        }
    }
}
