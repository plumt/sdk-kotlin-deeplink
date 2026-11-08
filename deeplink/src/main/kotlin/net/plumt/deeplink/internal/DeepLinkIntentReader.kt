package net.plumt.deeplink.internal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import net.plumt.deeplink.CallerVerifier
import net.plumt.deeplink.DeepLinkRequest
import net.plumt.deeplink.IntentReadResult
import net.plumt.deeplink.TrustedCaller
import net.plumt.deeplink.UnsafeIntentReason
import java.util.WeakHashMap

/**
 * Activity 가 받은 인텐트를 [DeepLinkRequest] 로 바꾼다.
 *
 * - data URI 문자열과 발신자([CallerVerifier]) **만** 꺼낸다. extras 의 값(Intent·PendingIntent·URL 등)은
 *   읽지도 꺼내 주지도 않는다 — 인텐트 리다이렉션의 재료를 넘기지 않고, 공격자 객체를 역직렬화하지 않는다
 * - data 에 붙은 자기 링크 토큰([net.plumt.deeplink.DeepLinks.selfIntent])은 떼고 [DeepLinkRequest.uri] 에 넣는다
 * - extras 가 꼭 필요하면 [SafeExtras] 로 원시 타입만 읽는다
 *
 * 처음 실행은 [readLaunch], `onNewIntent` 는 [readNewIntent] 로 나눠 부른다. 메인 스레드에서 부른다.
 */
internal class DeepLinkIntentReader private constructor(
    private val callerVerifier: CallerVerifier,
    private val allowedActions: Set<String>,
) {
    // Activity 마다 처음 읽은 실행 인텐트. setIntent 로 바뀐 인텐트를 처음 실행으로 오인하지 않게 한다
    private val launches = WeakHashMap<Activity, Intent>()

    /**
     * `onCreate` 에서 Activity 를 띄운 인텐트(`activity.intent`)를 읽는다.
     *
     * - [savedInstanceState] 가 `null` 이 아니면(회전·프로세스 복원으로 다시 만들어짐) 이미 처리한 링크이므로
     *   [IntentReadResult.NotDeepLink] 를 돌려준다 — 같은 링크를 두 번 열지 않고, 처음 띄운 앱 정보를 다른
     *   링크에 붙이지 않는다
     * - 최근 앱 화면에서 다시 연 실행(`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`)도 [IntentReadResult.NotDeepLink] 다.
     *   플랫폼이 옛 인텐트를 그대로 다시 주는 것이라 새 링크가 아니다
     * - 같은 Activity 로 두 번째 부를 때 `activity.intent` 가 처음과 다른 객체면(`onNewIntent` 에서 `setIntent` 한 뒤)
     *   처음 실행으로 보지 않는다
     */
    fun readLaunch(activity: Activity, savedInstanceState: Bundle?): IntentReadResult {
        if (savedInstanceState != null) return IntentReadResult.NotDeepLink
        val intent = activity.intent ?: return IntentReadResult.NotDeepLink
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return IntentReadResult.NotDeepLink
        val initial = synchronized(launches) {
            val first = launches[activity]
            if (first == null) launches[activity] = intent
            first == null || first === intent
        }
        return read(activity, intent, initial)
    }

    /**
     * `onNewIntent(intent)` 로 받은 [intent] 를 읽는다. 처음 띄운 앱 정보(launchedFrom)는 쓰지 않으므로
     * 이 경로로는 OS 보증 발신자·FIRST_PARTY 가 나오지 않는다. 자기 링크 토큰은 그대로 확인한다.
     * `setIntent(intent)` 를 했든 안 했든 이 함수를 쓴다.
     */
    fun readNewIntent(activity: Activity, intent: Intent): IntentReadResult = read(activity, intent, initialLaunch = false)

    private fun read(activity: Activity, intent: Intent, initialLaunch: Boolean): IntentReadResult {
        if (intent.action !in allowedActions) return IntentReadResult.NotDeepLink
        val data = intent.dataString ?: return IntentReadResult.NotDeepLink
        if (intent.selector != null) return IntentReadResult.Unsafe(UnsafeIntentReason.HAS_SELECTOR)
        if (!extrasStructureReadable(intent)) return IntentReadResult.Unsafe(UnsafeIntentReason.UNREADABLE_EXTRAS)
        val caller = callerVerifier.identify(activity, intent, initialLaunch)
        return IntentReadResult.Link(DeepLinkRequest(SelfLinkToken.split(data).first, caller))
    }

    private fun extrasStructureReadable(intent: Intent): Boolean = try {
        // size() 는 Bundle 을 풀게 만든다. API 33+ 에서는 키와 길이만 읽고 값은 역직렬화하지 않는다
        intent.extras?.size()
        true
    } catch (_: RuntimeException) {
        // 풀 수 없는 extras 는 Unsafe 결과로 알린다
        false
    }

    companion object {
        /**
         * @param trustedCallers 서명까지 확인해 FIRST_PARTY 로 올릴 앱([CallerVerifier.create] 참고)
         * @param allowedActions 딥링크로 볼 인텐트 액션. 기본 `ACTION_VIEW` 하나
         * @throws IllegalArgumentException [allowedActions] 가 비었거나 [trustedCallers] 가 틀렸을 때
         */
        fun create(
            context: Context,
            trustedCallers: Collection<TrustedCaller> = emptyList(),
            allowedActions: Set<String> = setOf(Intent.ACTION_VIEW),
        ): DeepLinkIntentReader = create(CallerVerifier.create(context, trustedCallers), allowedActions)

        /**
         * 발신자 판정을 직접 구현한 [CallerVerifier] 로 바꾼다.
         *
         * @throws IllegalArgumentException [allowedActions] 가 비었을 때
         */
        fun create(
            callerVerifier: CallerVerifier,
            allowedActions: Set<String> = setOf(Intent.ACTION_VIEW),
        ): DeepLinkIntentReader {
            require(allowedActions.isNotEmpty()) { "allowedActions 가 비었다" }
            return DeepLinkIntentReader(callerVerifier, allowedActions.toSet())
        }
    }
}
