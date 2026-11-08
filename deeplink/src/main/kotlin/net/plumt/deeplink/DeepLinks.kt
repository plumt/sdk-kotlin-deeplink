package net.plumt.deeplink

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.LifecycleOwner
import net.plumt.deeplink.internal.DefaultDeepLinks
import net.plumt.deeplink.internal.SafeExtras
import java.io.Closeable

/**
 * 딥링크의 유일한 진입점. [create] 로 **프로세스에 하나** 만들어(`Application`·DI 싱글턴) 넘긴다. 테스트에서는 [FakeDeepLinks] 로 바꾼다.
 *
 * 받는 쪽 흐름: 인텐트 읽기([readLaunch]·[readNewIntent]) → 대기열([offer]) → 앞에 선 화면의 소비([consumeWhileResumed]) →
 * 검증([resolve]: URI 규칙·경로·신뢰·파라미터·[DeepLinkRule]) → 인터셉터 사슬 → 화면이 준 [DeepLinkRouter].
 * 기본값은 전부 **거부**다. 설정([DeepLinkConfig])에 등록하지 않은 스킴·호스트·경로·파라미터는 [Rejection] 과 함께 거부된다.
 *
 * 처리는 같은 동작을 두 형태로 준다 — 둘은 같은 내부 처리를 쓴다.
 * - `suspend` [handle]·[resume] — 기준. 스레드를 바꾸지 않는다
 * - 콜백 [handle]·[resume] (`handle(request, router) { outcome -> }`) — 코루틴 없이 부른다. 결과는 메인 스레드로 한 번 온다. 취소 핸들을 돌려준다
 *
 * 그 밖에 자기 앱 링크 발급([selfIntent]), extras 원시 타입 읽기([stringExtra] 등), App Link 검증 상태([appLinkStatus]),
 * 외부 링크 열기([externalIntent]·[openExternal])를 준다.
 *
 * 인스턴스는 스레드 안전하다. 메서드마다 부를 스레드를 적었다.
 */
public interface DeepLinks {

    /** 이 인스턴스의 설정. */
    public val config: DeepLinkConfig

    /**
     * `onCreate` 에서 Activity 를 띄운 인텐트(`activity.intent`)를 읽는다. data URI 와 발신자만 꺼내고 extras 값은 읽지 않는다.
     * 메인 스레드에서 부른다(발신자 판정이 PackageManager 를 짧게 조회한다).
     *
     * - [savedInstanceState] 가 `null` 이 아니면(회전·프로세스 복원으로 다시 만들어짐) 이미 처리한 링크이므로 [IntentReadResult.NotDeepLink]
     * - 최근 앱 화면에서 다시 연 실행(`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`)도 [IntentReadResult.NotDeepLink]
     * - 셀렉터가 붙었거나 extras 구조가 손상되면 [IntentReadResult.Unsafe]
     * - 자기 링크 토큰([selfIntent])은 확인한 뒤 떼고 [DeepLinkRequest.uri] 에 넣는다
     */
    public fun readLaunch(activity: Activity, savedInstanceState: Bundle?): IntentReadResult

    /**
     * `onNewIntent(intent)` 로 받은 [intent] 를 읽는다. 처음 띄운 앱 정보(launchedFrom)는 쓰지 않으므로
     * 이 경로로는 OS 보증 발신자·FIRST_PARTY 가 나오지 않는다. 자기 링크 토큰은 그대로 확인한다. 메인 스레드에서 부른다.
     */
    public fun readNewIntent(activity: Activity, intent: Intent): IntentReadResult

    /**
     * [readLaunch] 하고 딥링크면 [offer] 한다. `onCreate` 에 한 줄로 쓴다.
     *
     * @return [readLaunch] 결과. 링크가 넘쳐 밀려난 다른 링크가 있어도 알리지 않는다(필요하면 [readLaunch]·[offer] 를 따로 부른다)
     */
    public fun offerLaunch(activity: Activity, savedInstanceState: Bundle?): IntentReadResult {
        val result = readLaunch(activity, savedInstanceState)
        if (result is IntentReadResult.Link) offer(result.request)
        return result
    }

    /** [readNewIntent] 하고 딥링크면 [offer] 한다. `onNewIntent` 에 한 줄로 쓴다. */
    public fun offerNewIntent(activity: Activity, intent: Intent): IntentReadResult {
        val result = readNewIntent(activity, intent)
        if (result is IntentReadResult.Link) offer(result.request)
        return result
    }

    /**
     * 링크를 대기열 맨 뒤에 넣는다. 앞에 선 화면의 [consumeWhileResumed] 가 가져간다. 어느 스레드에서든 부를 수 있다.
     * 넣은 [DeepLinkRequest] 객체가 그대로 나오므로 큐를 지나도 발신자 판정이 바뀌지 않는다. 메모리에만 두고 디스크에 쓰지 않는다.
     *
     * 큐가 가득 차 있으면([DeepLinkConfig.queueCapacity]) **가장 오래된 링크를 버린다.** 외부 앱은 인텐트를 얼마든지 보낼 수 있다.
     *
     * @return 밀려나 버려진 링크. 없으면 `null`
     */
    public fun offer(request: DeepLinkRequest): DeepLinkRequest?

    /** 대기 중인 링크를 모두 버린다(계정이 바뀌었을 때). 처리 중인 링크는 건드리지 않는다. 어느 스레드에서든 부를 수 있다. */
    public fun clearPending()

    /** 지금 대기 중인 링크 수. */
    public val pendingCount: Int

    /**
     * [owner] 가 RESUMED 인 동안 대기열의 링크를 하나씩 가져가 [handle] 하고 결과를 [onOutcome] 으로 준다. 메인 스레드에서 부른다.
     * 소비자는 [owner] 가 파괴되면 스스로 닫힌다. 그 전에 그만 받으려면 돌려준 값의 `close()` 를 메인 스레드에서 부른다.
     *
     * - 링크 하나는 소비자 한 곳에서 한 번만 처리된다. 살아 있는 Activity 인스턴스 수와 무관하다
     * - RESUMED 인 소비자만 가져간다. 소비자가 없거나 RESUMED 가 아니면 링크는 큐에 남는다
     * - 처리 중 화면이 멈추면(PAUSE) 취소하지 않고 보류한다. 다시 앞에 설 때 이어서 끝나고, 멈춘 화면이 라우터를 부르지 않는다
     * - 처리 중 소비자가 닫히면(파괴·`close()`) 처리를 취소하고 링크를 큐 맨 앞에 되돌린다. [owner] 가 `isFinishing` 인 Activity 면 버린다.
     *   다시 처리되므로 라우터는 화면을 움직인 뒤 suspend 하지 않는다
     *
     * [onOutcome] 은 메인 스레드에서 불린다. [onOutcome] 이나 리스너가 던진 예외는 메인 스레드의 처리되지 않은 예외가 된다.
     *
     * @param owner 보통 Activity. Compose 에서는 `NavHost` 목적지 안이 아니라 그 바깥의 `LocalLifecycleOwner`
     * @param router 이 화면의 이동. 보통 이 인스턴스의 `NavController` 를 쓴다
     * @return 소비자를 닫는 핸들. 두 번 닫아도 된다
     * @throws IllegalStateException 메인 스레드가 아닐 때
     */
    public fun consumeWhileResumed(owner: LifecycleOwner, router: DeepLinkRouter, onOutcome: (DeepLinkOutcome) -> Unit): Closeable

    /** 결과를 받지 않는 [consumeWhileResumed]. 결과는 [DeepLinkConfig.listeners] 로만 본다. */
    public fun consumeWhileResumed(owner: LifecycleOwner, router: DeepLinkRouter): Closeable =
        consumeWhileResumed(owner, router) { }

    /** 검증만 한다. 인터셉터·라우터·리스너는 부르지 않는다. 블로킹 작업이 없어 어느 스레드에서 불러도 된다. */
    public fun resolve(request: DeepLinkRequest): DeepLinkResolution

    /** 검증하고 인터셉터 사슬과 [router] 를 거친다. 결과는 리스너에도 전달된다. 스레드를 바꾸지 않는다. */
    public suspend fun handle(request: DeepLinkRequest, router: DeepLinkRouter): DeepLinkOutcome

    /**
     * 콜백 형태 [handle]. 메인 스레드 코루틴에서 돌리고 결과를 [onOutcome] 으로 한 번 준다. 어느 스레드에서 불러도 된다.
     * 메인 스레드에서 부르면 라우터까지 곧바로 진행해 이 함수가 돌아오기 전에 [onOutcome] 이 불릴 수 있다.
     *
     * @return 취소 핸들. [DeepLinkHandle.cancel] 하면 [onOutcome] 은 불리지 않는다
     */
    public fun handle(request: DeepLinkRequest, router: DeepLinkRouter, onOutcome: (DeepLinkOutcome) -> Unit): DeepLinkHandle

    /**
     * [DeepLinkOutcome.Deferred] 로 미뤘던 링크를 다시 진행한다. 다시 파싱하지 않고 인터셉터 사슬을 처음부터 돈다
     * (로그인 인터셉터가 이번에는 통과시킨다).
     *
     * @throws IllegalArgumentException [link] 를 이 인스턴스가 만들지 않았을 때
     */
    public suspend fun resume(link: ResolvedDeepLink, router: DeepLinkRouter): DeepLinkOutcome

    /**
     * 콜백 형태 [resume]. 나머지는 콜백 [handle] 과 같다.
     *
     * @throws IllegalArgumentException [link] 를 이 인스턴스가 만들지 않았을 때 (호출 즉시)
     */
    public fun resume(link: ResolvedDeepLink, router: DeepLinkRouter, onOutcome: (DeepLinkOutcome) -> Unit): DeepLinkHandle

    /**
     * 자기 앱이 **의도해서** 발급하는 딥링크 인텐트. 패키지를 고정하고, data URI 뒤에 이 앱만 만들 수 있는
     * 서명 토큰을 프래그먼트(`#plumt-self=…`)로 붙인다. 이 링크만 [CallerVerification.SELF_ISSUED](→ [TrustLevel.SELF])가 된다.
     * extras 는 넣지 않는다.
     *
     * **외부에서 온 문자열을 넣지 않는다.** 채팅 메시지·웹 페이지·다른 사용자가 고른 링크를 여기에 넣으면
     * 그 링크가 SELF 경로를 연다(혼동된 대리인). 토큰이 든 인텐트를 다른 앱에 넘기지 않는다.
     * 처음 호출은 앱 전용 저장소(`noBackupFilesDir`)에 키 파일을 만들거나 읽는다(짧은 디스크 I/O).
     *
     * @throws IllegalArgumentException [uri] 가 [DeepLinkUri.parse] 를 통과하지 못하거나 `#` 을 담을 때
     * @throws DeepLinkException 서명 키를 만들거나 읽지 못할 때(저장소 오류). `cause` 에 원래 예외가 있다
     */
    public fun selfIntent(uri: String): Intent

    /**
     * [selfIntent] 를 담은 Activity PendingIntent(알림 탭). 플래그는 `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`.
     *
     * @throws IllegalArgumentException [selfIntent] 와 같다
     * @throws DeepLinkException [selfIntent] 와 같다
     */
    public fun selfPendingIntent(uri: String, requestCode: Int): PendingIntent

    /**
     * [selfIntent] 를 담은 Activity PendingIntent. 항상 `FLAG_IMMUTABLE` 을 붙여 받는 쪽이 내용을 바꿔 채우지 못하게 한다.
     *
     * @param flags 추가 플래그. `FLAG_MUTABLE` 을 주면 거부한다
     * @throws IllegalArgumentException [uri] 가 틀렸거나 [flags] 에 `FLAG_MUTABLE` 이 있을 때
     * @throws DeepLinkException 서명 키를 준비하지 못했을 때
     */
    public fun selfPendingIntent(uri: String, requestCode: Int, flags: Int): PendingIntent

    /**
     * 외부에서 온 인텐트의 문자열 extra 를 읽는다(최대 1024자). Intent·PendingIntent·Bundle·Parcelable·Uri 를 읽는 함수는 일부러 없다 —
     * 받은 Intent 를 그대로 `startActivity` 하면 외부 앱이 우리 권한으로 비공개 컴포넌트를 연다(인텐트 리다이렉션).
     *
     * **역직렬화 주의**: 키 하나를 읽으면 그 키의 값은 타입을 확인하기 **전에** 역직렬화된다. API 33+ 는 읽은 키의 값만,
     * API 23~32 는 extras 에 처음 닿을 때 Bundle 전체가 역직렬화된다. 신뢰할 수 없는 발신자의 extras 는 꼭 필요한 키만 읽는다.
     */
    public fun stringExtra(intent: Intent, key: String): ExtraRead<String> = SafeExtras.string(intent, key)

    /**
     * 문자열 extra 를 [maxLength] 자까지 읽는다.
     *
     * @throws IllegalArgumentException [maxLength] 가 1 미만일 때
     */
    public fun stringExtra(intent: Intent, key: String, maxLength: Int): ExtraRead<String> = SafeExtras.string(intent, key, maxLength)

    /** 정수 extra 를 읽는다. [stringExtra] 의 주의를 본다. */
    public fun intExtra(intent: Intent, key: String): ExtraRead<Int> = SafeExtras.int(intent, key)

    /** Long extra 를 읽는다. [stringExtra] 의 주의를 본다. */
    public fun longExtra(intent: Intent, key: String): ExtraRead<Long> = SafeExtras.long(intent, key)

    /** 불리언 extra 를 읽는다. [stringExtra] 의 주의를 본다. */
    public fun booleanExtra(intent: Intent, key: String): ExtraRead<Boolean> = SafeExtras.boolean(intent, key)

    /**
     * 이 앱의 https App Link 검증 상태. API 31+ 는 `DomainVerificationManager` 로 읽고, 그 아래는 [AppLinkStatus.Unsupported].
     * 바인더 호출이 한 번 있다. 어느 스레드에서든 부를 수 있다.
     */
    public fun appLinkStatus(): AppLinkStatus

    /**
     * 사용자가 "지원되는 링크 열기" 를 켜고 호스트를 고르는 설정 화면 인텐트. API 31+ 는 `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS`,
     * 그 아래는 앱 상세 설정 화면. Activity 가 아닌 곳에서 열면 `FLAG_ACTIVITY_NEW_TASK` 를 더한다.
     */
    public fun appLinkSettingsIntent(): Intent

    /**
     * 외부로 열 https 링크(채팅 메시지·웹 페이지·서버 응답의 URL)를 검증하고 열 인텐트를 만든다. 열지는 않는다.
     *
     * - [DeepLinkConfig.uriRules] 로 다시 엄격히 파싱한다(사용자 정보·인코딩 꼼수·제어 문자 거부)
     * - 스킴은 `https` 만. `intent:`·`javascript:`·`file:`·`content:`·`android-app:`·커스텀 스킴은 [Rejection.UnknownScheme]
     * - 호스트는 [DeepLinkConfig.externalHosts] 와 정확히 일치해야 하고 포트가 없어야 한다. 아니면 [Rejection.UnknownHost]
     * - 인텐트는 `ACTION_VIEW` + `CATEGORY_BROWSABLE`, 컴포넌트·패키지·셀렉터·extras 없음. data 는 [DeepLinkUri.toUriString] 정규형이다
     *   — 프래그먼트(`#…`)는 버린다
     *
     * 어느 스레드에서든 부를 수 있다.
     */
    public fun externalIntent(uri: String): ExternalLinkResult

    /**
     * [externalIntent] 로 검증한 링크를 연다. [context] 가 Activity 가 아니면 새 태스크로 연다. 메인 스레드에서 부른다.
     *
     * @return 열었으면 [ExternalOpenResult.Opened], 검증 실패면 [ExternalOpenResult.Rejected], 열 앱이 없으면 [ExternalOpenResult.NoActivity]
     */
    public fun openExternal(context: Context, uri: String): ExternalOpenResult

    public companion object {
        /**
         * 기본 구현을 만든다. 프로세스에 하나 두고 DI 로 넘긴다(대기열과 [resume] 이 그 인스턴스에 묶인다).
         *
         * @param context 애플리케이션 컨텍스트를 꺼내 쓴다 — Activity 를 넘겨도 붙잡지 않는다
         * @throws IllegalArgumentException [DeepLinkConfig.trustedCallers] 에 자기 패키지가 있을 때.
         *   자기 앱은 서명이 아니라 토큰([selfIntent])으로만 SELF 가 된다
         */
        @JvmStatic
        public fun create(context: Context, config: DeepLinkConfig): DeepLinks = DefaultDeepLinks.create(context, config)
    }
}
