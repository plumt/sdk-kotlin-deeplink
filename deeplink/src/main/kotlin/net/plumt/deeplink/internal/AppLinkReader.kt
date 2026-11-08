package net.plumt.deeplink.internal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import net.plumt.deeplink.AppLinkHostState
import net.plumt.deeplink.AppLinkStatus

/** App Link 검증 상태를 읽는다. 시험에서 플랫폼 상태를 바꿔 끼우려고 함수 하나로 둔다. */
internal fun interface AppLinkReader {
    fun read(): AppLinkStatus

    companion object {
        /** 이 기기의 [DomainVerificationManager](API 31+). 그 아래는 [AppLinkStatus.Unsupported]. */
        fun platform(context: Context): AppLinkReader {
            val app = context.applicationContext
            return AppLinkReader {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) readDomainVerification(app) else AppLinkStatus.Unsupported
            }
        }

        /** 플랫폼 상태 값(DOMAIN_STATE_*)을 공개 상태로. 모르는 값은 앱으로 열린다고 가정하지 않는다. */
        fun hostState(value: Int): AppLinkHostState = when (value) {
            STATE_VERIFIED -> AppLinkHostState.VERIFIED
            STATE_SELECTED -> AppLinkHostState.SELECTED
            else -> AppLinkHostState.NONE
        }

        // DomainVerificationUserState.DOMAIN_STATE_SELECTED·VERIFIED(API 31) 의 값
        private const val STATE_SELECTED = 1
        private const val STATE_VERIFIED = 2

        @RequiresApi(Build.VERSION_CODES.S)
        private fun readDomainVerification(context: Context): AppLinkStatus {
            val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return AppLinkStatus.Unavailable
            val state: DomainVerificationUserState = try {
                manager.getDomainVerificationUserState(context.packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                // 자기 패키지를 못 찾는 것은 플랫폼 상태를 얻지 못한 것이다
                null
            } catch (_: RuntimeException) {
                // 시스템 서비스가 죽었거나 거부했다. 상태를 모른다고 알린다
                null
            } ?: return AppLinkStatus.Unavailable
            return AppLinkStatus.Available(
                linkHandlingAllowed = state.isLinkHandlingAllowed,
                hosts = state.hostToStateMap.mapValues { hostState(it.value) },
            )
        }
    }
}

/** 이 앱의 "지원되는 링크 열기" 설정 화면(API 31+). 그 아래는 앱 상세 설정 화면. */
internal fun appLinkSettingsIntent(packageName: String): Intent {
    val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
    } else {
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
    }
    return Intent(action, Uri.parse("package:$packageName"))
}
