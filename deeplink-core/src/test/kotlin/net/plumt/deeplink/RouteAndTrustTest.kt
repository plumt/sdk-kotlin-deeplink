package net.plumt.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAndTrustTest {

    @Test fun `경로 빌더 기본값과 선언`() {
        val id = Param.long("id")
        val ref = Param.string("ref")
        val route = DeepLinkRoute.builder("item", "myapp://item/{id}")
            .pathParam(id)
            .queryParam(ref)
            .queryParam(Param.int("page"), required = true)
            .requiredTrust(TrustLevel.FIRST_PARTY)
            .unknownQueryParameters(UnknownQueryPolicy.REJECT)
            .build()
        assertEquals("item", route.id)
        assertSame(id, route.pathParams["id"])
        assertFalse(route.queryParams.getValue("ref").required)
        assertTrue(route.queryParams.getValue("page").required)
        assertEquals(TrustLevel.FIRST_PARTY, route.requiredTrust)
        assertEquals(UnknownQueryPolicy.REJECT, route.unknownQueryPolicy)
        assertEquals("DeepLinkRoute(id=item, pattern=myapp://item/{id}, requiredTrust=FIRST_PARTY)", route.toString())
        assertEquals("QueryParamSpec(ref, required=false)", route.queryParams.getValue("ref").toString())

        val plain = deepLinkRoute("home", "myapp://home")
        assertEquals(TrustLevel.PUBLIC, plain.requiredTrust)
        assertEquals(UnknownQueryPolicy.DROP, plain.unknownQueryPolicy)
        assertTrue(plain.pathParams.isEmpty())
    }

    @Test fun `경로 빌더 오류`() {
        assertIae("경로 id 는 영숫자와 _.:- 1~64자여야 한다: ''") { DeepLinkRoute.builder("", "myapp://h") }
        assertIae("경로 id 는 영숫자와 _.:- 1~64자여야 한다: 'a b'") { DeepLinkRoute.builder("a b", "myapp://h") }
        assertIae("템플릿 myapp://h/{id} 에 없는 경로 파라미터다: 'other'") {
            DeepLinkRoute.builder("r", "myapp://h/{id}").pathParam(Param.string("other"))
        }
        assertIae("경로 파라미터 'id' 를 두 번 선언했다: route 'r'") {
            DeepLinkRoute.builder("r", "myapp://h/{id}").pathParam(Param.string("id")).pathParam(Param.int("id"))
        }
        assertIae("쿼리 파라미터 'q' 를 두 번 선언했다: route 'r'") {
            DeepLinkRoute.builder("r", "myapp://h").queryParam(Param.string("q")).queryParam(Param.string("q"))
        }
        assertIae("경로와 쿼리에 같은 이름을 선언했다: [id] (route 'r')") {
            DeepLinkRoute.builder("r", "myapp://h/{id}").queryParam(Param.string("id")).build()
        }
        assertIae("매처의 scheme 은 비어 있지 않은 소문자여야 한다: 'MyApp'") { DeepLinkRoute.builder("r", FakeMatcher("MyApp", "h")) }
        assertIae("매처의 scheme 은 비어 있지 않은 소문자여야 한다: ''") { DeepLinkRoute.builder("r", FakeMatcher("", "h")) }
        assertIae("매처의 host 는 비어 있지 않은 소문자여야 한다: 'H'") { DeepLinkRoute.builder("r", FakeMatcher("x", "H")) }
        assertIae("매처의 host 는 비어 있지 않은 소문자여야 한다: ''") { DeepLinkRoute.builder("r", FakeMatcher("x", "")) }
    }

    @Test fun `사용자 매처는 아무 이름의 경로 파라미터를 선언할 수 있다`() {
        val route = DeepLinkRoute.builder("r", FakeMatcher("x", "h")).pathParam(Param.int("n")).build()
        assertEquals(setOf("n"), route.pathParams.keys)
        assertEquals(0, FakeMatcher("x", "h").specificity)
    }

    @Test fun `신뢰 수준 비교 행렬`() {
        val levels = TrustLevel.values()
        for (actual in levels) for (required in levels) {
            assertEquals("$actual vs $required", levels.indexOf(actual) >= levels.indexOf(required), actual.satisfies(required))
        }
    }

    @Test fun `CallerIdentity 검증과 동등성`() {
        assertEquals(CallerIdentity(null, CallerVerification.NONE), CallerIdentity.UNKNOWN)
        assertEquals(CallerIdentity("a.b", CallerVerification.CLAIMED).hashCode(), CallerIdentity("a.b", CallerVerification.CLAIMED).hashCode())
        assertNotEquals(CallerIdentity("a.b", CallerVerification.CLAIMED), CallerIdentity("a.b", CallerVerification.OS_VERIFIED))
        assertNotEquals(CallerIdentity("a.b", CallerVerification.CLAIMED), CallerIdentity("a.c", CallerVerification.CLAIMED))
        assertFalse(CallerIdentity.UNKNOWN.equals("x"))
        assertEquals("CallerIdentity(packageName=a.b, verification=NONE)", CallerIdentity("a.b", CallerVerification.NONE).toString())
        assertIae("packageName 은 null 이거나 비어 있지 않아야 한다: ' '") { CallerIdentity(" ", CallerVerification.CLAIMED) }
        assertIae("packageName 이 null 이면 verification 은 NONE 이어야 한다: CLAIMED") { CallerIdentity(null, CallerVerification.CLAIMED) }
    }

    @Test fun `기본 신뢰 정책 행렬`() {
        val policy = TrustPolicy.standard("my.app")
        val expected = mapOf(
            CallerVerification.NONE to listOf(TrustLevel.PUBLIC, TrustLevel.PUBLIC),
            CallerVerification.CLAIMED to listOf(TrustLevel.PUBLIC, TrustLevel.PUBLIC),
            // H2 — OS 가 보증한 자기 패키지도, 서명이 맞는 자기 패키지도 중계일 수 있어 PUBLIC
            CallerVerification.OS_VERIFIED to listOf(TrustLevel.PUBLIC, TrustLevel.PUBLIC),
            CallerVerification.SIGNATURE_VERIFIED to listOf(TrustLevel.PUBLIC, TrustLevel.FIRST_PARTY),
            // 자기 앱이 발급한 토큰만 SELF. 다른 패키지 이름이 붙은 SELF_ISSUED 는 근거가 없다
            CallerVerification.SELF_ISSUED to listOf(TrustLevel.SELF, TrustLevel.PUBLIC),
        )
        assertEquals(CallerVerification.values().toSet(), expected.keys)
        for ((verification, levels) in expected) {
            assertEquals("self $verification", levels[0], policy.resolve(CallerIdentity("my.app", verification)))
            assertEquals("other $verification", levels[1], policy.resolve(CallerIdentity("other.app", verification)))
        }
        assertEquals(TrustLevel.PUBLIC, policy.resolve(CallerIdentity.UNKNOWN))
        val noSelf = TrustPolicy.standard(null)
        assertEquals(TrustLevel.PUBLIC, noSelf.resolve(CallerIdentity("my.app", CallerVerification.OS_VERIFIED)))
        assertEquals(TrustLevel.PUBLIC, noSelf.resolve(CallerIdentity("my.app", CallerVerification.SELF_ISSUED)))
        assertEquals(TrustLevel.FIRST_PARTY, noSelf.resolve(CallerIdentity("my.app", CallerVerification.SIGNATURE_VERIFIED)))
        assertIae("selfPackage 는 null 이거나 비어 있지 않아야 한다: ''") { TrustPolicy.standard("") }
    }

    @Test fun `거부 사유 코드`() {
        val all = listOf(
            Rejection.Malformed(MalformedReason.EMPTY) to "malformed",
            Rejection.TooLong(LimitKind.URI_LENGTH, 1, 2) to "too_long",
            Rejection.UnknownScheme("x") to "unknown_scheme",
            Rejection.UnknownHost("x", "h", null) to "unknown_host",
            Rejection.NoMatchingRoute("x", "h") to "no_matching_route",
            Rejection.InsufficientTrust("r", TrustLevel.SELF, TrustLevel.PUBLIC) to "insufficient_trust",
            Rejection.MissingParameter("r", "n") to "missing_parameter",
            Rejection.InvalidParameter("r", "n", "why") to "invalid_parameter",
            Rejection.DuplicateParameter("r", "n") to "duplicate_parameter",
            Rejection.UnexpectedParameter("r", "n") to "unexpected_parameter",
            Rejection.Intercepted("login") to "intercepted",
            Rejection.Custom("intent", "d") to "custom:intent",
        )
        all.forEach { (rejection, code) -> assertEquals(code, rejection.code) }
    }
}

internal class FakeMatcher(
    override val scheme: String,
    override val host: String,
    private val result: Map<String, String>? = emptyMap(),
    override val pattern: String = "fake",
) : RouteMatcher {
    override fun match(uri: DeepLinkUri): Map<String, String>? = result
}
