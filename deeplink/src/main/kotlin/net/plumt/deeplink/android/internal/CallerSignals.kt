package net.plumt.deeplink.android.internal

import android.app.Activity
import android.content.Intent

/** 발신자 판정에 쓰는 원재료. Activity 에서 읽는 부분과 판정을 나눠 판정을 순수하게 시험한다. */
internal data class CallerSignals(
    /** `Activity.getReferrer()`. 위조할 수 있어 참고용으로만 쓴다. */
    val referrer: String?,
    /** Android 14+ 의 `launchedFromUid`. 처음 띄운 인텐트일 때만 채운다. */
    val launchedFromUid: Int?,
    /** Android 14+ 의 `launchedFromPackage`. 처음 띄운 인텐트일 때만 채운다. */
    val launchedFromPackage: String?,
) {

    companion object {
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private const val ANDROID_APP = "android-app://"
        private const val MAX_PACKAGE_LENGTH = 255
        private const val SDK_U = 34

        fun isPackageName(value: String): Boolean = value.length <= MAX_PACKAGE_LENGTH && PACKAGE.matches(value)

        /** `android-app://<패키지>[/…]` 에서 패키지만. 다른 스킴(http 등)은 `null`. */
        fun packageFromReferrer(referrer: String?): String? {
            if (referrer == null || referrer.length < ANDROID_APP.length) return null
            if (!referrer.regionMatches(0, ANDROID_APP, 0, ANDROID_APP.length, ignoreCase = true)) return null
            val pkg = referrer.substring(ANDROID_APP.length).substringBefore('/')
            return pkg.takeIf(::isPackageName)
        }

        /**
         * @param initialLaunch [intent] 가 이 Activity 를 처음 띄운 인텐트인가. launchedFrom 정보는 Activity 를
         *   **처음** 띄운 앱을 가리키므로 `onNewIntent` 로 온 인텐트에는 쓰지 않는다
         * @throws RuntimeException extras 를 풀 수 없을 때(`BadParcelableException` 등). 호출부가 처리한다
         */
        fun read(activity: Activity, intent: Intent, sdkInt: Int, initialLaunch: Boolean): CallerSignals {
            val launched = initialLaunch && sdkInt >= SDK_U && intent === activity.intent
            return CallerSignals(
                referrer = activity.referrer?.toString(),
                launchedFromUid = if (launched) launchedFromUid(activity) else null,
                launchedFromPackage = if (launched) launchedFromPackage(activity) else null,
            )
        }

        @Suppress("NewApi")
        private fun launchedFromUid(activity: Activity): Int = activity.launchedFromUid

        @Suppress("NewApi")
        private fun launchedFromPackage(activity: Activity): String? = activity.launchedFromPackage
    }
}
