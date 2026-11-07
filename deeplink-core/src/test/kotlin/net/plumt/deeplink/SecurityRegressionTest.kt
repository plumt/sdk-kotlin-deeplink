package net.plumt.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * 적대적 리뷰에서 실제로 통과했던 우회 입력을 그대로 고정한다. 각 테스트 이름 앞의 기호는 리뷰 항목이다.
 */
class SecurityRegressionTest {

    private fun rejected(input: String, rules: UriRules = UriRules.DEFAULT) =
        assertNull("거부돼야 한다: $input", DeepLinkUri.parseOrNull(input, rules))

    private fun reason(input: String, rules: UriRules = UriRules.DEFAULT): Rejection {
        val handler = deepLinkHandler {
            route(deepLinkRoute("any", "myapp://host/{a}"))
            uriRules(rules)
            router { true }
        }
        return (handler.resolve(DeepLinkRequest(input)) as DeepLinkResolution.Rejected).rejection
    }

    private fun malformed(reason: MalformedReason) = Rejection.Malformed(reason)

    // H1 — 디코더가 비 ASCII 숫자(아랍·전각)를 16진수로 읽었다

    @Test fun `H1 비 ASCII 16진 숫자는 이스케이프가 아니다`() {
        rejected("myapp://host/%٢E")             // 아랍 숫자 2
        rejected("myapp://host/a%٢F")            // 예전엔 '/' 로 디코딩
        rejected("myapp://host/%٦١dmin")    // 예전엔 'admin'
        rejected("myapp://host/%２Ｆ")        // 전각 2·F
        rejected("myapp://host/a?q=%２Ｆ")    // 쿼리 값도 같은 디코더
        rejected("myapp://host/a?%٢E=1")
    }

    @Test fun `H1 이중 인코딩 - 디코딩 후 퍼센트가 남으면 경로·쿼리 이름은 거부`() {
        rejected("myapp://host/%25٢E%25٢E/x")
        rejected("myapp://host/%25２Ｆ")
        rejected("myapp://host/50%25")
        rejected("myapp://host/a?%25٢E=1")
        // 허용 옵션을 켜면 받는다
        assertEquals(malformed(MalformedReason.DOUBLE_ENCODING), reason("myapp://host/%25\u0662E%25\u0662E/x"))
        assertEquals(malformed(MalformedReason.DOUBLE_ENCODING), reason("myapp://host/a?%25zz=1"))
        val lenient = UriRules(allowDoubleEncoding = true)
        assertEquals(listOf("%٢E"), DeepLinkUri.parse("myapp://host/%25٢E", lenient).pathSegments)
    }

    @Test fun `H1 쿼리 값의 이중 인코딩은 Param string 이 막는다`() {
        val uri = DeepLinkUri.parse("myapp://host/a?next=%25２Ｆ%25２Ｆetc")
        val raw = uri.queryParameter("next")!!
        // JDK URLDecoder 는 전각 숫자도 16진으로 읽는다 — 하류에서 한 번 더 풀면 '//etc' 가 된다
        assertEquals("//etc", URLDecoder.decode(raw, "UTF-8"))
        assertEquals(ParamCheck.Invalid("이중 인코딩된 값이다"), Param.string("next").validator.validate(raw))
        assertEquals(ParamCheck.Invalid("이중 인코딩된 값이다"), Param.string("next").validator.validate("50%"))
        assertEquals(ParamCheck.Valid("50%"), Param.string("next", allowPercentEscapes = true).validator.validate("50%"))
    }

    // M2 — 유니코드 대소문자 접기로 비 ASCII 스킴·호스트가 ASCII 로 바뀌었다

    @Test fun `M2 켈빈 기호 K 는 소문자 k 로 접지 않는다`() {
        rejected("myapp://Kitchen/a")
        rejected("Kyapp://h/a")
        rejected("https://Kitchen.com/x")
        rejected("myapp://hİ/a")                  // 점 있는 대문자 I
        val next = Param.httpsUrl("next", setOf("kitchen.com"))
        assertTrue(next.validator.validate("https://Kitchen.com/x") is ParamCheck.Invalid)
        assertEquals("kitchen.com", (next.validator.validate("https://KITCHEN.com/x") as ParamCheck.Valid).value.host)
    }

    // M4 — 전각·호환 문자로 된 값이 정규화 없이 통과했다

    @Test fun `M4 NFKC 로 바뀌는 경로 조각과 쿼리 이름은 거부`() {
        rejected("myapp://host/．．/x")                // 전각 점 두 개 → '..'
        rejected("myapp://host/%EF%BC%8E%EF%BC%8E/x")          // 같은 것을 인코딩
        rejected("myapp://host/a／b")                      // 전각 슬래시
        rejected("myapp://host/cafe%CC%81")                    // NFD 조합 문자
        rejected("myapp://host/a?ｎｅｘｔ=1")  // 전각 'next'
        assertEquals(listOf("한글", "café", "😀"), DeepLinkUri.parse("myapp://host/한글/caf%C3%A9/😀").pathSegments)
        assertEquals(malformed(MalformedReason.NOT_NORMALIZED), reason("myapp://host/\uFF0E\uFF0E"))
        assertEquals(malformed(MalformedReason.NOT_NORMALIZED), reason("myapp://host/a?\uFF4E=1"))
        // 쿼리 값은 정규화 검사를 하지 않는다(일본어 입력의 전각 영숫자 등) — 문서화된 동작
        assertEquals("\uFF0E\uFF0E", DeepLinkUri.parse("myapp://host/a?q=\uFF0E\uFF0E").queryParameter("q"))
        // 옵션으로 끈다
        val lenient = UriRules(allowUnnormalized = true)
        assertEquals(listOf("\uFF0E\uFF0E"), DeepLinkUri.parse("myapp://host/\uFF0E\uFF0E", lenient).pathSegments)
        assertEquals(listOf("1"), DeepLinkUri.parse("myapp://host/a?\uFF4E=1", lenient).queryParameters["\uFF4E"])
        assertTrue(lenient.toString().endsWith("allowUnnormalized=true)"))
    }

    @Test fun `M3 짝 없는 서로게이트 원문은 되돌릴 수 없으므로 거부`() {
        assertEquals(malformed(MalformedReason.ILLEGAL_CHARACTER), reason("myapp://host/a\uD800"))
        assertEquals(malformed(MalformedReason.ILLEGAL_CHARACTER), reason("myapp://host/\uDC00a"))
        assertEquals(malformed(MalformedReason.ILLEGAL_CHARACTER), reason("myapp://host/a?q=\uD800x"))
        assertEquals(listOf("\uD800\uDC00"), DeepLinkUri.parse("myapp://host/\uD800\uDC00").pathSegments)
    }

    // M3 — 검증한 값을 다시 URL 로 만들 방법이 없어 다른 파서로 원문을 다시 읽게 됐다

    @Test fun `M3 toUriString 은 정규형을 내고 다시 읽으면 같은 값이다`() {
        val uri = DeepLinkUri.parse("MyApp://H.Example:8080/a%20b/%ED%95%9C/x~y?x=1&y=a+b&x=2&z=%26%3D%23%3F&e=&n")
        assertEquals("myapp://h.example:8080/a%20b/%ED%95%9C/x~y?x=1&x=2&y=a%20b&z=%26%3D%23%3F&e=&n=", uri.toUriString())
        assertEquals(uri, DeepLinkUri.parse(uri.toUriString()))
        assertEquals("https://example.com", DeepLinkUri.parse("https://EXAMPLE.com:443").toUriString())
        assertEquals("https://example.com/p", DeepLinkUri.parse("https://example.com/p/#frag").toUriString())
        // 허용 옵션으로 받은 '%'·'/' 도 되돌릴 수 있다
        val loose = UriRules(allowEncodedSlash = true, allowDoubleEncoding = true)
        val odd = DeepLinkUri.parse("myapp://h/a%2Fb/50%25", loose)
        assertEquals("myapp://h/a%2Fb/50%25", odd.toUriString())
        assertEquals(odd, DeepLinkUri.parse(odd.toUriString(), loose))
        // httpsUrl 로 통과한 값을 그대로 이동에 쓴다
        val next = Param.httpsUrl("next", setOf("example.com"))
        val valid = next.validator.validate("https://example.com/a?b=c%20d") as ParamCheck.Valid
        assertEquals("https://example.com/a?b=c%20d", valid.value.toUriString())
    }

    // L2 — '-0' 이 0 으로 받아졌다

    @Test fun `L2 음의 영은 정수가 아니다`() {
        assertEquals(ParamCheck.Invalid("정수가 아니다"), Param.int("n").validator.validate("-0"))
        assertEquals(ParamCheck.Invalid("정수가 아니다"), Param.long("n").validator.validate("-0"))
        assertEquals(ParamCheck.Valid(0), Param.int("n").validator.validate("0"))
        assertEquals(ParamCheck.Valid(-1L), Param.long("n").validator.validate("-1"))
    }

    // L3 — ':0443' 을 443 으로 읽었다

    @Test fun `L3 앞자리 0 포트는 거부`() {
        rejected("https://h.com:0443/a")
        rejected("myapp://h:080/a")
        rejected("myapp://h:00/a")
        assertEquals(8080, DeepLinkUri.parse("myapp://h:8080/a").port)
    }

    // L4 — 숫자·16진 IPv4 꼴 호스트를 이름으로 받았다

    @Test fun `L4 정규형이 아닌 IPv4 꼴 호스트는 거부`() {
        rejected("https://0x7f.0.0.1/a")
        rejected("https://127.1/a")
        rejected("https://0177.0.0.1/a")
        rejected("https://1.2.3.256/a")
        rejected("https://2130706433/a")
        rejected("https://example.123/a")
        rejected("https://a.0x1f/a")
        assertNotNull(DeepLinkUri.parseOrNull("https://127.0.0.1/a"))
        assertNotNull(DeepLinkUri.parseOrNull("https://203.0.113.255/a"))
        assertNotNull(DeepLinkUri.parseOrNull("https://123.example.com/a"))
        assertNotNull(DeepLinkUri.parseOrNull("myapp://h1/a"))
    }
}
