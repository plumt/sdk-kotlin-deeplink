package net.plumt.deeplink.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import net.plumt.deeplink.DeepLinkUri
import net.plumt.deeplink.TrustPolicy

/** Android 앱에서 자주 쓰는 조립 도우미. */
public object AndroidDeepLinks {

    /** 자기 패키지를 SELF 로 보는 [TrustPolicy.standard]. */
    @JvmStatic
    public fun trustPolicy(context: Context): TrustPolicy = TrustPolicy.standard(context.packageName)

    /**
     * 자기 앱만 받을 수 있는 딥링크 인텐트. 패키지를 고정하고 extras 를 넣지 않는다.
     * 알림 탭처럼 앱이 자기에게 보내는 링크에 쓴다 — 다른 앱이 가로채지 못한다.
     *
     * @throws IllegalArgumentException [uri] 가 [DeepLinkUri.parse] 를 통과하지 못할 때
     */
    @JvmStatic
    public fun selfIntent(context: Context, uri: String): Intent {
        DeepLinkUri.parse(uri)
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(context.packageName)
    }

    /**
     * [selfIntent] 를 담은 Activity PendingIntent. 항상 `FLAG_IMMUTABLE` 을 붙여 받는 쪽이 내용을
     * 바꿔 채우지 못하게 한다.
     *
     * @param flags 추가 플래그. 기본 `FLAG_UPDATE_CURRENT`. `FLAG_MUTABLE` 을 주면 거부한다
     * @throws IllegalArgumentException [uri] 가 틀렸거나 [flags] 에 `FLAG_MUTABLE` 이 있을 때
     */
    @JvmStatic
    @JvmOverloads
    public fun selfPendingIntent(
        context: Context,
        uri: String,
        requestCode: Int,
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT,
    ): PendingIntent {
        require(flags and FLAG_MUTABLE_VALUE == 0) { "FLAG_MUTABLE 은 쓸 수 없다: flags=$flags" }
        return PendingIntent.getActivity(context, requestCode, selfIntent(context, uri), flags or PendingIntent.FLAG_IMMUTABLE)
    }

    // PendingIntent.FLAG_MUTABLE(API 31) 의 값. minSdk 23 에서도 검사하려고 상수로 둔다
    private const val FLAG_MUTABLE_VALUE = 1 shl 25
}
