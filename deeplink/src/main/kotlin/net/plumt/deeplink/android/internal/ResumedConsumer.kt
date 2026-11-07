package net.plumt.deeplink.android.internal

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import net.plumt.deeplink.DeepLinkRequest
import net.plumt.deeplink.android.DeepLinkQueue
import java.io.Closeable
import kotlin.coroutines.CoroutineContext

/** 소비 코루틴 하나와 그 수명. 필드는 메인 스레드에서만 만진다. [DeepLinkQueue.consumeWhileResumed] 의 계약을 구현한다. */
internal class ResumedConsumer(
    private val queue: DeepLinkQueue,
    private val owner: LifecycleOwner,
    private val handle: suspend (DeepLinkRequest) -> Unit,
) : DefaultLifecycleObserver, Closeable {

    private val gate = ResumedGate()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var closed = false

    fun start() {
        // 파괴된 소유자에는 생명주기 콜백이 오지 않아 닫을 기회가 없다
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            closed = true
            return
        }
        queue.register(wakeup)
        job = CoroutineScope(gate).launch { consume() }
        wakeup.trySend(Unit) // 등록 전에 들어온 링크(콜드 스타트)
        owner.lifecycle.addObserver(this)
    }

    // 이 코루틴은 게이트 위에서만 돈다 — poll 은 항상 RESUMED 일 때 일어나고, 그 사이에 suspend 지점이 없다
    private suspend fun consume() {
        while (true) {
            wakeup.receive()
            while (true) {
                val request = queue.poll() ?: break
                process(request)
                yield() // 다음 링크 전에 메인 루퍼를 한 바퀴 돌린다. 그 사이 멈췄으면 게이트에서 기다린다
            }
        }
    }

    private suspend fun process(request: DeepLinkRequest) {
        try {
            handle(request)
        } catch (e: CancellationException) {
            // 소비자가 살아 있으면 handle 안의 자체 취소다. 여기서 삼키지 않으면 소비 코루틴이 조용히 끝난다
            if (currentCoroutineContext().isActive) return
            if ((owner as? Activity)?.isFinishing != true) queue.putBack(request)
            throw e
        }
    }

    override fun onResume(owner: LifecycleOwner) = gate.open()

    override fun onPause(owner: LifecycleOwner) = gate.shut()

    override fun onDestroy(owner: LifecycleOwner) = close()

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "소비자는 메인 스레드에서 닫아야 한다" }
        if (closed) return
        closed = true
        owner.lifecycle.removeObserver(this)
        queue.unregister(wakeup)
        job?.cancel()
        gate.release()
    }
}

/**
 * 열려 있을 때만 메인 스레드에서 코드를 돌리는 디스패처. 닫혀 있는 동안 온 코드는 순서대로 쌓아 두었다가 열리면 돌린다.
 * 취소하지 않고 멈추는 것이 목적이다. [release] 뒤에는 계속 열려 있어 취소된 코루틴이 끝까지 정리된다.
 */
private class ResumedGate : CoroutineDispatcher() {
    private val main = Handler(Looper.getMainLooper())
    private val held = ArrayDeque<Runnable>() // 메인 스레드 전용
    private var isOpen = false
    private var released = false

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        main.post {
            held.addLast(block)
            flush()
        }
    }

    // 생명주기 콜백 안에서 소비자 코드를 돌리지 않으려고 한 번 미룬다
    fun open() {
        isOpen = true
        main.post { flush() }
    }

    fun shut() {
        if (!released) isOpen = false
    }

    fun release() {
        released = true
        open()
    }

    private fun flush() {
        while (isOpen) (held.removeFirstOrNull() ?: return).run()
    }
}
