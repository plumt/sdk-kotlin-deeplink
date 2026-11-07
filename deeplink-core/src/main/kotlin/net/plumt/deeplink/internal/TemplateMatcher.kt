package net.plumt.deeplink.internal

import net.plumt.deeplink.DeepLinkUri
import net.plumt.deeplink.RouteMatcher
import net.plumt.deeplink.UriRules

internal class TemplateMatcher private constructor(
    override val scheme: String,
    override val host: String,
    override val pattern: String,
    private val segments: List<Segment>,
) : RouteMatcher {

    sealed interface Segment {
        class Literal(val value: String) : Segment
        class Variable(val name: String) : Segment
    }

    /** 템플릿에 나오는 파라미터 이름. */
    val parameterNames: List<String> = segments.filterIsInstance<Segment.Variable>().map { it.name }

    override val specificity: Int = segments.count { it is Segment.Literal }

    override fun match(uri: DeepLinkUri): Map<String, String>? {
        if (uri.pathSegments.size != segments.size) return null
        val params = LinkedHashMap<String, String>()
        segments.forEachIndexed { index, segment ->
            val actual = uri.pathSegments[index]
            when (segment) {
                is Segment.Literal -> if (segment.value != actual) return null
                is Segment.Variable -> params[segment.name] = actual
            }
        }
        return params
    }

    override fun toString(): String = "RouteMatcher.template($pattern)"

    companion object {
        private val NAME = Regex("[A-Za-z0-9_.-]{1,64}")

        // 중괄호는 클래스 안팎 모두 이스케이프한다 — Android(ICU)는 짝 없는 `}` 를 문법 오류로 거부한다(JDK 는 리터럴로 받는다)
        private val PLACEHOLDER = Regex("\\{([^\\{\\}/]*)\\}")

        fun compile(template: String): TemplateMatcher {
            // {..} 를 자리표시자로 바꾼 뒤 일반 URI 규칙으로 검증한다 — 템플릿과 실제 URI 가 같은 규칙을 따른다
            val names = ArrayList<String>()
            val placeholderTemplate = PLACEHOLDER.replace(template) { m ->
                val name = m.groupValues[1]
                require(NAME.matches(name)) { "템플릿 파라미터 이름이 틀렸다: '{$name}' in '$template'" }
                require(name !in names) { "템플릿 파라미터 이름이 겹친다: '$name' in '$template'" }
                names += name
                "${PLACEHOLDER_PREFIX}${names.size - 1}"
            }
            require(!placeholderTemplate.contains('{') && !placeholderTemplate.contains('}')) {
                "템플릿의 중괄호가 짝이 맞지 않거나 조각 일부에만 걸려 있다: '$template'"
            }
            require(!placeholderTemplate.contains('?') && !placeholderTemplate.contains('#')) {
                "템플릿에는 쿼리·프래그먼트를 쓸 수 없다: '$template'"
            }
            val uri = DeepLinkUri.parseOrNull(placeholderTemplate, UriRules.DEFAULT)
            requireNotNull(uri) { "템플릿이 올바른 URI 가 아니다: '$template'" }
            require(uri.port == null) { "템플릿에는 포트를 쓸 수 없다: '$template'" }

            val segments = uri.pathSegments.map { raw ->
                if (raw.startsWith(PLACEHOLDER_PREFIX)) {
                    val index = raw.removePrefix(PLACEHOLDER_PREFIX).toIntOrNull()
                    require(index != null && index in names.indices) { "템플릿 파라미터는 조각 전체를 차지해야 한다: '$template'" }
                    Segment.Variable(names[index])
                } else {
                    require(!raw.contains(PLACEHOLDER_PREFIX)) { "템플릿 파라미터는 조각 전체를 차지해야 한다: '$template'" }
                    Segment.Literal(raw)
                }
            }
            val pattern = buildString {
                append(uri.scheme).append("://").append(uri.host)
                segments.forEach { s ->
                    append('/')
                    append(if (s is Segment.Variable) "{${s.name}}" else (s as Segment.Literal).value)
                }
            }
            return TemplateMatcher(uri.scheme, uri.host, pattern, segments)
        }

        // 일반 URI 문자만으로 된, 실제 경로와 겹칠 일이 없는 표식
        private const val PLACEHOLDER_PREFIX = "__plumt_param_"
    }
}
