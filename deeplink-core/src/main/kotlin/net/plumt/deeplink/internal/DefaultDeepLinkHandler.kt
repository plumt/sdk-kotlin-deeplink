package net.plumt.deeplink.internal

import net.plumt.deeplink.DeepLinkChain
import net.plumt.deeplink.DeepLinkException
import net.plumt.deeplink.DeepLinkHandler
import net.plumt.deeplink.DeepLinkInterceptor
import net.plumt.deeplink.DeepLinkListener
import net.plumt.deeplink.DeepLinkOutcome
import net.plumt.deeplink.DeepLinkRequest
import net.plumt.deeplink.DeepLinkResolution
import net.plumt.deeplink.DeepLinkRoute
import net.plumt.deeplink.DeepLinkRouter
import net.plumt.deeplink.Param
import net.plumt.deeplink.ParamCheck
import net.plumt.deeplink.Rejection
import net.plumt.deeplink.ResolvedDeepLink
import net.plumt.deeplink.TrustPolicy
import net.plumt.deeplink.UnknownQueryPolicy
import net.plumt.deeplink.UriRules
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

internal class DefaultDeepLinkHandler(
    override val routes: List<DeepLinkRoute>,
    private val interceptors: List<DeepLinkInterceptor>,
    private val listeners: List<DeepLinkListener>,
    private val router: DeepLinkRouter,
    private val trustPolicy: TrustPolicy,
    private val uriRules: UriRules,
) : DeepLinkHandler {

    private val schemes: Set<String> = routes.mapTo(HashSet()) { it.matcher.scheme }
    private val hostsByScheme: Map<String, Set<String>> =
        routes.groupBy { it.matcher.scheme }.mapValues { (_, list) -> list.mapTo(HashSet()) { it.matcher.host } }

    override fun resolve(request: DeepLinkRequest): DeepLinkResolution {
        val uri = when (val parsed = UriParser.parse(request.uri, uriRules)) {
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

        return DeepLinkResolution.Resolved(ResolvedDeepLink(best, trust, request.caller, uri, values, this))
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

    private fun rejected(rejection: Rejection) = DeepLinkResolution.Rejected(rejection)

    override suspend fun handle(request: DeepLinkRequest): DeepLinkOutcome {
        val outcome = when (val resolution = resolve(request)) {
            is DeepLinkResolution.Rejected -> DeepLinkOutcome.Rejected(resolution.rejection)
            is DeepLinkResolution.Resolved -> run(resolution.link)
        }
        listeners.forEach { it.onOutcome(outcome) }
        return outcome
    }

    override suspend fun resume(link: ResolvedDeepLink): DeepLinkOutcome {
        require(link.owner === this) { "이 처리기가 만든 링크가 아니다: $link" }
        val outcome = run(link)
        listeners.forEach { it.onOutcome(outcome) }
        return outcome
    }

    private suspend fun run(link: ResolvedDeepLink): DeepLinkOutcome = try {
        RealChain(link, 0).proceed()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DeepLinkOutcome.Failed(link, DeepLinkException("딥링크 처리 실패: route '${link.route.id}'", e))
    }

    private inner class RealChain(override val link: ResolvedDeepLink, private val index: Int) : DeepLinkChain {
        private val called = AtomicBoolean(false)

        override suspend fun proceed(): DeepLinkOutcome {
            check(called.compareAndSet(false, true)) { "proceed() 는 한 번만 부를 수 있다" }
            if (index < interceptors.size) {
                return interceptors[index].intercept(RealChain(link, index + 1))
            }
            return if (router.route(link)) DeepLinkOutcome.Handled(link) else DeepLinkOutcome.Unhandled(link)
        }
    }

}
