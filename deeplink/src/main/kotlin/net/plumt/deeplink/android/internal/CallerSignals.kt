package net.plumt.deeplink.android.internal

import android.app.Activity
import android.content.Intent
import net.plumt.deeplink.CallerIdentity
import net.plumt.deeplink.CallerVerification

/** 발신자 판정에 쓰는 원재료. Activity 에서 읽는 부분과 판정을 나눠 판정을 순수하게 시험한다. */
internal data class CallerSignals(
    val referrerExtraPresent: Boolean,
    val referrer: String?,
    val launchedFromPackage: String?,
) {

    /** 서명 확인 전 단계의 판정. */
    fun decide(): CallerIdentity {
        launchedFromPackage?.takeIf(::isPackageName)?.let { return CallerIdentity(it, CallerVerification.OS_VERIFIED) }
        val pkg = packageFromReferrer(referrer) ?: return CallerIdentity.UNKNOWN
        val verification = if (referrerExtraPresent) CallerVerification.CLAIMED else CallerVerification.SYSTEM_REPORTED
        return CallerIdentity(pkg, verification)
    }

    companion object {
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private const val ANDROID_APP = "android-app://"
        private const val MAX_PACKAGE_LENGTH = 255
        private const val SDK_U = 34

        fun isPackageName(value: String): Boolean = value.length <= MAX_PACKAGE_LENGTH && PACKAGE.matches(value)

        /** `android-app://<패키지>[/…]` 에서 패키지만. 다른 스킴(http 등)은 근거가 아니므로 `null`. */
        fun packageFromReferrer(referrer: String?): String? {
            if (referrer == null || referrer.length < ANDROID_APP.length) return null
            if (!referrer.regionMatches(0, ANDROID_APP, 0, ANDROID_APP.length, ignoreCase = true)) return null
            val pkg = referrer.substring(ANDROID_APP.length).substringBefore('/')
            return pkg.takeIf(::isPackageName)
        }

        fun hasReferrerExtra(intent: Intent?): Boolean =
            intent != null && (intent.hasExtra(Intent.EXTRA_REFERRER) || intent.hasExtra(Intent.EXTRA_REFERRER_NAME))

        /**
         * @throws RuntimeException extras 를 풀 수 없을 때(`BadParcelableException` 등). 호출부가 처리한다
         */
        fun read(activity: Activity, intent: Intent, sdkInt: Int): CallerSignals {
            // Activity.referrer 는 activity.intent 의 extras 를 먼저 본다. 전달받은 인텐트와 현재 인텐트 중
            // 어느 쪽에든 referrer extra 가 있으면 referrer 값은 발신 앱의 주장일 수 있다
            val extraPresent = hasReferrerExtra(intent) || hasReferrerExtra(activity.intent)
            val launchedFrom = if (sdkInt >= SDK_U && intent === activity.intent) launchedFrom(activity) else null
            return CallerSignals(extraPresent, activity.referrer?.toString(), launchedFrom)
        }

        @Suppress("NewApi")
        private fun launchedFrom(activity: Activity): String? = activity.launchedFromPackage
    }
}
