package net.plumt.deeplink.internal

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.LifecycleOwner
import net.plumt.deeplink.AppLinkStatus
import net.plumt.deeplink.CallerVerifier
import net.plumt.deeplink.DeepLinkConfig
import net.plumt.deeplink.DeepLinkHandle
import net.plumt.deeplink.DeepLinkOutcome
import net.plumt.deeplink.DeepLinkRequest
import net.plumt.deeplink.DeepLinkResolution
import net.plumt.deeplink.DeepLinkRouter
import net.plumt.deeplink.DeepLinks
import net.plumt.deeplink.ExternalLinkResult
import net.plumt.deeplink.ExternalOpenResult
import net.plumt.deeplink.IntentReadResult
import net.plumt.deeplink.ResolvedDeepLink
import net.plumt.deeplink.TrustPolicy
import java.io.Closeable

/** [DeepLinks] 기본 구현. 판정은 [DeepLinkEngine], 대기열은 [DeepLinkQueue], 인텐트는 [DeepLinkIntentReader] 가 맡는다. */
internal class DefaultDeepLinks(
    private val context: Context,
    override val config: DeepLinkConfig,
    private val reader: DeepLinkIntentReader,
    private val appLinks: AppLinkReader,
) : DeepLinks {

    private val engine = DeepLinkEngine(config, config.trustPolicy ?: TrustPolicy.standard(context.packageName))
    private val queue = DeepLinkQueue(config.queueCapacity)
    private val callbacks = MainCallbacks()

    override fun readLaunch(activity: Activity, savedInstanceState: Bundle?): IntentReadResult = reader.readLaunch(activity, savedInstanceState)

    override fun readNewIntent(activity: Activity, intent: Intent): IntentReadResult = reader.readNewIntent(activity, intent)

    override fun offer(request: DeepLinkRequest): DeepLinkRequest? = queue.offer(request)

    override fun clearPending() = queue.clear()

    override val pendingCount: Int get() = queue.size

    override fun consumeWhileResumed(owner: LifecycleOwner, router: DeepLinkRouter, onOutcome: (DeepLinkOutcome) -> Unit): Closeable =
        consume(queue, engine, owner, router, onOutcome)

    override fun resolve(request: DeepLinkRequest): DeepLinkResolution = engine.resolve(request)

    override suspend fun handle(request: DeepLinkRequest, router: DeepLinkRouter): DeepLinkOutcome = engine.handle(request, router)

    override fun handle(request: DeepLinkRequest, router: DeepLinkRouter, onOutcome: (DeepLinkOutcome) -> Unit): DeepLinkHandle =
        callbacks.launch({ engine.handle(request, router) }, onOutcome)

    override suspend fun resume(link: ResolvedDeepLink, router: DeepLinkRouter): DeepLinkOutcome = engine.resume(link, router)

    override fun resume(link: ResolvedDeepLink, router: DeepLinkRouter, onOutcome: (DeepLinkOutcome) -> Unit): DeepLinkHandle {
        requireOwned(engine, link)
        return callbacks.launch({ engine.resume(link, router) }, onOutcome)
    }

    override fun selfIntent(uri: String): Intent = AndroidDeepLinks.selfIntent(context, uri)

    override fun selfPendingIntent(uri: String, requestCode: Int): PendingIntent =
        AndroidDeepLinks.selfPendingIntent(context, uri, requestCode)

    override fun selfPendingIntent(uri: String, requestCode: Int, flags: Int): PendingIntent =
        AndroidDeepLinks.selfPendingIntent(context, uri, requestCode, flags)

    override fun appLinkStatus(): AppLinkStatus = appLinks.read()

    override fun appLinkSettingsIntent(): Intent = appLinkSettingsIntent(context.packageName)

    override fun externalIntent(uri: String): ExternalLinkResult = ExternalLinks.intent(uri, config.externalHosts, config.uriRules)

    override fun openExternal(context: Context, uri: String): ExternalOpenResult =
        ExternalLinks.open(context, uri, config.externalHosts, config.uriRules)

    override fun toString(): String = "DeepLinks(routes=${config.routes.size}, pending=$pendingCount)"

    companion object {
        fun create(context: Context, config: DeepLinkConfig): DeepLinks {
            val app = context.applicationContext ?: context
            val verifier = config.callerVerifier ?: CallerVerifier.create(app, config.trustedCallers)
            return DefaultDeepLinks(app, config, DeepLinkIntentReader.create(verifier, config.allowedActions), AppLinkReader.platform(app))
        }
    }
}

/** 기본·가짜 구현이 함께 쓰는 소비자 연결. */
internal fun consume(
    queue: DeepLinkQueue,
    engine: DeepLinkEngine,
    owner: LifecycleOwner,
    router: DeepLinkRouter,
    onOutcome: (DeepLinkOutcome) -> Unit,
): Closeable {
    check(Looper.myLooper() == Looper.getMainLooper()) { "consumeWhileResumed 는 메인 스레드에서 불러야 한다" }
    return ResumedConsumer(queue, owner) { request -> onOutcome(engine.handle(request, router)) }.also { it.start() }
}

/** 콜백 [DeepLinks.resume] 이 인자 실수를 호출 즉시 알리게 한다. */
internal fun requireOwned(engine: DeepLinkEngine, link: ResolvedDeepLink) {
    require(link.owner === engine) { "이 DeepLinks 가 만든 링크가 아니다: $link" }
}
