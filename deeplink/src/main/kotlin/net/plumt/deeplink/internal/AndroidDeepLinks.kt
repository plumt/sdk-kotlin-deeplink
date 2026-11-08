package net.plumt.deeplink.internal

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import net.plumt.deeplink.DeepLinkException
import net.plumt.deeplink.DeepLinkUri
import net.plumt.deeplink.TrustPolicy

/** 자기 링크 발급과 기본 신뢰 정책. [net.plumt.deeplink.DeepLinks] 가 쓴다. */
internal object AndroidDeepLinks {

    /** 자기 앱이 발급한 링크([selfIntent])를 SELF 로 보는 [TrustPolicy.standard]. */
    fun trustPolicy(context: Context): TrustPolicy = TrustPolicy.standard(context.packageName)

    /**
     * 자기 앱이 **의도해서** 발급하는 딥링크 인텐트. 패키지를 고정하고, data URI 뒤에 이 앱만 만들 수 있는
     * 서명 토큰을 프래그먼트(`#plumt-self=…`)로 붙인다. [DeepLinkIntentReader] 가 토큰을 확인해
     * [net.plumt.deeplink.CallerVerification.SELF_ISSUED] 로 보고 떼어 낸다. extras 는 넣지 않는다.
     *
     * **외부에서 온 문자열을 넣지 않는다.** 채팅 메시지·웹 페이지·다른 사용자가 고른 링크를 여기에 넣으면
     * 그 링크가 SELF 경로를 연다(혼동된 대리인). 그런 링크는 `Intent(ACTION_VIEW, uri)` 로 그대로 열면
     * PUBLIC 으로 처리된다. 푸시 페이로드의 URI 는 서버가 직접 정한 값일 때만 넣는다.
     *
     * 토큰은 URI 문자열 전체에 묶인다. 토큰이 든 인텐트를 다른 앱에 넘기면 그 URI 를 SELF 로 재생할 수 있으니
     * 넘기지 않는다. 같은 앱에 인텐트 필터가 여럿이면 반환값에 `setClass` 로 컴포넌트를 고정해도 된다.
     *
     * 처음 호출은 앱 전용 저장소(`noBackupFilesDir`)에 키 파일을 만들거나 읽는다(짧은 디스크 I/O).
     *
     * @throws IllegalArgumentException [uri] 가 [DeepLinkUri.parse] 를 통과하지 못하거나 `#` 을 담을 때
     * @throws DeepLinkException 서명 키를 만들거나 읽지 못할 때(저장소 오류). `cause` 에 원래 예외가 있다
     */
    fun selfIntent(context: Context, uri: String): Intent {
        DeepLinkUri.parse(uri)
        require('#' !in uri) { "자기 링크에는 프래그먼트(#)를 쓸 수 없다" }
        val token = SelfLinkSigner.of(context).sign(uri)
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri + SelfLinkToken.MARKER + token)).setPackage(context.packageName)
    }

    /**
     * [selfIntent] 를 담은 Activity PendingIntent. 항상 `FLAG_IMMUTABLE` 을 붙여 받는 쪽이 내용을
     * 바꿔 채우지 못하게 한다.
     *
     * @param flags 추가 플래그. 기본 `FLAG_UPDATE_CURRENT`. `FLAG_MUTABLE` 을 주면 거부한다
     * @throws IllegalArgumentException [uri] 가 틀렸거나 [flags] 에 `FLAG_MUTABLE` 이 있을 때
     * @throws DeepLinkException 서명 키를 준비하지 못했을 때([selfIntent] 참고)
     */
    fun selfPendingIntent(
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
