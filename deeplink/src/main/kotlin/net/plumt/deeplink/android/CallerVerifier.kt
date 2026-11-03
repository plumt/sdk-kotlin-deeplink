package net.plumt.deeplink.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import net.plumt.deeplink.CallerIdentity
import net.plumt.deeplink.android.internal.CallerSignals
import net.plumt.deeplink.android.internal.DefaultCallerVerifier
import net.plumt.deeplink.android.internal.SignatureChecker

/**
 * 딥링크를 보낸 앱을 알아낸다.
 *
 * 판정 근거와 결과 ([net.plumt.deeplink.CallerVerification]):
 * 1. Android 14+ 에서 발신 앱이 신원 공유(`ActivityOptions.setShareIdentityEnabled`)를 켰으면
 *    `Activity.launchedFromPackage` → `OS_VERIFIED`
 * 2. 인텐트 extras 에 `Intent.EXTRA_REFERRER`·`EXTRA_REFERRER_NAME` 이 있으면 발신 앱이 써 넣은 값이다
 *    → `CLAIMED`(신뢰 근거로 치지 않는다)
 * 3. 그 밖에 `Activity.referrer` 가 `android-app://<패키지>` 면 OS 가 채운 값 → `SYSTEM_REPORTED`
 * 4. 2·3 의 결과가 `SYSTEM_REPORTED`·`OS_VERIFIED` 이고 패키지가 [TrustedCaller] 에 있으면 그 패키지의
 *    현재 서명 인증서 SHA-256 을 PackageManager 로 대조 → 모두 맞으면 `SIGNATURE_VERIFIED`
 *
 * 메인 스레드에서 부른다(PackageManager 조회가 짧게 블로킹한다). `onNewIntent` 에서는 그 콜백
 * 안에서 곧바로 부른다 — OS 는 콜백이 도는 동안만 새 발신자로 referrer 를 바꿔 둔다.
 */
public interface CallerVerifier {

    /** [activity] 가 받은 [intent] 의 발신자. 기본은 `activity.intent`. */
    public fun identify(activity: Activity, intent: Intent = activity.intent): CallerIdentity

    public companion object {
        /**
         * 기본 구현.
         *
         * Android 11+ 에서 다른 앱의 서명을 읽으려면 앱 매니페스트에 `<queries><package android:name="…"/></queries>`
         * 로 [trustedCallers] 패키지를 적어야 한다. 빠지면 조회가 실패해 그 앱은 `SYSTEM_REPORTED` 에 머문다.
         *
         * @throws IllegalArgumentException [trustedCallers] 에 같은 패키지가 두 번 있을 때
         */
        @JvmStatic
        @JvmOverloads
        public fun create(context: Context, trustedCallers: Collection<TrustedCaller> = emptyList()): CallerVerifier {
            val byPackage = trustedCallers.groupBy { it.packageName }
            val duplicated = byPackage.filterValues { it.size > 1 }.keys
            require(duplicated.isEmpty()) { "trustedCallers 에 같은 패키지가 두 번 있다: $duplicated" }
            return DefaultCallerVerifier(
                trusted = byPackage.mapValues { it.value.single().sha256Digests },
                signatures = SignatureChecker(context.applicationContext.packageManager, Build.VERSION.SDK_INT),
                readSignals = { activity, intent -> CallerSignals.read(activity, intent, Build.VERSION.SDK_INT) },
            )
        }
    }
}

/**
 * 서명까지 확인해 [net.plumt.deeplink.TrustLevel.FIRST_PARTY] 로 올릴 앱.
 *
 * @property packageName 패키지 이름
 * @property sha256Digests 허용할 서명 인증서 SHA-256. `AB:CD:…` 나 콜론 없는 16진 64자 모두 받고
 *   대문자 16진으로 정규화한다. 키를 교체했다면 새 인증서도 적는다
 * @throws IllegalArgumentException 패키지 이름 형식이 틀렸거나, 다이제스트가 비었거나 SHA-256 형식이 아닐 때
 */
public class TrustedCaller(
    public val packageName: String,
    sha256Digests: Set<String>,
) {
    public val sha256Digests: Set<String>

    init {
        require(CallerSignals.isPackageName(packageName)) { "패키지 이름 형식이 아니다: '$packageName'" }
        require(sha256Digests.isNotEmpty()) { "sha256Digests 가 비었다: $packageName" }
        this.sha256Digests = sha256Digests.mapTo(LinkedHashSet()) { raw ->
            val hex = raw.replace(":", "").uppercase()
            require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'A'..'F' }) {
                "SHA-256 다이제스트 형식이 아니다(16진 64자): '$raw'"
            }
            hex
        }
    }

    override fun toString(): String = "TrustedCaller($packageName, ${sha256Digests.size} digests)"
}
