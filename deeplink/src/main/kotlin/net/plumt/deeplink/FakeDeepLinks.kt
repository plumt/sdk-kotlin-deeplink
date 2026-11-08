package net.plumt.deeplink

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.LifecycleOwner
import net.plumt.deeplink.internal.DeepLinkEngine
import net.plumt.deeplink.internal.DeepLinkQueue
import net.plumt.deeplink.internal.ExternalLinks
import net.plumt.deeplink.internal.MainCallbacks
import net.plumt.deeplink.internal.consume
import net.plumt.deeplink.internal.requireOwned
import java.io.Closeable
import java.util.Collections

/**
 * 테스트용 [DeepLinks]. 앱 단위 테스트에서 기기 없이 쓴다.
 *
 * - 검증·인터셉터·라우터·리스너·규칙([resolve]·[handle]·[resume])과 외부 링크 검증은 **기본 구현과 같은 내부 처리**를 쓴다.
 *   그래서 앱의 경로 설정을 JVM 단위 테스트에서 그대로 시험할 수 있다. 발신자는 [DeepLinkRequest] 에 직접 넣는다
 *   (예: `CallerIdentity(selfPackage, CallerVerification.SELF_ISSUED)` 로 알림 링크)
 * - 인텐트 읽기는 정해 둔 값([launchResult]·[newIntentResult])을 돌려준다
 * - App Link 상태는 [appLinkStatusResult] 를 돌려준다
 * - [openExternal] 은 실제로 열지 않고 [openedExternal] 에 정규형 URL 을 남긴다
 * - [selfIntent]·[selfPendingIntent]·[appLinkSettingsIntent]·[externalIntent] 의 `Ready` 와 [consumeWhileResumed]·콜백 [handle] 은 Android 클래스를 쓰므로
 *   Robolectric 에서만 쓴다. 자기 링크에는 토큰을 붙이지 않고 [issuedSelfLinks] 에 남긴다. [selfPendingIntent] 는 [pendingIntentContext] 가 있어야 한다
 *
 * 스레드 안전하다(기록 목록은 동기화된 목록이다).
 *
 * @param selfPackage 자기 앱 패키지. 설정에 신뢰 정책이 없으면 이 패키지로 [TrustPolicy.standard] 를 쓴다
 */
public class FakeDeepLinks(
    override val config: DeepLinkConfig,
    public val selfPackage: String,
) : DeepLinks {

    /** 자기 패키지를 [DEFAULT_SELF_PACKAGE] 로 둔다. */
    public constructor(config: DeepLinkConfig) : this(config, DEFAULT_SELF_PACKAGE)

    private val engine = DeepLinkEngine(config, config.trustPolicy ?: TrustPolicy.standard(selfPackage))
    private val queue = DeepLinkQueue(config.queueCapacity)
    private val callbacks by lazy { MainCallbacks() }

    /** [readLaunch]·[offerLaunch] 가 돌려줄 값. 기본 [IntentReadResult.NotDeepLink]. */
    @Volatile
    public var launchResult: IntentReadResult = IntentReadResult.NotDeepLink

    /** [readNewIntent]·[offerNewIntent] 가 돌려줄 값. 기본 [IntentReadResult.NotDeepLink]. */
    @Volatile
    public var newIntentResult: IntentReadResult = IntentReadResult.NotDeepLink

    /** [appLinkStatus] 가 돌려줄 값. 기본 [AppLinkStatus.Unsupported]. */
    @Volatile
    public var appLinkStatusResult: AppLinkStatus = AppLinkStatus.Unsupported

    /** [selfPendingIntent] 가 PendingIntent 를 만들 컨텍스트(Robolectric 의 애플리케이션). 기본 없음. */
    @Volatile
    public var pendingIntentContext: Context? = null

    /** `false` 면 [openExternal] 이 검증을 통과한 링크에 [ExternalOpenResult.NoActivity] 를 돌려준다. 기본 `true`. */
    @Volatile
    public var externalActivityAvailable: Boolean = true

    private val outcomesList = Collections.synchronizedList(ArrayList<DeepLinkOutcome>())
    private val openedList = Collections.synchronizedList(ArrayList<String>())
    private val issuedList = Collections.synchronizedList(ArrayList<String>())

    /** [handle]·[resume]·[consumeWhileResumed] 가 낸 결과(순서대로). */
    public val outcomes: List<DeepLinkOutcome> get() = synchronized(outcomesList) { outcomesList.toList() }

    /** [openExternal] 이 열었다고 기록한 정규형 URL. */
    public val openedExternal: List<String> get() = synchronized(openedList) { openedList.toList() }

    /** [selfIntent]·[selfPendingIntent] 에 넘긴 URI. */
    public val issuedSelfLinks: List<String> get() = synchronized(issuedList) { issuedList.toList() }

    override fun readLaunch(activity: Activity, savedInstanceState: Bundle?): IntentReadResult = launchResult

    override fun readNewIntent(activity: Activity, intent: Intent): IntentReadResult = newIntentResult

    override fun offer(request: DeepLinkRequest): DeepLinkRequest? = queue.offer(request)

    override fun clearPending(): Unit = queue.clear()

    override val pendingCount: Int get() = queue.size

    override fun consumeWhileResumed(owner: LifecycleOwner, router: DeepLinkRouter, onOutcome: (DeepLinkOutcome) -> Unit): Closeable =
        consume(queue, engine, owner, router) { outcome ->
            outcomesList += outcome
            onOutcome(outcome)
        }

    override fun resolve(request: DeepLinkRequest): DeepLinkResolution = engine.resolve(request)

    override suspend fun handle(request: DeepLinkRequest, router: DeepLinkRouter): DeepLinkOutcome =
        engine.handle(request, router).also { outcomesList += it }

    override fun handle(request: DeepLinkRequest, router: DeepLinkRouter, onOutcome: (DeepLinkOutcome) -> Unit): DeepLinkHandle =
        callbacks.launch({ handle(request, router) }, onOutcome)

    override suspend fun resume(link: ResolvedDeepLink, router: DeepLinkRouter): DeepLinkOutcome =
        engine.resume(link, router).also { outcomesList += it }

    override fun resume(link: ResolvedDeepLink, router: DeepLinkRouter, onOutcome: (DeepLinkOutcome) -> Unit): DeepLinkHandle {
        requireOwned(engine, link)
        return callbacks.launch({ resume(link, router) }, onOutcome)
    }

    override fun selfIntent(uri: String): Intent {
        DeepLinkUri.parse(uri)
        require('#' !in uri) { "자기 링크에는 프래그먼트(#)를 쓸 수 없다" }
        issuedList += uri
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(selfPackage)
    }

    override fun selfPendingIntent(uri: String, requestCode: Int): PendingIntent =
        selfPendingIntent(uri, requestCode, PendingIntent.FLAG_UPDATE_CURRENT)

    override fun selfPendingIntent(uri: String, requestCode: Int, flags: Int): PendingIntent {
        require(flags and FLAG_MUTABLE_VALUE == 0) { "FLAG_MUTABLE 은 쓸 수 없다: flags=$flags" }
        val context = checkNotNull(pendingIntentContext) { "selfPendingIntent 를 쓰려면 pendingIntentContext 를 정해야 한다" }
        return PendingIntent.getActivity(context, requestCode, selfIntent(uri), flags or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun appLinkStatus(): AppLinkStatus = appLinkStatusResult

    override fun appLinkSettingsIntent(): Intent = Intent(SETTINGS_ACTION, Uri.parse("package:$selfPackage"))

    override fun externalIntent(uri: String): ExternalLinkResult = ExternalLinks.intent(uri, config.externalHosts, config.uriRules)

    override fun openExternal(context: Context, uri: String): ExternalOpenResult {
        val (canonical, rejection) = ExternalLinks.check(uri, config.externalHosts, config.uriRules)
        if (canonical == null) return ExternalOpenResult.Rejected(rejection!!)
        if (!externalActivityAvailable) return ExternalOpenResult.NoActivity
        openedList += canonical
        return ExternalOpenResult.Opened
    }

    /** 기록([outcomes]·[openedExternal]·[issuedSelfLinks])과 대기열을 비운다. 정해 둔 값은 그대로 둔다. */
    public fun reset() {
        outcomesList.clear()
        openedList.clear()
        issuedList.clear()
        queue.clear()
    }

    override fun toString(): String = "FakeDeepLinks(routes=${config.routes.size}, pending=$pendingCount)"

    public companion object {
        /** 자기 패키지를 정하지 않을 때 쓰는 이름. */
        public const val DEFAULT_SELF_PACKAGE: String = "app.test"

        private const val FLAG_MUTABLE_VALUE = 1 shl 25
        private const val SETTINGS_ACTION = "android.settings.APPLICATION_DETAILS_SETTINGS"
    }
}
