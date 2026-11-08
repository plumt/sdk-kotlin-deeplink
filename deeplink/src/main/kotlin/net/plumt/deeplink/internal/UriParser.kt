package net.plumt.deeplink.internal

import net.plumt.deeplink.DeepLinkUri
import net.plumt.deeplink.LimitKind
import net.plumt.deeplink.MalformedReason
import net.plumt.deeplink.Rejection
import net.plumt.deeplink.UriRules
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * RFC 3986 의 일반 구문을 따르되 보안상 모호한 형태를 모두 거부하는 파서.
 *
 * `java.net.URI` 는 사용자 정보·IPv6·느슨한 디코딩을 허용하고, `android.net.Uri` 는 검증 없이
 * 받아들인다. 둘 다 "같은 문자열을 서로 다르게 읽는" 틈을 남기므로 직접 분해한다.
 */
internal object UriParser {

    sealed interface Result {
        class Ok(val uri: DeepLinkUri) : Result
        class Fail(val rejection: Rejection) : Result
    }

    // 대소문자 접기 전에 원문으로 검사한다 — 켈빈 기호(U+212A)처럼 소문자화하면 ASCII 가 되는 글자를 막는다
    private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")
    private val HOST_LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
    private val HOST_RAW = Regex("[A-Za-z0-9.-]+")
    private val NUMERIC_LABEL = Regex("[0-9]+|0x[0-9a-f]*")
    private val IPV4_OCTET = Regex("0|[1-9][0-9]{0,2}")
    private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)
    private const val MAX_PORT = 65535

    fun parse(input: String, rules: UriRules): Result {
        if (input.isBlank()) return malformed(MalformedReason.EMPTY)
        if (input.length > rules.maxUriLength) {
            return Result.Fail(Rejection.TooLong(LimitKind.URI_LENGTH, rules.maxUriLength, input.length))
        }
        if (input.any(::isIllegalRaw) || hasLoneSurrogate(input)) return malformed(MalformedReason.ILLEGAL_CHARACTER)

        val colon = input.indexOf(':')
        if (colon <= 0) return malformed(MalformedReason.INVALID_SCHEME)
        val rawScheme = input.substring(0, colon)
        if (!SCHEME.matches(rawScheme)) return malformed(MalformedReason.INVALID_SCHEME)
        val scheme = rawScheme.lowercase()

        var rest = input.substring(colon + 1)
        if (!rest.startsWith("//")) return malformed(MalformedReason.MISSING_AUTHORITY)
        rest = rest.substring(2)

        // 프래그먼트 → 쿼리 → 경로 순으로 떼어 낸다. 프래그먼트는 버린다
        val hashAt = rest.indexOf('#')
        if (hashAt >= 0) rest = rest.substring(0, hashAt)
        val queryAt = rest.indexOf('?')
        val rawQuery = if (queryAt >= 0) rest.substring(queryAt + 1) else null
        if (queryAt >= 0) rest = rest.substring(0, queryAt)
        val pathAt = rest.indexOf('/')
        val authority = if (pathAt >= 0) rest.substring(0, pathAt) else rest
        val rawPath = if (pathAt >= 0) rest.substring(pathAt) else ""

        val (host, port) = when (val a = parseAuthority(authority, scheme)) {
            is AuthorityResult.Fail -> return Result.Fail(a.rejection)
            is AuthorityResult.Ok -> a.host to a.port
        }

        val segments = when (val p = parsePath(rawPath, rules)) {
            is PathResult.Fail -> return Result.Fail(p.rejection)
            is PathResult.Ok -> p.segments
        }

        val query = when (val q = parseQuery(rawQuery, rules)) {
            is QueryResult.Fail -> return Result.Fail(q.rejection)
            is QueryResult.Ok -> q.values
        }

        return Result.Ok(DeepLinkUri(scheme, host, port, segments, query))
    }

    private fun malformed(reason: MalformedReason): Result = Result.Fail(Rejection.Malformed(reason))

    private sealed interface AuthorityResult {
        class Ok(val host: String, val port: Int?) : AuthorityResult
        class Fail(val rejection: Rejection) : AuthorityResult
    }

    private fun parseAuthority(authority: String, scheme: String): AuthorityResult {
        if (authority.contains('@')) return AuthorityResult.Fail(Rejection.Malformed(MalformedReason.USERINFO_NOT_ALLOWED))
        val portAt = authority.lastIndexOf(':')
        val hostPart = if (portAt >= 0) authority.substring(0, portAt) else authority
        val portPart = if (portAt >= 0) authority.substring(portAt + 1) else null

        if (hostPart.isEmpty() || hostPart.length > 253 || !HOST_RAW.matches(hostPart)) {
            return authorityFail(MalformedReason.INVALID_HOST)
        }
        val host = hostPart.lowercase()
        val labels = host.split('.')
        if (!labels.all { HOST_LABEL.matches(it) }) return authorityFail(MalformedReason.INVALID_HOST)
        // 마지막 라벨이 숫자면 브라우저는 IPv4 로 읽는다(0x7f.1·127.1·2130706433). 정규형 점 네 개 표기만 받는다
        if (NUMERIC_LABEL.matches(labels.last()) && !isCanonicalIpv4(labels)) return authorityFail(MalformedReason.INVALID_HOST)

        val port: Int? = if (portPart == null) {
            null
        } else {
            // 앞자리 0(':0443')은 같은 포트의 다른 표기다
            if (portPart.isEmpty() || portPart.length > 5 || !portPart.all { it in '0'..'9' } || portPart[0] == '0') {
                return authorityFail(MalformedReason.INVALID_PORT)
            }
            val value = portPart.toInt()
            if (value !in 1..MAX_PORT) return authorityFail(MalformedReason.INVALID_PORT)
            value
        }
        val normalizedPort = if (port != null && DEFAULT_PORTS[scheme] == port) null else port
        return AuthorityResult.Ok(host, normalizedPort)
    }

    private fun authorityFail(reason: MalformedReason) = AuthorityResult.Fail(Rejection.Malformed(reason))

    private fun isCanonicalIpv4(labels: List<String>): Boolean =
        labels.size == 4 && labels.all { IPV4_OCTET.matches(it) && it.toInt() <= 255 }

    private sealed interface PathResult {
        class Ok(val segments: List<String>) : PathResult
        class Fail(val rejection: Rejection) : PathResult
    }

    private fun parsePath(rawPath: String, rules: UriRules): PathResult {
        if (rawPath.isEmpty() || rawPath == "/") return PathResult.Ok(emptyList())
        // 앞의 '/' 를 떼고, 끝의 '/' 하나는 관대하게 무시한다
        val trimmed = rawPath.substring(1).removeSuffix("/")
        val rawSegments = trimmed.split('/')
        if (rawSegments.size > rules.maxPathSegments) {
            return PathResult.Fail(Rejection.TooLong(LimitKind.PATH_SEGMENTS, rules.maxPathSegments, rawSegments.size))
        }
        val decoded = ArrayList<String>(rawSegments.size)
        for (raw in rawSegments) {
            if (raw.isEmpty()) return pathFail(MalformedReason.EMPTY_SEGMENT)
            val value = decode(raw, plusAsSpace = false) ?: return pathFail(MalformedReason.INVALID_PERCENT_ENCODING)
            if (value == "." || value == "..") return pathFail(MalformedReason.DOT_SEGMENT)
            if (!rules.allowEncodedSlash && (value.contains('/') || value.contains('\\'))) {
                return pathFail(MalformedReason.ENCODED_SLASH)
            }
            if (!rules.allowDoubleEncoding && value.contains('%')) return pathFail(MalformedReason.DOUBLE_ENCODING)
            if (value.any(::isIllegalDecoded)) return pathFail(MalformedReason.ILLEGAL_CHARACTER)
            if (!rules.allowUnnormalized && !isNfkc(value)) return pathFail(MalformedReason.NOT_NORMALIZED)
            if (value.length > rules.maxSegmentLength) {
                return PathResult.Fail(Rejection.TooLong(LimitKind.SEGMENT_LENGTH, rules.maxSegmentLength, value.length))
            }
            decoded += value
        }
        return PathResult.Ok(decoded)
    }

    private fun pathFail(reason: MalformedReason) = PathResult.Fail(Rejection.Malformed(reason))

    private sealed interface QueryResult {
        class Ok(val values: Map<String, List<String>>) : QueryResult
        class Fail(val rejection: Rejection) : QueryResult
    }

    private fun parseQuery(rawQuery: String?, rules: UriRules): QueryResult {
        if (rawQuery.isNullOrEmpty()) return QueryResult.Ok(emptyMap())
        val pairs = rawQuery.split('&').filter { it.isNotEmpty() }
        if (pairs.size > rules.maxQueryParameters) {
            return QueryResult.Fail(Rejection.TooLong(LimitKind.QUERY_PARAMETERS, rules.maxQueryParameters, pairs.size))
        }
        val result = LinkedHashMap<String, MutableList<String>>()
        for (pair in pairs) {
            val eq = pair.indexOf('=')
            val rawName = if (eq >= 0) pair.substring(0, eq) else pair
            val rawValue = if (eq >= 0) pair.substring(eq + 1) else ""
            val name = decode(rawName, plusAsSpace = true) ?: return queryFail(MalformedReason.INVALID_PERCENT_ENCODING)
            val value = decode(rawValue, plusAsSpace = true) ?: return queryFail(MalformedReason.INVALID_PERCENT_ENCODING)
            if (name.isEmpty()) return queryFail(MalformedReason.INVALID_QUERY)
            if (!rules.allowDoubleEncoding && name.contains('%')) return queryFail(MalformedReason.DOUBLE_ENCODING)
            if (name.any(::isIllegalDecoded) || value.any(::isIllegalDecoded)) {
                return queryFail(MalformedReason.ILLEGAL_CHARACTER)
            }
            if (!rules.allowUnnormalized && !isNfkc(name)) return queryFail(MalformedReason.NOT_NORMALIZED)
            if (value.length > rules.maxQueryValueLength) {
                return QueryResult.Fail(Rejection.TooLong(LimitKind.QUERY_VALUE_LENGTH, rules.maxQueryValueLength, value.length))
            }
            result.getOrPut(name) { ArrayList(1) } += value
        }
        return QueryResult.Ok(result)
    }

    private fun queryFail(reason: MalformedReason) = QueryResult.Fail(Rejection.Malformed(reason))

    /** NFKC 정규화해도 그대로인가. 아니면 하류의 정규화(파일 시스템·IDNA)가 `．．` 를 `..` 로 바꾼다. */
    private fun isNfkc(value: String): Boolean = value.all { it.code < 128 } || Normalizer.isNormalized(value, Normalizer.Form.NFKC)

    /**
     * 퍼센트 디코딩을 정확히 한 번 한다. 잘못된 이스케이프·잘못된 UTF-8 이면 `null`.
     * `URLDecoder` 는 잘못된 UTF-8 을 U+FFFD 로 바꿔 삼키므로 쓰지 않는다.
     * 16진 숫자는 RFC 3986 의 HEXDIG(ASCII `0-9A-Fa-f`)만 받는다 — `Character.digit` 은 아랍·전각 숫자도 받는다.
     */
    fun decode(raw: String, plusAsSpace: Boolean): String? {
        if (raw.indexOf('%') < 0) return if (plusAsSpace) raw.replace('+', ' ') else raw
        val out = StringBuilder(raw.length)
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%') {
                if (i + 2 >= raw.length) return null
                val hi = hexValue(raw[i + 1])
                val lo = hexValue(raw[i + 2])
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                if (bytes.size() > 0) {
                    out.append(decodeUtf8(bytes.toByteArray()) ?: return null)
                    bytes.reset()
                }
                out.append(if (plusAsSpace && c == '+') ' ' else c)
                i++
            }
        }
        if (bytes.size() > 0) out.append(decodeUtf8(bytes.toByteArray()) ?: return null)
        return out.toString()
    }

    /** UTF-8 퍼센트 인코딩. unreserved(`A-Za-z0-9-._~`) 외에는 모두 `%XX`(대문자)로 바꾼다. */
    fun encode(value: String): String {
        val out = StringBuilder(value.length)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") {
                out.append(c)
            } else {
                out.append('%').append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        // 잘못된 UTF-8 은 호출부가 INVALID_PERCENT_ENCODING 거부로 바꾼다
        null
    }

    /** 원문(디코딩 전)에 올 수 없는 문자. ASCII 밖 글자는 받되 공백·제어·서식 문자는 막는다. */
    private fun isIllegalRaw(c: Char): Boolean =
        c in RAW_FORBIDDEN || isIllegalDecoded(c) || c == ' '

    /** 디코딩한 값에 올 수 없는 문자. 공백(U+0020)은 값 안에서 허용한다. */
    private fun isIllegalDecoded(c: Char): Boolean {
        if (c == ' ') return false
        if (Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)) return true
        val type = Character.getType(c)
        // 양방향 재정렬(U+202E 등)·폭 없는 문자(U+200B 등)는 사람이 보는 값과 실제 값을 다르게 만든다
        return type == Character.FORMAT.toInt() || type == Character.UNASSIGNED.toInt() ||
            type == Character.PRIVATE_USE.toInt()
    }

    /** 짝 없는 서로게이트. UTF-8 로 옮기면 `?` 로 바뀌어 같은 값을 다시 만들 수 없다. */
    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return true
                i++
            }
        }
        return false
    }

    private val RAW_FORBIDDEN = setOf('\\', '"', '<', '>', '`', '{', '}', '|', '^')

    /** 로그에 쓸 쿼리 이름을 ASCII 영숫자·`_-.` 외에는 `?` 로 바꿔 둔다(로그 주입 방지). */
    fun encodeForLog(name: String): String =
        name.map { if (it.isLetterOrDigit() && it.code < 128 || it in "_-.") it else '?' }.joinToString("")
}
