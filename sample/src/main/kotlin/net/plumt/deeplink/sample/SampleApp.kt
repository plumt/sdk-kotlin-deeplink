package net.plumt.deeplink.sample

import android.app.Application
import net.plumt.deeplink.DeepLinks
import net.plumt.deeplink.Param
import net.plumt.deeplink.TrustLevel
import net.plumt.deeplink.deepLinkConfig
import net.plumt.deeplink.deepLinkRoute

/** 프로세스에 하나 — 대기열과 재개가 이 인스턴스에 묶인다 */
class SampleApp : Application() {
    val itemId = Param.long("id", 1L..Long.MAX_VALUE)

    val deepLinks: DeepLinks by lazy {
        DeepLinks.create(
            this,
            deepLinkConfig {
                route(deepLinkRoute("item", "plumtsample://item/{id}") { pathParam(itemId) })
                route(deepLinkRoute("web-item", "https://example.com/item/{id}") { pathParam(itemId) })
                // 알림 탭처럼 자기 앱이 발급한 링크만 연다
                route(deepLinkRoute("notice", "plumtsample://notice") { requiredTrust(TrustLevel.SELF) })
                externalHost("example.com")
            },
        )
    }
}
