package net.plumt.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

internal fun assertIae(expectedMessage: String?, block: () -> Unit) {
    val e = runCatching(block).exceptionOrNull()
    assertTrue("IllegalArgumentException 이어야 한다: $e", e is IllegalArgumentException)
    if (expectedMessage != null) assertEquals(expectedMessage, e!!.message)
}

class TemplateAndParamTest {

    @Test fun `템플릿 컴파일과 매칭`() {
        val m = RouteMatcher.template("MyApp://Item/{id}/detail/{tab}")
        assertEquals("myapp", m.scheme)
        assertEquals("item", m.host)
        assertEquals("myapp://item/{id}/detail/{tab}", m.pattern)
        assertEquals(1, m.specificity)
        assertEquals(mapOf("id" to "1", "tab" to "a b"), m.match(DeepLinkUri.parse("myapp://item/1/detail/a%20b")))
        assertNull(m.match(DeepLinkUri.parse("myapp://item/1/Detail/x")))
        assertNull(m.match(DeepLinkUri.parse("myapp://item/1/detail")))
        assertNull(m.match(DeepLinkUri.parse("myapp://item/1/detail/x/y")))
        assertEquals("RouteMatcher.template(myapp://item/{id}/detail/{tab})", m.toString())
    }

    @Test fun `템플릿 - 경로 없음과 https`() {
        val root = RouteMatcher.template("myapp://start")
        assertEquals(emptyMap<String, String>(), root.match(DeepLinkUri.parse("myapp://start/")))
        assertNull(root.match(DeepLinkUri.parse("myapp://start/x")))
        assertEquals(0, root.specificity)
        val web = RouteMatcher.template("https://example.com/p/{id}/")
        assertEquals("https://example.com/p/{id}", web.pattern)
        assertEquals("example.com", web.host)
        // 리터럴 조각은 인코딩된 형태로 써도 디코딩한 값과 견준다
        val encoded = RouteMatcher.template("myapp://h/%ED%95%9C")
        assertEquals(emptyMap<String, String>(), encoded.match(DeepLinkUri.parse("myapp://h/한")))
    }

    @Test fun `잘못된 템플릿`() {
        assertIae("템플릿 파라미터 이름이 겹친다: 'id' in 'myapp://h/{id}/{id}'") { RouteMatcher.template("myapp://h/{id}/{id}") }
        assertIae("템플릿 파라미터 이름이 틀렸다: '{}' in 'myapp://h/{}'") { RouteMatcher.template("myapp://h/{}") }
        assertIae("템플릿 파라미터 이름이 틀렸다: '{a b}' in 'myapp://h/{a b}'") { RouteMatcher.template("myapp://h/{a b}") }
        assertIae("템플릿의 중괄호가 짝이 맞지 않거나 조각 일부에만 걸려 있다: 'myapp://h/{id'") { RouteMatcher.template("myapp://h/{id") }
        assertIae("템플릿의 중괄호가 짝이 맞지 않거나 조각 일부에만 걸려 있다: 'myapp://h/{a/b}'") { RouteMatcher.template("myapp://h/{a/b}") }
        assertIae("템플릿 파라미터는 조각 전체를 차지해야 한다: 'myapp://h/x{id}'") { RouteMatcher.template("myapp://h/x{id}") }
        assertIae("템플릿 파라미터는 조각 전체를 차지해야 한다: 'myapp://h/{id}x'") { RouteMatcher.template("myapp://h/{id}x") }
        assertIae("템플릿 파라미터는 조각 전체를 차지해야 한다: 'myapp://h/__plumt_param_x'") { RouteMatcher.template("myapp://h/__plumt_param_x") }
        assertIae("템플릿 파라미터는 조각 전체를 차지해야 한다: 'myapp://h/a__plumt_param_0'") { RouteMatcher.template("myapp://h/a__plumt_param_0") }
        assertIae("템플릿에는 쿼리·프래그먼트를 쓸 수 없다: 'myapp://h/a?x=1'") { RouteMatcher.template("myapp://h/a?x=1") }
        assertIae("템플릿에는 쿼리·프래그먼트를 쓸 수 없다: 'myapp://h/a#f'") { RouteMatcher.template("myapp://h/a#f") }
        assertIae("템플릿에는 포트를 쓸 수 없다: 'https://example.com:8443/a'") { RouteMatcher.template("https://example.com:8443/a") }
        assertIae("템플릿이 올바른 URI 가 아니다: 'myapp:/a'") { RouteMatcher.template("myapp:/a") }
        assertIae("템플릿이 올바른 URI 가 아니다: 'https://u@example.com'") { RouteMatcher.template("https://u@example.com") }
        assertIae("템플릿이 올바른 URI 가 아니다: 'myapp://h/../a'") { RouteMatcher.template("myapp://h/../a") }
    }

    private fun <T : Any> Param<T>.ok(raw: String): T = (validator.validate(raw) as ParamCheck.Valid<T>).value
    private fun Param<*>.bad(raw: String): String = (validator.validate(raw) as ParamCheck.Invalid).reason

    @Test fun `파라미터 이름 규칙`() {
        Param.string("a")
        Param.string("A_b-c.9")
        Param.string("x".repeat(64))
        assertIae("파라미터 이름은 영숫자와 _-. 1~64자여야 한다: ''") { Param.string("") }
        assertIae("파라미터 이름은 영숫자와 _-. 1~64자여야 한다: '${"x".repeat(65)}'") { Param.string("x".repeat(65)) }
        assertIae("파라미터 이름은 영숫자와 _-. 1~64자여야 한다: 'a b'") { Param.int("a b") }
        assertEquals("Param(id)", Param.long("id").toString())
    }

    @Test fun `string 검증기`() {
        val p = Param.string("s", maxLength = 3)
        assertEquals("abc", p.ok("abc"))
        assertEquals("한글값", p.ok("한글값"))
        assertEquals("비어 있다", p.bad(""))
        assertEquals("길이 4 가 상한 3 을 넘는다", p.bad("abcd"))
        assertEquals("이중 인코딩된 값이다", p.bad("%2F"))
        assertEquals("%2F", Param.string("s", allowPercentEscapes = true).ok("%2F"))
        // H1 — '%' 가 남은 값은 16진 꼴이 아니어도 거부한다
        assertEquals("이중 인코딩된 값이다", Param.string("s").bad("100%"))
        assertEquals("100%", Param.string("s", allowPercentEscapes = true).ok("100%"))
        val pattern = Param.string("s", pattern = Regex("[a-z]+"))
        assertEquals("abc", pattern.ok("abc"))
        assertEquals("형식이 맞지 않는다", pattern.bad("abc1"))
        Param.string("s", maxLength = 1)
        assertIae("maxLength 는 1 이상이어야 한다: 0") { Param.string("s", maxLength = 0) }
    }

    @Test fun `int·long 검증기 - 경계와 표기`() {
        val i = Param.int("i", 1..10)
        assertEquals(1, i.ok("1"))
        assertEquals(10, i.ok("10"))
        assertEquals("범위 1..10 밖이다", i.bad("0"))
        assertEquals("범위 1..10 밖이다", i.bad("11"))
        listOf("", "+1", "01", "1.0", "1e3", " 1", "--1", "-", "0x1", "١").forEach { assertEquals(it, "정수가 아니다", i.bad(it)) }
        val all = Param.int("i")
        assertEquals(Int.MAX_VALUE, all.ok("2147483647"))
        assertEquals(Int.MIN_VALUE, all.ok("-2147483648"))
        assertEquals(0, all.ok("0"))
        assertEquals("정수가 아니다", all.bad("2147483648"))
        assertEquals("정수가 아니다", all.bad("-0".let { "-01" }))
        val l = Param.long("l", 0L..5L)
        assertEquals(5L, l.ok("5"))
        assertEquals("범위 0..5 밖이다", l.bad("-1"))
        assertEquals(Long.MAX_VALUE, Param.long("l").ok("9223372036854775807"))
        assertEquals("정수가 아니다", Param.long("l").bad("9223372036854775808"))
        assertEquals("정수가 아니다", Param.long("l").bad("x"))
        assertIae("range 가 비었다: 1..0") { Param.int("i", 1..0) }
        assertIae("range 가 비었다: 1..0") { Param.long("l", 1L..0L) }
    }

    @Test fun `boolean·oneOf·uuid`() {
        val b = Param.boolean("b")
        assertEquals(true, b.ok("true"))
        assertEquals(false, b.ok("false"))
        assertEquals("true·false 가 아니다", b.bad("TRUE"))
        assertEquals("true·false 가 아니다", b.bad("1"))
        val o = Param.oneOf("o", setOf("a", "b"))
        assertEquals("a", o.ok("a"))
        assertEquals("허용 값이 아니다", o.bad("A"))
        assertIae("allowed 가 비었다") { Param.oneOf("o", emptySet()) }
        val u = Param.uuid("u")
        val id = UUID.randomUUID()
        assertEquals(id, u.ok(id.toString()))
        assertEquals(id, u.ok(id.toString().uppercase()))
        assertEquals("UUID 가 아니다", u.bad("1-1-1-1-1"))
        assertEquals("UUID 가 아니다", u.bad(id.toString().replace("-", "")))
    }

    @Test fun `httpsUrl - 오픈 리다이렉트 방어`() {
        val p = Param.httpsUrl("next", setOf("example.com", "m.example.com"))
        assertEquals("example.com", p.ok("https://example.com/a?b=1").host)
        assertEquals("m.example.com", p.ok("https://M.example.com:443/").host)
        assertEquals("URL 형식이 아니다", p.bad("//evil.com"))
        assertEquals("URL 형식이 아니다", p.bad("https://example.com@evil.com"))
        assertEquals("URL 형식이 아니다", p.bad("https://example.com\\@evil.com"))
        assertEquals("URL 형식이 아니다", p.bad("javascript:alert(1)"))
        assertEquals("https 가 아니다", p.bad("http://example.com"))
        assertEquals("https 가 아니다", p.bad("myapp://example.com"))
        assertEquals("포트를 지정할 수 없다", p.bad("https://example.com:8443"))
        assertEquals("허용하지 않은 호스트다", p.bad("https://example.com.evil.com"))
        assertEquals("허용하지 않은 호스트다", p.bad("https://evil-example.com"))
        assertEquals("허용하지 않은 호스트다", p.bad("https://a.example.com"))
        assertIae("allowedHosts 가 비었다") { Param.httpsUrl("n", emptySet()) }
        assertIae("allowedHosts 의 항목이 소문자 호스트가 아니다: 'Example.com'") { Param.httpsUrl("n", setOf("Example.com")) }
        assertIae("allowedHosts 의 항목이 소문자 호스트가 아니다: 'example.com/a'") { Param.httpsUrl("n", setOf("example.com/a")) }
        assertIae("allowedHosts 의 항목이 소문자 호스트가 아니다: 'example.com:1'") { Param.httpsUrl("n", setOf("example.com:1")) }
        assertIae("allowedHosts 의 항목이 소문자 호스트가 아니다: 'a@b'") { Param.httpsUrl("n", setOf("a@b")) }
    }

    @Test fun `ParamCheck 값 동등성`() {
        assertEquals(ParamCheck.Valid(1), ParamCheck.Valid(1))
        assertEquals(ParamCheck.Invalid("x"), ParamCheck.Invalid("x"))
    }
}
