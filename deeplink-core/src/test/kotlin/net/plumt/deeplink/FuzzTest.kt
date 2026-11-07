package net.plumt.deeplink

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** 시드 고정 무작위 입력. 어떤 입력에도 예외 없이 결과 값이 나와야 한다. */
class FuzzTest {

    private val id = Param.long("id", 1L..Long.MAX_VALUE)
    private val q = Param.string("q", maxLength = 64)
    private val next = Param.httpsUrl("next", setOf("example.com"))
    private val handler = deepLinkHandler {
        route(DeepLinkRoute.builder("item", "myapp://item/{id}").pathParam(id).queryParam(q).build())
        route(DeepLinkRoute.builder("web", "https://example.com/a/{x}/b").queryParam(next).build())
        route(deepLinkRoute("home", "myapp://home"))
        trustPolicy(TrustPolicy.standard("my.app"))
        router { true }
    }

    private val alphabet = (
        "abcxyzAZ09-._~:/?#[]@!$&'()*+,;=%% \t\n\r\\\"<>{}|^`" +
            "\u0662\u0666\uFF12\uFF26\uFF0E\uFF0F\u212A\u0130" +
            "\u0000\u001f\u007f ​‮﻿𐀀한글😀"
        ).toList()

    private val seeds = listOf(
        "myapp://item/1?q=a", "https://example.com/a/x/b?next=https%3A%2F%2Fexample.com", "myapp://home",
        "myapp://item/%ED%95%9C", "MYAPP://ITEM/9", "https://u@example.com/a/x/b",
    )

    private fun randomString(r: Random): String {
        val len = r.nextInt(0, 80)
        return buildString { repeat(len) { append(alphabet[r.nextInt(alphabet.size)]) } }
    }

    private fun mutate(r: Random, s: String): String {
        val sb = StringBuilder(s)
        repeat(r.nextInt(1, 6)) {
            when (r.nextInt(4)) {
                0 -> if (sb.isNotEmpty()) sb.deleteCharAt(r.nextInt(sb.length))
                1 -> sb.insert(r.nextInt(sb.length + 1), alphabet[r.nextInt(alphabet.size)])
                2 -> sb.insert(r.nextInt(sb.length + 1), "%" + "%02X".format(r.nextInt(256)))
                else -> if (sb.isNotEmpty()) sb.setCharAt(r.nextInt(sb.length), alphabet[r.nextInt(alphabet.size)])
            }
        }
        return sb.toString()
    }

    private fun check(input: String) {
        val resolution = try {
            handler.resolve(DeepLinkRequest(input, CallerIdentity("my.app", CallerVerification.SELF_ISSUED)))
        } catch (e: Throwable) {
            fail("예외가 났다: ${e.javaClass.name} for ${input.toByteArray().joinToString()}")
            return
        }
        // DeepLinkUri.parse 는 IllegalArgumentException 만 던져야 하고, parseOrNull 과 일치해야 한다
        val parsed = runCatching { DeepLinkUri.parse(input) }
        parsed.exceptionOrNull()?.let { assertTrue("$it", it is IllegalArgumentException) }
        assertEquals(parsed.getOrNull(), DeepLinkUri.parseOrNull(input))
        if (resolution is DeepLinkResolution.Resolved) {
            val uri = resolution.link.uri
            // 통과한 URI 의 조각·값에는 제어 문자·서식 문자·경로 구분자가 없다
            uri.pathSegments.forEach { seg ->
                assertTrue(seg.isNotEmpty() && '/' !in seg && '\\' !in seg && seg != "." && seg != "..")
                // 이중 인코딩 방어: 하류가 어떤 디코더로 한 번 더 풀어도 바뀌지 않는다
                assertTrue('%' !in seg && java.text.Normalizer.isNormalized(seg, java.text.Normalizer.Form.NFKC))
                assertTrue(seg.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
            }
            uri.queryParameters.keys.forEach { assertTrue('%' !in it) }
            assertTrue(uri.host.all { it.code < 128 } && uri.scheme.all { it.code < 128 })
            // redacted 는 항상 ASCII 출력 가능 문자만
            assertTrue(uri.redacted().all { it.code in 0x21..0x7e })
        }
        // 통과한 모든 URI 는 정규형으로 되돌려 다시 읽어도 같다(길이 상한만 늘린다)
        DeepLinkUri.parseOrNull(input)?.let { uri ->
            val text = uri.toUriString()
            assertTrue(text.all { it.code in 0x21..0x7e })
            assertEquals(input, uri, DeepLinkUri.parse(text, UriRules(maxUriLength = 100_000)))
        }
    }

    @Test fun `무작위 문자열 2만 건`() {
        val r = Random(20261102)
        repeat(20_000) { check(randomString(r)) }
        repeat(2_000) { check("myapp://" + randomString(r)) }
        repeat(2_000) { check("https://example.com/" + randomString(r)) }
    }

    @Test fun `유효 URI 변형 2만 건`() {
        val r = Random(42)
        repeat(20_000) { check(mutate(r, seeds[r.nextInt(seeds.size)])) }
    }

    @Test fun `무작위 퍼센트 바이트 열`() {
        val r = Random(7)
        repeat(5_000) {
            val bytes = (0 until r.nextInt(1, 12)).joinToString("") { "%" + "%02X".format(r.nextInt(256)) }
            check("myapp://item/$bytes?q=$bytes")
        }
    }

    @Test fun `handle 도 예외 없이 결과를 낸다`() = runBlocking {
        val r = Random(99)
        repeat(3_000) {
            val outcome = handler.handle(DeepLinkRequest(mutate(r, seeds[r.nextInt(seeds.size)])))
            assertTrue(outcome is DeepLinkOutcome.Handled || outcome is DeepLinkOutcome.Rejected)
        }
    }
}
