package net.plumt.deeplink

import net.plumt.deeplink.internal.DeepLinkQueue
import android.app.Activity
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.plumt.deeplink.DeepLinkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 소비 규칙: 인스턴스가 여럿일 때 앞 인스턴스에서 한 번만, 콜드 스타트 보존, 멈춘 뒤 복귀, 처리 중 멈춤·파괴.
 * 소비자 수명은 [LifecycleRegistry] 로 직접 움직인다. 실제 Activity 흐름은 [DeepLinkQueueActivityTest] 가 본다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 33, 35])
class ResumedConsumptionTest {

    private val queue = DeepLinkQueue()
    private val handled = mutableListOf<String>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private inner class Instance(val name: String, initial: Lifecycle.State = Lifecycle.State.CREATED) : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = initial }
        override val lifecycle: Lifecycle get() = registry

        fun move(state: Lifecycle.State) {
            registry.currentState = state
            idle()
        }

        fun consume(): Closeable = queue.consumeWhileResumed(this) { handled += "$name:${it.uri}" }.also { idle() }
    }

    private fun send(uri: String): DeepLinkRequest = DeepLinkRequest(uri).also {
        queue.offer(it)
        idle()
    }

    // ---- 앞 인스턴스에서 한 번만 ----

    @Test fun `인스턴스가 여럿이어도 링크 하나는 앞 인스턴스에서 한 번만 처리된다`() {
        val back = Instance("back", Lifecycle.State.RESUMED).apply { consume() }
        // 앱이 떠 있을 때 링크가 와 새 인스턴스가 위에 생긴다: 아래는 멈추고 위가 RESUMED
        back.move(Lifecycle.State.CREATED)
        val fronts = List(3) { Instance("front$it").apply { consume() } }
        fronts[0].move(Lifecycle.State.RESUMED)
        fronts[1].move(Lifecycle.State.STARTED)

        send("myapp://a")

        assertEquals(listOf("front0:myapp://a"), handled)
        assertEquals(0, queue.size)
    }

    @Test fun `뒤에 깔린 인스턴스는 앞 인스턴스가 사라지기 전까지 가져가지 않는다`() {
        val back = Instance("back", Lifecycle.State.STARTED).apply { consume() }
        send("myapp://a")
        assertEquals(emptyList<String>(), handled)
        assertEquals(1, queue.size)

        back.move(Lifecycle.State.RESUMED)
        assertEquals(listOf("back:myapp://a"), handled)
    }

    @Test fun `동시에 RESUMED 인 소비자가 둘이어도 링크마다 한 번이다`() {
        val a = Instance("a", Lifecycle.State.RESUMED).apply { consume() }
        val b = Instance("b", Lifecycle.State.RESUMED).apply { consume() }
        assertEquals(Lifecycle.State.RESUMED, a.lifecycle.currentState)
        assertEquals(Lifecycle.State.RESUMED, b.lifecycle.currentState)
        send("myapp://a")
        send("myapp://a") // 같은 URI 두 건 = 두 번. 구독자 수를 곱하지 않는다
        assertEquals(2, handled.size)
    }

    // ---- 콜드 스타트 ----

    @Test fun `소비자가 생기기 전에 온 링크는 첫 RESUMED 까지 남는다`() {
        val request = send("myapp://cold")
        val activity = Instance("main")
        activity.consume()
        assertEquals(emptyList<String>(), handled)
        activity.move(Lifecycle.State.STARTED)
        assertEquals(emptyList<String>(), handled)
        assertEquals(1, queue.size)

        val taken = mutableListOf<DeepLinkRequest>()
        queue.consumeWhileResumed(Instance("nav", Lifecycle.State.RESUMED)) { taken += it }
        idle()
        assertSame(request, taken.single())
    }

    @Test fun `이미 RESUMED 인 뒤 늦게 붙은 소비자도 받는다`() {
        // 화면 구성(Compose 첫 구성)이 onResume 보다 늦는 경우
        send("myapp://cold")
        Instance("late", Lifecycle.State.RESUMED).consume()
        assertEquals(listOf("late:myapp://cold"), handled)
    }

    // ---- 멈춤과 복귀 ----

    @Test fun `멈춘 동안 온 링크는 다시 앞에 설 때 한 번 처리되고 재개만으로 다시 처리되지 않는다`() {
        val activity = Instance("main", Lifecycle.State.RESUMED).apply { consume() }
        activity.move(Lifecycle.State.CREATED)
        send("myapp://a")
        assertEquals(emptyList<String>(), handled)

        activity.move(Lifecycle.State.RESUMED)
        assertEquals(listOf("main:myapp://a"), handled)

        activity.move(Lifecycle.State.STARTED)
        activity.move(Lifecycle.State.RESUMED)
        assertEquals(1, handled.size)
    }

    @Test fun `재생성된 인스턴스는 처리 끝난 링크를 다시 받지 않는다`() {
        val old = Instance("old", Lifecycle.State.RESUMED).apply { consume() }
        send("myapp://a")
        old.move(Lifecycle.State.DESTROYED)
        Instance("new", Lifecycle.State.RESUMED).consume()
        assertEquals(listOf("old:myapp://a"), handled)
    }

    @Test fun `링크는 넣은 순서대로 하나씩 처리된다`() {
        val activity = Instance("main", Lifecycle.State.RESUMED)
        val gates = List(2) { CompletableDeferred<Unit>() }
        val events = mutableListOf<String>()
        queue.consumeWhileResumed(activity) {
            val id = it.uri.last().digitToInt()
            events += "begin$id"
            gates[id].await()
            events += "end$id"
        }
        send("myapp://0")
        send("myapp://1")
        assertEquals(listOf("begin0"), events)
        gates[0].complete(Unit)
        idle()
        assertEquals(listOf("begin0", "end0", "begin1"), events)
        gates[1].complete(Unit)
        idle()
        assertEquals(listOf("begin0", "end0", "begin1", "end1"), events)
    }

    // ---- 처리 중 PAUSE ----

    @Test fun `처리 중 멈추면 취소하지 않고 보류했다가 복귀하면 이어서 끝낸다`() {
        val activity = Instance("main", Lifecycle.State.RESUMED)
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        queue.consumeWhileResumed(activity) {
            events += "begin:${activity.lifecycle.currentState}"
            gate.await()
            events += "route:${activity.lifecycle.currentState}"
        }
        send("myapp://a")
        assertEquals(listOf("begin:RESUMED"), events)

        activity.move(Lifecycle.State.STARTED)
        gate.complete(Unit)
        idle()
        assertEquals("멈춘 화면이 라우터까지 가면 안 된다", listOf("begin:RESUMED"), events)
        assertEquals("보류 중인 링크를 큐에 되돌리지 않는다", 0, queue.size)

        activity.move(Lifecycle.State.RESUMED)
        assertEquals(listOf("begin:RESUMED", "route:RESUMED"), events)
    }

    @Test fun `다른 디스패처에서 돌아와도 멈춘 동안에는 이어지지 않는다`() {
        val activity = Instance("main", Lifecycle.State.RESUMED)
        val paused = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        queue.consumeWhileResumed(activity) {
            withContext(Dispatchers.Default) {
                paused.await(5, TimeUnit.SECONDS)
                events += "background"
            }
            events += "route:${Looper.myLooper() == Looper.getMainLooper()}:${activity.lifecycle.currentState}"
        }
        send("myapp://a")
        activity.move(Lifecycle.State.STARTED)
        paused.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while ("background" !in events && System.nanoTime() < deadline) Thread.sleep(5)
        idle()
        assertEquals(listOf("background"), events.toList())

        activity.move(Lifecycle.State.RESUMED)
        assertEquals(listOf("background", "route:true:RESUMED"), events.toList())
    }

    @Test fun `처리 중 멈춘 인스턴스 대신 앞에 선 인스턴스가 다음 링크를 받고 보류된 링크는 옮겨 가지 않는다`() {
        val back = Instance("back", Lifecycle.State.RESUMED)
        val gate = CompletableDeferred<Unit>()
        queue.consumeWhileResumed(back) {
            handled += "back-begin"
            gate.await()
            handled += "back-end"
        }
        send("myapp://a")
        back.move(Lifecycle.State.CREATED)
        Instance("front", Lifecycle.State.RESUMED).consume()
        send("myapp://b")
        assertEquals(listOf("back-begin", "front:myapp://b"), handled)

        gate.complete(Unit)
        idle()
        back.move(Lifecycle.State.RESUMED)
        assertEquals(listOf("back-begin", "front:myapp://b", "back-end"), handled)
    }

    // ---- 처리 중 파괴·닫기 ----

    @Test fun `처리 중 파괴되면 취소하고 같은 요청을 맨 앞에 되돌려 다음 소비자가 처음부터 처리한다`() {
        val old = Instance("old", Lifecycle.State.RESUMED)
        val never = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        queue.consumeWhileResumed(old) {
            events += "old-begin"
            never.await()
            events += "old-end"
        }
        val slow = send("myapp://slow")
        val later = DeepLinkRequest("myapp://later")
        queue.offer(later)

        old.move(Lifecycle.State.DESTROYED)
        assertEquals(2, queue.size)

        val taken = mutableListOf<DeepLinkRequest>()
        queue.consumeWhileResumed(Instance("new", Lifecycle.State.RESUMED)) { taken += it }
        idle()
        assertEquals(listOf("old-begin"), events)
        assertEquals(2, taken.size)
        assertSame("판정이 든 요청 객체가 그대로 돌아온다", slow, taken[0])
        assertSame(later, taken[1])
    }

    @Test fun `멈춘 채 파괴돼도 되돌린다`() {
        val old = Instance("old", Lifecycle.State.RESUMED)
        queue.consumeWhileResumed(old) { CompletableDeferred<Unit>().await() }
        send("myapp://slow")
        old.move(Lifecycle.State.CREATED)
        old.move(Lifecycle.State.DESTROYED)
        assertEquals(1, queue.size)
    }

    @Test fun `close 도 처리 중인 링크를 되돌리고 그 뒤로는 소비하지 않는다`() {
        val activity = Instance("main", Lifecycle.State.RESUMED)
        val consumer = queue.consumeWhileResumed(activity) {
            handled += it.uri
            CompletableDeferred<Unit>().await()
        }
        send("myapp://a")
        consumer.close()
        idle()
        assertEquals(1, queue.size)
        send("myapp://b")
        activity.move(Lifecycle.State.STARTED)
        activity.move(Lifecycle.State.RESUMED)
        assertEquals(listOf("myapp://a"), handled)
        assertEquals(2, queue.size)
    }

    @Test fun `되돌릴 자리가 없으면 새 링크를 지키고 버린다`() {
        val small = DeepLinkQueue(1)
        val old = Instance("old", Lifecycle.State.RESUMED)
        small.consumeWhileResumed(old) { CompletableDeferred<Unit>().await() }
        small.offer(DeepLinkRequest("myapp://slow"))
        idle()
        val newer = DeepLinkRequest("myapp://newer")
        small.offer(newer)
        old.move(Lifecycle.State.DESTROYED)
        assertEquals(1, small.size)
        assertSame(newer, small.poll())
    }

    @Test fun `끝나는 Activity 의 처리 중 링크는 되돌리지 않는다`() {
        val activity = Robolectric.buildActivity(OwnerActivity::class.java).create().get()
        activity.registry.currentState = Lifecycle.State.RESUMED
        queue.consumeWhileResumed(activity) { CompletableDeferred<Unit>().await() }
        send("myapp://slow")
        activity.finish()
        activity.registry.currentState = Lifecycle.State.DESTROYED
        idle()
        assertTrue(activity.isFinishing)
        assertEquals(0, queue.size)
    }

    @Test fun `회전 중인 Activity 의 처리 중 링크는 되돌린다`() {
        val activity = Robolectric.buildActivity(OwnerActivity::class.java).create().get()
        activity.registry.currentState = Lifecycle.State.RESUMED
        queue.consumeWhileResumed(activity) { CompletableDeferred<Unit>().await() }
        send("myapp://slow")
        activity.registry.currentState = Lifecycle.State.DESTROYED
        idle()
        assertEquals(1, queue.size)
    }

    // ---- 실패 ----

    @Test fun `처리 안의 자체 취소는 그 링크로 끝나고 다음 링크를 계속 받는다`() {
        val activity = Instance("main", Lifecycle.State.RESUMED)
        queue.consumeWhileResumed(activity) {
            handled += it.uri
            if (it.uri.endsWith("cancel")) throw CancellationException("자체 취소")
        }
        send("myapp://cancel")
        assertEquals(0, queue.size)
        send("myapp://next")
        assertEquals(listOf("myapp://cancel", "myapp://next"), handled)
    }

    @Test fun `처리 예외는 처리되지 않은 예외로 올라가고 링크를 되돌리지 않는다`() {
        val caught = mutableListOf<Throwable>()
        val thread = Thread.currentThread()
        val previous = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> caught += e }
        try {
            queue.consumeWhileResumed(Instance("main", Lifecycle.State.RESUMED)) { error("실패") }
            send("myapp://a")
        } finally {
            thread.uncaughtExceptionHandler = previous
        }
        assertEquals("실패", caught.single().message)
        assertEquals(0, queue.size)
    }

    // ---- 수명·스레드 ----

    @Test fun `파괴된 소유자에는 소비자를 붙이지 않는다`() {
        val owner = Instance("gone", Lifecycle.State.RESUMED)
        owner.move(Lifecycle.State.DESTROYED)
        val consumer = owner.consume()
        assertEquals("콜백이 오지 않으므로 큐에 남기면 영영 안 풀린다", 0, queue.consumerCount)
        send("myapp://a")
        assertEquals(1, queue.size)
        consumer.close()
    }

    @Test fun `소비자는 파괴·close 뒤 큐에 남지 않는다`() {
        val owner = Instance("a", Lifecycle.State.RESUMED).apply { consume() }
        val closing = Instance("b", Lifecycle.State.RESUMED).consume()
        assertEquals(2, queue.consumerCount)
        owner.move(Lifecycle.State.DESTROYED)
        assertEquals(1, queue.consumerCount)
        closing.close()
        assertEquals(0, queue.consumerCount)
    }

    @Test fun `close 를 두 번 불러도 된다`() {
        val consumer = Instance("main", Lifecycle.State.RESUMED).consume()
        consumer.close()
        consumer.close()
        send("myapp://a")
        assertEquals(emptyList<String>(), handled)
    }

    @Test fun `메인 스레드가 아니면 거부한다`() {
        val owner = Instance("main", Lifecycle.State.RESUMED)
        val consumer = owner.consume()
        val errors = arrayOfNulls<Throwable>(2)
        thread {
            errors[0] = runCatching { queue.consumeWhileResumed(owner) {} }.exceptionOrNull()
            errors[1] = runCatching { consumer.close() }.exceptionOrNull()
        }.join()
        assertTrue(errors[0] is IllegalStateException)
        assertTrue(errors[1] is IllegalStateException)
    }

    @Test fun `다른 스레드에서 넣어도 메인 스레드에서 한 번씩 처리된다`() {
        val big = DeepLinkQueue(1000)
        val seen = mutableListOf<String>()
        var onMain = true
        big.consumeWhileResumed(Instance("main", Lifecycle.State.RESUMED)) {
            onMain = onMain && Looper.myLooper() == Looper.getMainLooper()
            seen += it.uri
        }
        List(8) { t -> thread { repeat(25) { big.offer(DeepLinkRequest("myapp://$t/$it")) } } }.forEach { it.join() }
        idle()
        assertEquals(200, seen.size)
        assertEquals(200, seen.toSet().size)
        assertTrue(onMain)
    }
}

/** isFinishing 분기를 보려는 실제 Activity. */
class OwnerActivity : Activity(), LifecycleOwner {
    val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry
}
