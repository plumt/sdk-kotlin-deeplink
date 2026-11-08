package net.plumt.deeplink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import net.plumt.deeplink.internal.CallerSignals
import net.plumt.deeplink.internal.DefaultCallerVerifier
import net.plumt.deeplink.internal.SelfLinkSigner
import net.plumt.deeplink.internal.SignatureChecker

/**
 * 딥링크를 보낸 앱을 알아낸다. 확장점이다 — 다른 신원 근거가 있으면 구현해 [DeepLinkConfig.Builder.callerVerifier] 로 바꾼다.
 *
 * 플랫폼이 보증하는 값만 신뢰 근거로 쓴다. 판정 순서와 결과([CallerVerification]):
 * 1. data URI 에 [DeepLinks.selfIntent] 가 붙인 서명 토큰이 있고 맞으면 → `SELF_ISSUED`.
 *    토큰은 앱 전용 저장소의 키로 만든 HMAC 이라 다른 앱이 만들 수 없다
 * 2. Android 14+ 이고 [initialLaunch] 이면 `Activity.launchedFromUid`·`launchedFromPackage` 를 읽어
 *    그 uid 가 실제로 그 패키지를 가졌는지 PackageManager 로 대조 → `OS_VERIFIED`. 발신 앱이 신원 공유
 *    (`ActivityOptions.setShareIdentityEnabled`)를 켰거나 같은 uid 일 때만 OS 가 채운다
 * 3. 2 의 패키지가 [TrustedCaller] 에 있으면 그 패키지의 서명 인증서 SHA-256 을 대조 → 모두 맞으면 `SIGNATURE_VERIFIED`
 * 4. 그 밖에 `Activity.referrer`(extras 의 `EXTRA_REFERRER` 포함)가 `android-app://<패키지>` 면 → `CLAIMED`.
 *    플랫폼 문서가 위조할 수 있다고 적은 값이라 참고로만 남기고 신뢰를 올리지 않는다
 * 5. 아무것도 없으면 `CallerIdentity.UNKNOWN`
 *
 * 메인 스레드에서 부른다(PackageManager 조회와 첫 호출의 키 파일 읽기가 짧게 블로킹한다).
 */
public interface CallerVerifier {

    /**
     * [activity] 가 받은 [intent] 의 발신자.
     *
     * @param initialLaunch [intent] 가 이 Activity 를 **처음** 띄운 인텐트일 때만 `true`
     *   (`onCreate` 이고 `savedInstanceState == null`). launchedFrom 정보는 처음 띄운 앱을 가리키므로
     *   `onNewIntent` 로 온 인텐트에 쓰면 엉뚱한 앱을 발신자로 본다. 보통은 [DeepLinks.readLaunch]·[DeepLinks.readNewIntent] 가 정한다
     */
    public fun identify(activity: Activity, intent: Intent, initialLaunch: Boolean): CallerIdentity

    public companion object {
        /** 신뢰 목록 없는 기본 구현. 다른 앱은 FIRST_PARTY 가 되지 않는다. 아래 오버로드를 본다. */
        @JvmStatic
        public fun create(context: Context): CallerVerifier = create(context, emptyList())

        /**
         * 기본 구현. 직접 구현한 판정이 기본 판정을 감싸 쓸 때도 이것으로 만든다.
         *
         * Android 11+ 에서 다른 앱의 서명을 읽으려면 앱 매니페스트에 `<queries><package android:name="…"/></queries>`
         * 로 [trustedCallers] 패키지를 적는다. 조회가 실패하면 그 앱은 `OS_VERIFIED` 에 머문다(안전한 쪽).
         *
         * @throws IllegalArgumentException [trustedCallers] 에 같은 패키지가 두 번 있거나 자기 패키지가 있을 때.
         *   자기 앱은 서명이 아니라 토큰([DeepLinks.selfIntent])으로만 SELF 가 된다
         */
        @JvmStatic
        public fun create(context: Context, trustedCallers: Collection<TrustedCaller>): CallerVerifier {
            val byPackage = trustedCallers.groupBy { it.packageName }
            val duplicated = byPackage.filterValues { it.size > 1 }.keys
            require(duplicated.isEmpty()) { "trustedCallers 에 같은 패키지가 두 번 있다: $duplicated" }
            val selfPackage = context.packageName
            require(selfPackage !in byPackage) { "trustedCallers 에 자기 패키지를 넣을 수 없다: $selfPackage" }
            val pm = context.applicationContext.packageManager
            val sdkInt = Build.VERSION.SDK_INT
            return DefaultCallerVerifier(
                selfPackage = selfPackage,
                trusted = byPackage.mapValues { it.value.single() },
                sdkInt = sdkInt,
                signatures = SignatureChecker(pm, sdkInt),
                selfLinks = SelfLinkSigner.of(context),
                packagesForUid = pm::getPackagesForUid,
                readSignals = { activity, intent, initial -> CallerSignals.read(activity, intent, sdkInt, initial) },
            )
        }
    }
}

/**
 * 서명까지 확인해 [TrustLevel.FIRST_PARTY] 로 올릴 앱. 발신 앱이 Android 14+ 의 신원 공유를
 * 켜고 보내야 한다 — 그 밖에는 OS 가 보증한 발신자가 없어 FIRST_PARTY 가 되지 않는다.
 *
 * **키 교체**(APK 서명 v3/v3.1): 교체가 적용되는 API 레벨([rotationMinSdk] 이상)의 기기는 새 인증서를,
 * 그 아래 기기는 옛 인증서를 "현재 서명" 으로 보고한다. 옛 인증서를 [previousSha256Digests] 에 적으면
 * [rotationMinSdk] 미만 기기에서만 받는다. 옛 키가 유출됐다면 그 기기들에서는 그 키로 서명한 같은 이름의
 * 앱도 통과한다 — 플랫폼이 그 기기에서 옛 키를 현재 키로 보기 때문이다. Play 앱 서명을 쓰면 업로드 키가
 * 아니라 **앱 서명 키** 의 인증서를 적는다.
 *
 * @property packageName 패키지 이름
 * @property sha256Digests 허용할 현재 서명 인증서 SHA-256. `AB:CD:…` 나 콜론 없는 16진 64자 모두 받고
 *   대문자 16진으로 정규화한다
 * @property previousSha256Digests 교체 전 인증서. [rotationMinSdk] 미만 기기에서만 받는다. 기본 없음
 * @property rotationMinSdk 키 교체가 적용되는 최소 API 레벨. apksigner `--rotation-min-sdk-version` 과 같게 둔다.
 *   기본 33(apksigner 기본값), 28 이상
 * @throws IllegalArgumentException 패키지 이름 형식이 틀렸거나, 현재 다이제스트가 비었거나, SHA-256 형식이 아니거나,
 *   [rotationMinSdk] 가 28 미만일 때
 */
public class TrustedCaller(
    public val packageName: String,
    sha256Digests: Set<String>,
    previousSha256Digests: Set<String>,
    public val rotationMinSdk: Int,
) {
    /** 키를 교체하지 않은 앱. 이전 인증서 없음. */
    public constructor(packageName: String, sha256Digests: Set<String>) :
        this(packageName, sha256Digests, emptySet(), DEFAULT_ROTATION_MIN_SDK)

    /** 키를 교체한 앱. [rotationMinSdk] 는 apksigner 기본값 33. */
    public constructor(packageName: String, sha256Digests: Set<String>, previousSha256Digests: Set<String>) :
        this(packageName, sha256Digests, previousSha256Digests, DEFAULT_ROTATION_MIN_SDK)

    /** 대문자 16진으로 정규화한 현재 인증서 다이제스트. */
    public val sha256Digests: Set<String>

    /** 대문자 16진으로 정규화한 교체 전 인증서 다이제스트. */
    public val previousSha256Digests: Set<String>

    init {
        require(CallerSignals.isPackageName(packageName)) { "패키지 이름 형식이 아니다: '$packageName'" }
        require(sha256Digests.isNotEmpty()) { "sha256Digests 가 비었다: $packageName" }
        require(rotationMinSdk >= MIN_ROTATION_SDK) { "rotationMinSdk 는 $MIN_ROTATION_SDK 이상이어야 한다: $rotationMinSdk" }
        this.sha256Digests = normalize(sha256Digests)
        this.previousSha256Digests = normalize(previousSha256Digests)
    }

    /** 이 기기([sdkInt])에서 받을 다이제스트. */
    internal fun acceptedDigests(sdkInt: Int): Set<String> =
        if (sdkInt < rotationMinSdk) sha256Digests + previousSha256Digests else sha256Digests

    override fun equals(other: Any?): Boolean =
        other is TrustedCaller && packageName == other.packageName && sha256Digests == other.sha256Digests &&
            previousSha256Digests == other.previousSha256Digests && rotationMinSdk == other.rotationMinSdk

    override fun hashCode(): Int =
        ((packageName.hashCode() * 31 + sha256Digests.hashCode()) * 31 + previousSha256Digests.hashCode()) * 31 + rotationMinSdk

    override fun toString(): String =
        "TrustedCaller($packageName, ${sha256Digests.size} digests, ${previousSha256Digests.size} previous, rotationMinSdk=$rotationMinSdk)"
}

// 파일 전용 상수 — 동반 객체에 두면 const 가 공개 정적 필드로 나간다
private const val DEFAULT_ROTATION_MIN_SDK = 33
private const val MIN_ROTATION_SDK = 28

private fun normalize(raw: Set<String>): Set<String> = raw.mapTo(LinkedHashSet()) { value ->
    val hex = value.replace(":", "").uppercase()
    require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'A'..'F' }) {
        "SHA-256 다이제스트 형식이 아니다(16진 64자): '$value'"
    }
    hex
}
