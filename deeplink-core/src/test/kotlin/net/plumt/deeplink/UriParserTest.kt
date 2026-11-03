package net.plumt.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UriParserTest {

    private fun reject(input: String, rules: UriRules = UriRules.DEFAULT): Rejection {
        try {
            DeepLinkUri.parse(input, rules)
        } catch (e: IllegalArgumentException) {
            // 메시지에 입력 원문이 들어가면 안 된다
            if (input.length > 12) assertFalse("메시지에 원문이 샜다: ${e.message}", e.message!!.contains(input))
            val handler = handlerFor(rules)
            val resolution = handler.resolve(DeepLinkRequest(input))
            return (resolution as DeepLinkResolution.Rejected).rejection
        }
        fail("거부돼야 한다: $input")
        throw AssertionError()
    }

    private fun handlerFor(rules: UriRules) = deepLinkHandler {
        route(deepLinkRoute("any", "myapp://item/{id}"))
        router { true }
        uriRules(rules)
    }

    private fun malformed(input: String, reason: MalformedReason, rules: UriRules = UriRules.DEFAULT) {
        assertEquals(input, Rejection.Malformed(reason), reject(input, rules))
        assertNull(DeepLinkUri.parseOrNull(input, rules))
    }

    @Test fun `기본 분해 - 스킴·호스트 소문자, 경로·쿼리 디코딩`() {
        val uri = DeepLinkUri.parse("MyApp://ITEM.Example/a/b%20c?x=1&y=hello+world&x=2#frag")
        assertEquals("myapp", uri.scheme)
        assertEquals("item.example", uri.host)
        assertNull(uri.port)
        assertEquals(listOf("a", "b c"), uri.pathSegments)
        assertEquals(listOf("1", "2"), uri.queryParameters["x"])
        assertEquals("hello world", uri.queryParameter("y"))
        assertNull(uri.queryParameter("missing"))
    }

    @Test fun `경로의 플러스는 그대로, 쿼리의 플러스는 공백`() {
        val uri = DeepLinkUri.parse("myapp://h/a+b?q=a+b&r=a%2Bb")
        assertEquals("a+b", uri.pathSegments.single())
        assertEquals("a b", uri.queryParameter("q"))
        assertEquals("a+b", uri.queryParameter("r"))
    }

    @Test fun `경로 없음·슬래시 하나·끝 슬래시`() {
        assertEquals(emptyList<String>(), DeepLinkUri.parse("myapp://h").pathSegments)
        assertEquals(emptyList<String>(), DeepLinkUri.parse("myapp://h/").pathSegments)
        assertEquals(listOf("a"), DeepLinkUri.parse("myapp://h/a/").pathSegments)
        assertEquals(emptyList<String>(), DeepLinkUri.parse("myapp://h?").pathSegments)
        assertTrue(DeepLinkUri.parse("myapp://h?").queryParameters.isEmpty())
    }

    @Test fun `기본 포트는 지우고 다른 포트는 남긴다`() {
        assertNull(DeepLinkUri.parse("https://example.com:443/a").port)
        assertNull(DeepLinkUri.parse("http://example.com:80/a").port)
        assertEquals(8443, DeepLinkUri.parse("https://example.com:8443/a").port)
        assertEquals(443, DeepLinkUri.parse("myapp://h:443").port)
        assertEquals(1, DeepLinkUri.parse("myapp://h:1").port)
        assertEquals(65535, DeepLinkUri.parse("myapp://h:65535").port)
    }

    @Test fun `잘못된 포트`() {
        malformed("myapp://h:0/a", MalformedReason.INVALID_PORT)
        malformed("myapp://h:65536/a", MalformedReason.INVALID_PORT)
        malformed("myapp://h:123456/a", MalformedReason.INVALID_PORT)
        malformed("myapp://h:/a", MalformedReason.INVALID_PORT)
        malformed("myapp://h:8a/a", MalformedReason.INVALID_PORT)
        malformed("myapp://h:-1/a", MalformedReason.INVALID_PORT)
    }

    @Test fun `퍼센트 인코딩된 슬래시는 새 조각을 만들지 않고 기본 거부`() {
        malformed("myapp://item/a%2Fb", MalformedReason.ENCODED_SLASH)
        malformed("myapp://item/a%2fb", MalformedReason.ENCODED_SLASH)
        malformed("myapp://item/a%5Cb", MalformedReason.ENCODED_SLASH)
        val loose = UriRules(allowEncodedSlash = true)
        assertEquals(listOf("item", "a/b"), DeepLinkUri.parse("myapp://h/item/a%2Fb", loose).pathSegments)
    }

    @Test fun `이중 인코딩은 경로와 쿼리 이름에서 기본 거부`() {
        malformed("myapp://item/a%252Fb", MalformedReason.DOUBLE_ENCODING)
        malformed("myapp://item/%2541", MalformedReason.DOUBLE_ENCODING)
        malformed("myapp://item/1?%2541=x", MalformedReason.DOUBLE_ENCODING)
        val loose = UriRules(allowDoubleEncoding = true)
        assertEquals("a%2Fb", DeepLinkUri.parse("myapp://h/a%252Fb", loose).pathSegments.single())
        // 쿼리 값은 파서가 아니라 검증기가 판단한다
        assertEquals("%2F", DeepLinkUri.parse("myapp://h?q=%252F").queryParameter("q"))
    }

    @Test fun `유니코드 - 인코딩·원문 모두 받는다`() {
        val encoded = DeepLinkUri.parse("myapp://h/%ED%95%9C%EA%B8%80?q=%F0%9F%98%80")
        assertEquals("한글", encoded.pathSegments.single())
        assertEquals("\uD83D\uDE00", encoded.queryParameter("q"))
        val raw = DeepLinkUri.parse("myapp://h/한글?q=값")
        assertEquals("한글", raw.pathSegments.single())
        assertEquals("값", raw.queryParameter("q"))
        assertEquals(encoded.pathSegments, raw.pathSegments)
    }

    @Test fun `잘못된 퍼센트 인코딩과 잘못된 UTF-8`() {
        malformed("myapp://h/%", MalformedReason.INVALID_PERCENT_ENCODING)
        malformed("myapp://h/%2", MalformedReason.INVALID_PERCENT_ENCODING)
        malformed("myapp://h/%zz", MalformedReason.INVALID_PERCENT_ENCODING)
        malformed("myapp://h/%G1", MalformedReason.INVALID_PERCENT_ENCODING)
        malformed("myapp://h/%FF", MalformedReason.INVALID_PERCENT_ENCODING)
        malformed("myapp://h/%C3", MalformedReason.INVALID_PERCENT_ENCODING)
        malformed("myapp://h/%C3a", MalformedReason.INVALID_PERCENT_ENCODING)
        malformed("myapp://h/%C0%AF", MalformedReason.INVALID_PERCENT_ENCODING) // 지나치게 긴 UTF-8 로 쓴 '/'
        malformed("myapp://h/%ED%A0%80", MalformedReason.INVALID_PERCENT_ENCODING) // 서로게이트 반쪽
        malformed("myapp://h?q=%", MalformedReason.INVALID_PERCENT_ENCODING)
        malformed("myapp://h?%=1", MalformedReason.INVALID_PERCENT_ENCODING)
    }

    @Test fun `디코딩한 값의 제어·서식 문자`() {
        malformed("myapp://h/a%00", MalformedReason.ILLEGAL_CHARACTER)
        malformed("myapp://h/a%0A", MalformedReason.ILLEGAL_CHARACTER)
        malformed("myapp://h/a%0D%0Ab", MalformedReason.ILLEGAL_CHARACTER)
        malformed("myapp://h/a%E2%80%AE", MalformedReason.ILLEGAL_CHARACTER) // U+202E
        malformed("myapp://h/a%E2%80%8B", MalformedReason.ILLEGAL_CHARACTER) // U+200B
        malformed("myapp://h/a%09", MalformedReason.ILLEGAL_CHARACTER)
        malformed("myapp://h?q=%00", MalformedReason.ILLEGAL_CHARACTER)
        malformed("myapp://h?%0A=1", MalformedReason.ILLEGAL_CHARACTER)
        malformed("myapp://h?q=%EE%80%80", MalformedReason.ILLEGAL_CHARACTER) // 사설 영역
        // 값 안의 공백(U+0020)은 받는다
        assertEquals("a b", DeepLinkUri.parse("myapp://h/a%20b").pathSegments.single())
    }

    @Test fun `원문에 올 수 없는 문자`() {
        listOf(
            "myapp://h/a b", "myapp://h/a\nb", "myapp://h/a\tb", "myapp://h\\a", "myapp://h/a\\b",
            "myapp://h/<a>", "myapp://h/a\"", "myapp://h/`", "myapp://h/{x}", "myapp://h/a|b", "myapp://h/a^",
            "myapp://h/a\u202Eb", "myapp://h/a\u200Bb", "myapp://h/a\u0000", "myapp://h/a\u3000b", " myapp://h",
        ).forEach { malformed(it, MalformedReason.ILLEGAL_CHARACTER) }
    }

    @Test fun `점 조각은 원문·인코딩 모두 거부`() {
        listOf("myapp://h/.", "myapp://h/..", "myapp://h/a/../b", "myapp://h/%2E%2E", "myapp://h/%2e", "myapp://h/.%2E/x")
            .forEach { malformed(it, MalformedReason.DOT_SEGMENT) }
        assertEquals(listOf("..a", ".b"), DeepLinkUri.parse("myapp://h/..a/.b").pathSegments)
    }

    @Test fun `빈 조각`() {
        malformed("myapp://h//a", MalformedReason.EMPTY_SEGMENT)
        malformed("myapp://h/a//b", MalformedReason.EMPTY_SEGMENT)
        malformed("myapp://h/a//", MalformedReason.EMPTY_SEGMENT)
    }

    @Test fun `스킴·authority·호스트 형식`() {
        malformed("", MalformedReason.EMPTY)
        malformed("   ", MalformedReason.EMPTY)
        malformed("noscheme", MalformedReason.INVALID_SCHEME)
        malformed(":x", MalformedReason.INVALID_SCHEME)
        malformed("1app://h", MalformedReason.INVALID_SCHEME)
        malformed("my_app://h", MalformedReason.INVALID_SCHEME)
        malformed("myapp:item/1", MalformedReason.MISSING_AUTHORITY)
        malformed("myapp:/item/1", MalformedReason.MISSING_AUTHORITY)
        malformed("myapp://", MalformedReason.INVALID_HOST)
        malformed("myapp:///a", MalformedReason.INVALID_HOST)
        malformed("myapp://-h", MalformedReason.INVALID_HOST)
        malformed("myapp://h-", MalformedReason.INVALID_HOST)
        malformed("myapp://a..b", MalformedReason.INVALID_HOST)
        malformed("https://example.com./a", MalformedReason.INVALID_HOST)
        malformed("myapp://my_host", MalformedReason.INVALID_HOST)
        malformed("https://exämple.com", MalformedReason.INVALID_HOST)
        malformed("https://[::1]/a", MalformedReason.INVALID_HOST)
        malformed("https://ex%41mple.com", MalformedReason.INVALID_HOST)
        malformed("https://" + "a".repeat(64) + ".com", MalformedReason.INVALID_HOST)
        malformed("https://" + List(64) { "abc" }.joinToString("."), MalformedReason.INVALID_HOST)
        assertEquals("xn--mller-kva.de", DeepLinkUri.parse("https://xn--mller-kva.de").host)
        assertEquals("a1-b.c", DeepLinkUri.parse("myapp+x.y-z://A1-b.C").host)
    }

    @Test fun `사용자 정보는 거부 - 호스트 속이기`() {
        malformed("https://trusted.com@evil.com/a", MalformedReason.USERINFO_NOT_ALLOWED)
        malformed("https://user:pw@example.com", MalformedReason.USERINFO_NOT_ALLOWED)
        malformed("myapp://@h", MalformedReason.USERINFO_NOT_ALLOWED)
    }

    @Test fun `쿼리 형식`() {
        malformed("myapp://h?=1", MalformedReason.INVALID_QUERY)
        val uri = DeepLinkUri.parse("myapp://h?&&a&b=&c==d&")
        assertEquals(listOf(""), uri.queryParameters["a"])
        assertEquals(listOf(""), uri.queryParameters["b"])
        assertEquals(listOf("=d"), uri.queryParameters["c"])
        assertEquals(listOf("a", "b", "c"), uri.queryParameters.keys.toList())
    }

    @Test fun `프래그먼트는 버리지만 문자 검사는 한다`() {
        val uri = DeepLinkUri.parse("myapp://h/a?q=1#x?y=2/z")
        assertEquals(listOf("a"), uri.pathSegments)
        assertEquals(mapOf("q" to listOf("1")), uri.queryParameters)
        malformed("myapp://h/a#x y", MalformedReason.ILLEGAL_CHARACTER)
        assertEquals(listOf("a"), DeepLinkUri.parse("myapp://h/a#").pathSegments)
    }

    @Test fun `길이 상한 - 경계값에서 통과, 하나 넘으면 거부`() {
        val rules = UriRules(maxUriLength = 30, maxPathSegments = 3, maxSegmentLength = 4, maxQueryParameters = 2, maxQueryValueLength = 3)
        DeepLinkUri.parse("myapp://h/aaaa/b/c?x=123&y=1", rules)
        assertEquals(Rejection.TooLong(LimitKind.URI_LENGTH, 30, 31), reject("myapp://h/aaaa/b/c?x=123&y=12ab", rules))
        assertEquals(Rejection.TooLong(LimitKind.PATH_SEGMENTS, 3, 4), reject("myapp://h/a/b/c/d", rules))
        assertEquals(Rejection.TooLong(LimitKind.SEGMENT_LENGTH, 4, 5), reject("myapp://h/aaaaa", rules))
        assertEquals(Rejection.TooLong(LimitKind.QUERY_PARAMETERS, 2, 3), reject("myapp://h?a=1&b=2&c=3", rules))
        assertEquals(Rejection.TooLong(LimitKind.QUERY_VALUE_LENGTH, 3, 4), reject("myapp://h?a=1234", rules))
        // 길이는 디코딩한 글자 수로 센다
        DeepLinkUri.parse("myapp://h/%ED%95%9C%ED%95%9C?a=%ED%95%9C", UriRules(maxSegmentLength = 2, maxQueryValueLength = 1))
    }

    @Test fun `매우 긴 입력도 예외 없이 거부`() {
        val huge = "myapp://h/" + "a".repeat(1_000_000)
        assertEquals(Rejection.TooLong(LimitKind.URI_LENGTH, 2048, huge.length), reject(huge))
        val many = "myapp://h?" + (1..33).joinToString("&") { "k$it=v" }
        assertEquals(Rejection.TooLong(LimitKind.QUERY_PARAMETERS, 32, 33), reject(many))
    }

    @Test fun `redacted 는 값과 경로를 지운다`() {
        val uri = DeepLinkUri.parse("myapp://h:8080/reset/SECRET-TOKEN?token=abc&ref=x")
        val text = uri.redacted()
        assertEquals("myapp://h:8080/*/*?token=*&ref=*", text)
        assertEquals(text, uri.toString())
        assertFalse(text.contains("SECRET"))
        assertEquals("myapp://h", DeepLinkUri.parse("myapp://h").redacted())
        // 쿼리 이름의 줄바꿈·비 ASCII 는 로그 주입을 막도록 바꾼다
        assertEquals("myapp://h?a?b=*&??=*", DeepLinkUri.parse("myapp://h?a%3Cb=1&%ED%95%9C%EA%B8%80=2").redacted())
    }

    @Test fun `equals·hashCode`() {
        val a = DeepLinkUri.parse("MYAPP://H/a?x=1")
        val b = DeepLinkUri.parse("myapp://h/a/?x=1#f")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == DeepLinkUri.parse("myapp://h/a?x=2"))
        assertFalse(a == DeepLinkUri.parse("myapp://h/b?x=1"))
        assertFalse(a == DeepLinkUri.parse("myapp://g/a?x=1"))
        assertFalse(a == DeepLinkUri.parse("other://h/a?x=1"))
        assertFalse(a == DeepLinkUri.parse("myapp://h:9/a?x=1"))
        assertFalse(a.equals("myapp://h/a?x=1"))
        assertEquals(DeepLinkUri.parse("myapp://h:9").hashCode(), DeepLinkUri.parse("myapp://h:9").hashCode())
    }

    @Test fun `parse 의 예외 타입과 메시지`() {
        val e = runCatching { DeepLinkUri.parse("myapp://h/secret%2Fvalue") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertEquals("URI 를 받을 수 없다: Malformed(reason=ENCODED_SLASH)", e!!.message)
    }

    @Test fun `UriRules 상한은 1 이상`() {
        fun expect(name: String, block: () -> Unit) {
            val e = runCatching(block).exceptionOrNull()
            assertTrue(e is IllegalArgumentException)
            assertEquals("$name 은 1 이상이어야 한다: 0", e!!.message)
        }
        expect("maxUriLength") { UriRules(maxUriLength = 0) }
        expect("maxPathSegments") { UriRules(maxPathSegments = 0) }
        expect("maxSegmentLength") { UriRules(maxSegmentLength = 0) }
        expect("maxQueryParameters") { UriRules(maxQueryParameters = 0) }
        expect("maxQueryValueLength") { UriRules(maxQueryValueLength = 0) }
        UriRules(1, 1, 1, 1, 1)
        assertTrue(UriRules.DEFAULT.toString().contains("maxUriLength=2048"))
    }
}
