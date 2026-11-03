package net.plumt.deeplink

import net.plumt.deeplink.internal.TemplateMatcher

/**
 * URI 가 어떤 경로 정의에 맞는지 판정한다. 기본은 [template] 이고, 정규식·접두어 같은 다른 규칙이
 * 필요하면 직접 구현한다.
 *
 * 스킴·호스트는 판정 전에 처리기가 먼저 걸러 낸다. 그래서 구현은 [scheme]·[host] 로 자기 담당을
 * 밝혀야 하며, 밝히지 않은 스킴·호스트의 URI 는 구현까지 오지 않는다(거부가 기본).
 */
public interface RouteMatcher {

    /** 담당하는 소문자 스킴. */
    public val scheme: String

    /** 담당하는 소문자 호스트. */
    public val host: String

    /** 템플릿 표기. 로그·[toString] 에 값 대신 쓴다. 예: `myapp://item/{id}` */
    public val pattern: String

    /**
     * 여러 정의가 동시에 맞을 때 높은 쪽이 이긴다. 같으면 먼저 등록한 쪽이 이긴다.
     * 템플릿은 리터럴 조각 수를 쓴다 — `item/new` 가 `item/{id}` 보다 우선한다.
     */
    public val specificity: Int get() = 0

    /**
     * [uri] 가 맞으면 경로 파라미터 이름→디코딩 값을 돌려준다. 맞지 않으면 `null`.
     * 스킴·호스트는 이미 같다고 보장된다. 예외를 던지지 않는다.
     */
    public fun match(uri: DeepLinkUri): Map<String, String>?

    public companion object {
        /**
         * `scheme://host/literal/{param}` 형태의 템플릿.
         *
         * - 리터럴 조각은 디코딩한 값과 **대소문자까지** 같아야 한다
         * - `{name}` 조각은 비어 있지 않은 조각 하나에 맞는다. 이름은 영숫자와 `_-.`, 한 템플릿 안에서 유일
         * - 조각 수가 같아야 맞는다. 끝의 `/` 는 무시한다
         * - https 템플릿의 호스트는 App Link 도메인이다. 포트·쿼리·프래그먼트는 쓸 수 없다
         *
         * @throws IllegalArgumentException 템플릿 형식이 틀렸을 때
         */
        @JvmStatic
        public fun template(template: String): RouteMatcher = TemplateMatcher.compile(template)
    }
}
