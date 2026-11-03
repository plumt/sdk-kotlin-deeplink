package net.plumt.deeplink.android.internal

import android.app.Activity
import android.content.Intent
import net.plumt.deeplink.CallerIdentity
import net.plumt.deeplink.CallerVerification
import net.plumt.deeplink.android.CallerVerifier

internal class DefaultCallerVerifier(
    private val trusted: Map<String, Set<String>>,
    private val signatures: SignatureChecker,
    private val readSignals: (Activity, Intent) -> CallerSignals,
) : CallerVerifier {

    override fun identify(activity: Activity, intent: Intent): CallerIdentity {
        val signals = try {
            readSignals(activity, intent)
        } catch (_: RuntimeException) {
            // extras 를 풀 수 없는 인텐트는 적대적으로 보고 "모름"(가장 낮은 신뢰)으로 둔다
            return CallerIdentity.UNKNOWN
        }
        return upgrade(signals.decide())
    }

    fun upgrade(base: CallerIdentity): CallerIdentity {
        val pkg = base.packageName ?: return base
        val reported = base.verification == CallerVerification.SYSTEM_REPORTED ||
            base.verification == CallerVerification.OS_VERIFIED
        val allowed = trusted[pkg]
        if (!reported || allowed == null) return base
        return if (signatures.matches(pkg, allowed)) CallerIdentity(pkg, CallerVerification.SIGNATURE_VERIFIED) else base
    }
}
