package net.plumt.deeplink.internal

import net.plumt.deeplink.DeepLinkChain
import net.plumt.deeplink.DeepLinkConfig
import net.plumt.deeplink.DeepLinkException
import net.plumt.deeplink.DeepLinkOutcome
import net.plumt.deeplink.DeepLinkRequest
import net.plumt.deeplink.DeepLinkResolution
import net.plumt.deeplink.DeepLinkRoute
import net.plumt.deeplink.DeepLinkRouter
import net.plumt.deeplink.DeepLinkRule
import net.plumt.deeplink.Param
import net.plumt.deeplink.ParamCheck
import net.plumt.deeplink.Rejection
import net.plumt.deeplink.ResolvedDeepLink
import net.plumt.deeplink.TrustPolicy
import net.plumt.deeplink.UnknownQueryPolicy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * 검증 → 경로 매칭 → 신뢰 판정 → 파라미터 검증 → 규칙 → 인터셉터 사슬 → 라우터. Android 클래스를 쓰지 않는다.
 * [net.plumt.deeplink.DeepLinks] 의 기본 구현과 가짜 구현이 함께 쓴다. 불변이며 스레드 안전하다.
 */
internal class DeepLinkEngine(
    private val config: DeepLinkConfig,
    private val trustPolicy: TrustPolicy,
) {
    private val routes: List<DeepLinkRoute> = config.routes
    private val schemes: Set<String> = routes.mapTo(HashSet()) { it.matcher.scheme }
    private val hostsByScheme: Map<String, Set<String>> =
        routes.groupBy { it.matcher.scheme }.mapValues { (_, list) -> list.mapTo(HashSet()) { it.matcher.host } }

    fun resolve(request: DeepLinkRequest): DeepLinkResolution {
        val uri = when (val parsed = UriParser.parse(request.uri, config.uriRules)) {
            is UriParser.Result.Fail -> return DeepLinkResolution.Rejected(parsed.rejection)
            is UriParser.Result.Ok -> parsed.uri
        }
        if (uri.scheme !in schemes) return rejected(Rejection.UnknownScheme(uri.scheme))
        if (uri.port != null || uri.host !in hostsByScheme.getValue(uri.scheme)) {
            return rejected(Rejection.UnknownHost(uri.scheme, uri.host, uri.port))
        }

        var best: DeepLinkRoute? = null
        var bestParams: Map<String, String>? = null
        for (route in routes) {
            if (route.matcher.scheme != uri.scheme || route.matcher.host != uri.host) continue
            val params = route.matcher.match(uri) ?: continue
            if (best == null || route.matcher.specificity > best.matcher.specificity) {
                best = route
                bestParams = params
            }
        }
        if (best == null || bestParams == null) return rejected(Rejection.NoMatchingRoute(uri.scheme, uri.host))

        // 신뢰를 먼저 본다 — 자격 없는 발신자의 값은 검증기에 넘기지도 않는다
        val trust = trustPolicy.resolve(request.caller)
        if (!trust.satisfies(best.requiredTrust)) {
            return rejected(Rejection.InsufficientTrust(best.id, best.requiredTrust, trust))
        }

        val values = LinkedHashMap<String, Any>()
        for ((name, raw) in bestParams) {
            val param = best.pathParams[name] ?: Param.string(name)
            when (val check = validate(param, raw)) {
                is ParamCheck.Invalid -> return rejected(Rejection.InvalidParameter(best.id, name, check.reason))
                is ParamCheck.Valid -> values[name] = check.value
            }
        }

        for ((name, list) in uri.queryParameters) {
            val spec = best.queryParams[name]
            if (spec == null) {
                if (best.unknownQueryPolicy == UnknownQueryPolicy.REJECT) {
                    return rejected(Rejection.UnexpectedParameter(best.id, name))
                }
                continue
            }
            if (list.size > 1) return rejected(Rejection.DuplicateParameter(best.id, name))
            when (val check = validate(spec.param, list.single())) {
                is ParamCheck.Invalid -> return rejected(Rejection.InvalidParameter(best.id, name, check.reason))
                is ParamCheck.Valid -> values[name] = check.value
            }
        }
        best.queryParams.values.firstOrNull { it.required && it.param.name !in values }?.let {
            return rejected(Rejection.MissingParameter(best.id, it.param.name))
        }

        val link = ResolvedDeepLink(best, trust, request.caller, uri, values, this)
        config.rules.forEach { rule -> check(rule, link)?.let { return rejected(it) } }
        return DeepLinkResolution.Resolved(link)
    }

    /** 소비자 검증기가 예외를 던져도 처리기가 죽지 않게 실패로 바꾼다. 사유는 예외 타입 이름만. */
    private fun validate(param: Param<*>, raw: String): ParamCheck<Any> = try {
        param.validator.validate(raw)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 외부 입력 검증 실패와 같게 거부한다. 메시지엔 값이 섞일 수 있어 타입명만 싣는다
        ParamCheck.Invalid("검증기 예외 ${e.javaClass.simpleName}")
    }

    /** 소비자 규칙. 예외는 거부로 바꾸고 타입 이름만 싣는다. */
    private fun check(rule: DeepLinkRule, link: ResolvedDeepLink): Rejection? = try {
        rule.check(link)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 규칙이 실패한 링크는 받지 않는다(거부 쪽으로 실패)
        Rejection.Custom("rule_exception", e.javaClass.simpleName)
    }

    private fun rejected(rejection: Rejection) = DeepLinkResolution.Rejected(rejection)

    suspend fun handle(request: DeepLinkRequest, router: DeepLinkRouter): DeepLinkOutcome {
        val outcome = when (val resolution = resolve(request)) {
            is DeepLinkResolution.Rejected -> DeepLinkOutcome.Rejected(resolution.rejection)
            is DeepLinkResolution.Resolved -> run(resolution.link, router)
        }
        config.listeners.forEach { it.onOutcome(outcome) }
        return outcome
    }

    suspend fun resume(link: ResolvedDeepLink, router: DeepLinkRouter): DeepLinkOutcome {
        require(link.owner === this) { "이 DeepLinks 가 만든 링크가 아니다: $link" }
        val outcome = run(link, router)
        config.listeners.forEach { it.onOutcome(outcome) }
        return outcome
    }

    private suspend fun run(link: ResolvedDeepLink, router: DeepLinkRouter): DeepLinkOutcome = try {
        RealChain(link, router, 0).proceed()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DeepLinkOutcome.Failed(link, DeepLinkException("딥링크 처리 실패: route '${link.route.id}'", e))
    }

    private inner class RealChain(
        override val link: ResolvedDeepLink,
        private val router: DeepLinkRouter,
        private val index: Int,
    ) : DeepLinkChain {
        private val called = AtomicBoolean(false)

        override suspend fun proceed(): DeepLinkOutcome {
            check(called.compareAndSet(false, true)) { "proceed() 는 한 번만 부를 수 있다" }
            val interceptors = config.interceptors
            if (index < interceptors.size) {
                return interceptors[index].intercept(RealChain(link, router, index + 1))
            }
            return if (router.route(link)) DeepLinkOutcome.Handled(link) else DeepLinkOutcome.Unhandled(link)
        }
    }
}
