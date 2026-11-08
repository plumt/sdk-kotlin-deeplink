package net.plumt.deeplink.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import net.plumt.deeplink.AppLinkStatus
import net.plumt.deeplink.DeepLinkOutcome
import net.plumt.deeplink.DeepLinkRouter

/** 확인용 화면 — 받은 링크의 처리 결과와 App Link 상태를 제목에 적는다 */
class SampleActivity : ComponentActivity() {
    private val app get() = application as SampleApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deepLinks = app.deepLinks
        deepLinks.offerLaunch(this, savedInstanceState)
        val router = DeepLinkRouter { link ->
            title = "route=${link.route.id} trust=${link.trust}"
            true
        }
        deepLinks.consumeWhileResumed(this, router) { outcome ->
            if (outcome !is DeepLinkOutcome.Handled) title = "outcome=$outcome"
        }
        val status = deepLinks.appLinkStatus()
        if (status is AppLinkStatus.Available && !status.opensInApp("example.com")) {
            // 실제 앱이라면 안내 문구와 함께 deepLinks.appLinkSettingsIntent() 를 연다
            title = "app link not verified"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        app.deepLinks.offerNewIntent(this, intent)
    }
}
