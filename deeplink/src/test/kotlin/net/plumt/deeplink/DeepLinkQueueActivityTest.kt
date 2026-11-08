package net.plumt.deeplink

import net.plumt.deeplink.internal.AndroidDeepLinks
import net.plumt.deeplink.internal.DeepLinkIntentReader
import net.plumt.deeplink.internal.DeepLinkQueue
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import net.plumt.deeplink.CallerVerification
import net.plumt.deeplink.DeepLinkChain
import net.plumt.deeplink.DeepLinkHandler
import net.plumt.deeplink.DeepLinkInterceptor
import net.plumt.deeplink.DeepLinkOutcome
import net.plumt.deeplink.Rejection
import net.plumt.deeplink.TrustLevel
import net.plumt.deeplink.deepLinkHandler
import net.plumt.deeplink.deepLinkRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** README 의 전체 Activity 예제와 같은 구성. 시험값은 프로세스 전역 큐처럼 [Companion] 에 둔다. */
class LinkActivity : ComponentActivity() {
    private val reader by lazy { DeepLinkIntentReader.create(CallerVerifier.create(this, trustedCallers)) }
    private val handler: DeepLinkHandler by lazy {
        deepLinkHandler {
            route(deepLinkRoute("item", "myapp://item"))
            route(deepLinkRoute("slow", "myapp://slow"))
            route(deepLinkRoute("partner", "myapp://partner") { requiredTrust(TrustLevel.FIRST_PARTY) })
            route(deepLinkRoute("notice", "myapp://notice") { requiredTrust(TrustLevel.SELF) })
            trustPolicy(AndroidDeepLinks.trustPolicy(this@LinkActivity))
            interceptor(object : DeepLinkInterceptor {
                override suspend fun intercept(chain: DeepLinkChain): DeepLinkOutcome {
                    if (chain.link.route.id == "slow") slowGate?.await()
                    return chain.proceed()
                }
            })
            router { link ->
                routed += "${System.identityHashCode(this@LinkActivity)}:${link.route.id}:${link.trust}:${link.caller.verification}"
                true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enqueue(reader.readLaunch(this, savedInstanceState))
        queue.consumeWhileResumed(this) { outcomes += handler.handle(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enqueue(reader.readNewIntent(this, intent))
    }

    /** 화면이 멈춘 채 onNewIntent 가 오는 경우를 재현한다(시험 전용). */
    fun deliver(intent: Intent) = onNewIntent(intent)

    private fun enqueue(result: IntentReadResult) {
        if (result is IntentReadResult.Link) queue.offer(result.request)
    }

    override fun getLaunchedFromUid(): Int = fromUid
    override fun getLaunchedFromPackage(): String? = fromPackage

    companion object {
        internal var queue = DeepLinkQueue()
        var trustedCallers: List<TrustedCaller> = emptyList()
        var slowGate: CompletableDeferred<Unit>? = null
        var fromUid = -1
        var fromPackage: String? = null
        val routed = mutableListOf<String>()
        val outcomes = mutableListOf<DeepLinkOutcome>()
    }
}

/** 실제 Activity 흐름과 처리기까지 묶어 본다: 콜드·웜·회전·최근 앱 재진입·신뢰 유지. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 33, 35])
class DeepLinkQueueActivityTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        LinkActivity.queue = DeepLinkQueue()
        LinkActivity.trustedCallers = emptyList()
        LinkActivity.slowGate = null
        LinkActivity.fromUid = -1
        LinkActivity.fromPackage = null
        LinkActivity.routed.clear()
        LinkActivity.outcomes.clear()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
    private fun build(intent: Intent): ActivityController<LinkActivity> = Robolectric.buildActivity(LinkActivity::class.java, intent)
    private fun launch(intent: Intent) = build(intent).setup().also { idle() }
    private fun routeIds() = LinkActivity.routed.map { it.split(':')[1] }

    @Test fun `콜드 스타트 링크는 첫 RESUMED 에 한 번 처리된다`() {
        val controller = build(view("myapp://item")).create().start()
        idle()
        assertEquals(emptyList<String>(), LinkActivity.routed)
        assertEquals(1, LinkActivity.queue.size)
        controller.resume()
        idle()
        assertEquals(listOf("item"), routeIds())
        assertEquals(0, LinkActivity.queue.size)
    }

    @Test fun `웜 스타트 링크는 떠 있는 인스턴스가 처리한다`() {
        val controller = launch(Intent(Intent.ACTION_MAIN))
        controller.newIntent(view("myapp://item"))
        idle()
        val owner = System.identityHashCode(controller.get()).toString()
        assertEquals(listOf("$owner:item:PUBLIC:NONE"), LinkActivity.routed)
        assertEquals("myapp://item", controller.get().intent.dataString)
    }

    @Test fun `회전 뒤에 같은 링크를 다시 처리하지 않는다`() {
        val controller = launch(view("myapp://item"))
        val before = controller.get()
        controller.recreate()
        idle()
        assertNotSame(before, controller.get())
        assertEquals(listOf("item"), routeIds())

        controller.newIntent(view("myapp://item"))
        idle()
        controller.recreate()
        idle()
        assertEquals(listOf("item", "item"), routeIds())
    }

    @Test fun `재생성 뒤 새 인스턴스가 다음 링크를 받고 파괴된 인스턴스는 받지 않는다`() {
        val controller = launch(Intent(Intent.ACTION_MAIN))
        val old = System.identityHashCode(controller.get())
        controller.recreate()
        idle()
        controller.newIntent(view("myapp://item"))
        idle()
        val owner = LinkActivity.routed.single().substringBefore(':').toInt()
        assertTrue(owner != old)
        assertEquals(System.identityHashCode(controller.get()), owner)
    }

    @Test fun `두 인스턴스 중 앞 인스턴스만 처리한다`() {
        val back = launch(Intent(Intent.ACTION_MAIN))
        back.pause().stop()
        val front = launch(view("myapp://item"))
        assertEquals(System.identityHashCode(front.get()), LinkActivity.routed.single().substringBefore(':').toInt())
    }

    @Test fun `화면이 멈춘 동안 온 링크는 복귀 때 처리된다`() {
        val controller = launch(Intent(Intent.ACTION_MAIN))
        controller.pause().stop()
        controller.get().deliver(view("myapp://item"))
        idle()
        assertEquals(emptyList<String>(), LinkActivity.routed)
        controller.restart().resume()
        idle()
        assertEquals(listOf("item"), routeIds())
    }

    @Test fun `처리 중 회전하면 새 인스턴스가 한 번 끝낸다`() {
        val gate = CompletableDeferred<Unit>()
        LinkActivity.slowGate = gate
        val controller = launch(view("myapp://slow"))
        assertEquals(emptyList<String>(), LinkActivity.routed)
        controller.recreate()
        idle()
        gate.complete(Unit)
        idle()
        assertEquals(System.identityHashCode(controller.get()), LinkActivity.routed.single().substringBefore(':').toInt())
    }

    @Test fun `처리 중 끝내면 링크를 버린다`() {
        val gate = CompletableDeferred<Unit>()
        LinkActivity.slowGate = gate
        val controller = launch(view("myapp://slow"))
        controller.get().finish()
        controller.pause().stop().destroy()
        idle()
        gate.complete(Unit)
        idle()
        assertEquals(0, LinkActivity.queue.size)
        assertEquals(emptyList<String>(), LinkActivity.routed)
    }

    @Test fun `최근 앱에서 다시 연 실행은 옛 링크를 처리하지 않는다`() {
        launch(view("myapp://item").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertEquals(emptyList<String>(), LinkActivity.routed)
        assertEquals(0, LinkActivity.queue.size)
    }

    // ---- 신뢰 유지 ----

    @Test fun `SELF 서명 링크는 콜드 스타트 큐를 지나도 SELF 다`() {
        launch(AndroidDeepLinks.selfIntent(context, "myapp://notice"))
        assertEquals("notice:SELF:SELF_ISSUED", LinkActivity.routed.single().substringAfter(':'))
        assertTrue(LinkActivity.outcomes.single() is DeepLinkOutcome.Handled)
    }

    @Test fun `SELF 서명 링크는 웜 스타트 큐를 지나도 SELF 다`() {
        val controller = launch(Intent(Intent.ACTION_MAIN))
        controller.newIntent(AndroidDeepLinks.selfIntent(context, "myapp://notice"))
        idle()
        assertEquals("notice:SELF:SELF_ISSUED", LinkActivity.routed.single().substringAfter(':'))
    }

    @Test fun `SELF 링크가 멈춤 뒤에 처리돼도 SELF 다`() {
        val controller = launch(Intent(Intent.ACTION_MAIN))
        controller.pause().stop()
        controller.get().deliver(AndroidDeepLinks.selfIntent(context, "myapp://notice"))
        idle()
        controller.restart().resume()
        idle()
        assertEquals("notice:SELF:SELF_ISSUED", LinkActivity.routed.single().substringAfter(':'))
    }

    @Test fun `외부 링크는 앞서 처리한 SELF 링크 뒤에 와도 SELF 로 올라가지 않는다`() {
        val controller = launch(AndroidDeepLinks.selfIntent(context, "myapp://notice"))
        controller.newIntent(view("myapp://notice"))
        idle()
        assertEquals(1, LinkActivity.routed.size)
        val rejection = (LinkActivity.outcomes[1] as DeepLinkOutcome.Rejected).rejection as Rejection.InsufficientTrust
        assertEquals(TrustLevel.PUBLIC, rejection.actual)
    }

    @Test fun `위조 토큰 링크는 큐를 지나도 PUBLIC 이다`() {
        launch(view("myapp://notice#plumt-self=AAAA"))
        val rejection = (LinkActivity.outcomes.single() as DeepLinkOutcome.Rejected).rejection as Rejection.InsufficientTrust
        assertEquals(TrustLevel.PUBLIC, rejection.actual)
    }

    @Test @Config(sdk = [34, 35]) fun `제휴 앱은 처음 실행에서만 FIRST_PARTY 이고 큐를 지나도 유지되며 onNewIntent 로는 올라가지 않는다`() {
        installPackage("com.partner", arrayOf(SIG_A))
        setUidPackages(1234, "com.partner")
        LinkActivity.trustedCallers = listOf(TrustedCaller("com.partner", setOf(digest(SIG_A))))
        LinkActivity.fromUid = 1234
        LinkActivity.fromPackage = "com.partner"

        val controller = launch(view("myapp://partner"))
        assertEquals(
            "partner:${TrustLevel.FIRST_PARTY}:${CallerVerification.SIGNATURE_VERIFIED}",
            LinkActivity.routed.single().substringAfter(':'),
        )

        controller.newIntent(view("myapp://partner"))
        idle()
        assertEquals(1, LinkActivity.routed.size)
        assertTrue(LinkActivity.outcomes[1] is DeepLinkOutcome.Rejected)
    }

    @Test fun `넘치면 멈춘 동안 가장 최근 링크만 복귀 때 차례로 처리된다`() {
        LinkActivity.queue = DeepLinkQueue(3)
        val controller = launch(Intent(Intent.ACTION_MAIN))
        controller.pause().stop()
        repeat(5) { controller.get().deliver(view("myapp://item?n=$it")) }
        assertEquals(3, LinkActivity.queue.size)
        controller.restart().resume()
        idle()
        assertEquals(3, LinkActivity.routed.size)
        assertEquals(0, LinkActivity.queue.size)
    }
}
