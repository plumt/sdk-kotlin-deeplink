package net.plumt.deeplink.internal

import android.app.Activity
import android.content.Intent
import net.plumt.deeplink.CallerIdentity
import net.plumt.deeplink.CallerVerification
import net.plumt.deeplink.CallerVerifier
import net.plumt.deeplink.TrustedCaller

internal class DefaultCallerVerifier(
    private val selfPackage: String,
    private val trusted: Map<String, TrustedCaller>,
    private val sdkInt: Int,
    private val signatures: SignatureChecker,
    private val selfLinks: SelfLinkSigner,
    private val packagesForUid: (Int) -> Array<String>?,
    private val readSignals: (Activity, Intent, Boolean) -> CallerSignals,
) : CallerVerifier {

    override fun identify(activity: Activity, intent: Intent, initialLaunch: Boolean): CallerIdentity {
        // 1. 자기 앱이 발급한 토큰 — 전달 경로(onCreate·onNewIntent)와 API 레벨에 상관없이 같은 근거다
        val (uri, token) = SelfLinkToken.split(intent.dataString.orEmpty())
        if (token != null && selfLinks.verify(uri, token)) return CallerIdentity(selfPackage, CallerVerification.SELF_ISSUED)

        val signals = try {
            readSignals(activity, intent, initialLaunch)
        } catch (_: RuntimeException) {
            // extras 를 풀 수 없는 인텐트는 적대적으로 보고 "모름"(가장 낮은 신뢰)으로 둔다
            return CallerIdentity.UNKNOWN
        }
        // 2. OS 가 uid 로 보증한 발신 패키지(Android 14+, 처음 띄운 인텐트)
        osVerifiedPackage(signals)?.let { return upgrade(it) }
        // 3. 위조할 수 있는 referrer 는 참고로만 남긴다
        val hint = CallerSignals.packageFromReferrer(signals.referrer) ?: return CallerIdentity.UNKNOWN
        return CallerIdentity(hint, CallerVerification.CLAIMED)
    }

    private fun osVerifiedPackage(signals: CallerSignals): String? {
        val uid = signals.launchedFromUid ?: return null
        val pkg = signals.launchedFromPackage?.takeIf(CallerSignals::isPackageName) ?: return null
        if (uid < 0) return null
        val owned = try {
            packagesForUid(uid)
        } catch (_: SecurityException) {
            // 조회 권한이 없으면 uid 와 패키지를 대조하지 못한 것이므로 보증으로 치지 않는다
            null
        } ?: return null
        return pkg.takeIf { it in owned }
    }

    fun upgrade(pkg: String): CallerIdentity {
        val caller = trusted[pkg]
        if (caller == null || pkg == selfPackage) return CallerIdentity(pkg, CallerVerification.OS_VERIFIED)
        val verified = signatures.matches(pkg, caller.acceptedDigests(sdkInt))
        return CallerIdentity(pkg, if (verified) CallerVerification.SIGNATURE_VERIFIED else CallerVerification.OS_VERIFIED)
    }
}
