package net.plumt.deeplink.internal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import net.plumt.deeplink.DeepLinkUri
import net.plumt.deeplink.ExternalLinkResult
import net.plumt.deeplink.ExternalOpenResult
import net.plumt.deeplink.Rejection
import net.plumt.deeplink.UriRules

/** 외부로 열 링크의 검증. Android 클래스 없이 판정한다 — 가짜 구현과 기본 구현이 함께 쓴다. */
internal object ExternalLinks {

    /** 열어도 되면 정규형 URL, 아니면 거부 사유. */
    fun check(uri: String, hosts: Set<String>, rules: UriRules): Pair<String?, Rejection?> {
        val parsed = when (val result = UriParser.parse(uri, rules)) {
            is UriParser.Result.Fail -> return null to result.rejection
            is UriParser.Result.Ok -> result.uri
        }
        return when {
            // intent:·javascript:·file:·content: 등은 모두 여기서 걸린다 — 인텐트 리다이렉션·로컬 파일 노출 재료다
            parsed.scheme != HTTPS -> null to Rejection.UnknownScheme(parsed.scheme)
            parsed.port != null || parsed.host !in hosts -> null to Rejection.UnknownHost(parsed.scheme, parsed.host, parsed.port)
            else -> canonical(parsed) to null
        }
    }

    fun intent(uri: String, hosts: Set<String>, rules: UriRules): ExternalLinkResult {
        val (canonical, rejection) = check(uri, hosts, rules)
        return if (canonical != null) ExternalLinkResult.Ready(browsable(canonical)) else ExternalLinkResult.Rejected(rejection!!)
    }

    fun open(context: Context, uri: String, hosts: Set<String>, rules: UriRules): ExternalOpenResult =
        when (val result = intent(uri, hosts, rules)) {
            is ExternalLinkResult.Rejected -> ExternalOpenResult.Rejected(result.rejection)
            is ExternalLinkResult.Ready -> {
                val intent = result.intent
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                    ExternalOpenResult.Opened
                } catch (_: ActivityNotFoundException) {
                    // 열 앱이 없다는 것은 실패가 아니라 결과다
                    ExternalOpenResult.NoActivity
                }
            }
        }

    private fun canonical(uri: DeepLinkUri): String = uri.toUriString()

    // 패키지·컴포넌트·셀렉터·extras 를 두지 않는다. 시스템이 BROWSABLE 로 열 수 있는 앱만 고른다
    private fun browsable(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

    private const val HTTPS = "https"
}
