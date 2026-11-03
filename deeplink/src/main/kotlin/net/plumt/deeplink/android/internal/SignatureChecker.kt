package net.plumt.deeplink.android.internal

import android.content.pm.PackageManager
import android.content.pm.Signature
import java.security.MessageDigest

/** 패키지의 현재 서명 인증서 SHA-256 을 읽는다. */
internal class SignatureChecker(private val pm: PackageManager, private val sdkInt: Int) {

    /** 서명자 다이제스트(대문자 16진). 설치돼 있지 않거나 보이지 않으면(패키지 가시성) `null`. */
    fun signerDigests(packageName: String): Set<String>? {
        val signers = try {
            if (sdkInt >= SDK_P) currentSigners(packageName) else legacySigners(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            // 설치 안 됨·가시성 없음은 "확인 못 함" 으로 돌려 신뢰를 올리지 않는다
            null
        }
        if (signers.isNullOrEmpty()) return null
        return signers.mapTo(LinkedHashSet()) { sha256(it) }
    }

    /** 모든 서명자가 [allowed] 안에 있을 때만 `true`. 서명자 하나라도 모르면 거부한다. */
    fun matches(packageName: String, allowed: Set<String>): Boolean {
        val digests = signerDigests(packageName) ?: return false
        return allowed.containsAll(digests)
    }

    @Suppress("NewApi", "DEPRECATION")
    private fun currentSigners(packageName: String): Array<Signature>? {
        val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        // 지난 인증서(교체 이력)는 보지 않는다 — 유출돼 교체한 옛 키로 서명한 앱을 받지 않기 위해서다
        return info.signingInfo?.apkContentsSigners
    }

    @Suppress("DEPRECATION")
    private fun legacySigners(packageName: String): Array<Signature>? {
        @Suppress("PackageManagerGetSignatures")
        val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        return info.signatures
    }

    private companion object {
        const val SDK_P = 28

        fun sha256(signature: Signature): String =
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }
}
