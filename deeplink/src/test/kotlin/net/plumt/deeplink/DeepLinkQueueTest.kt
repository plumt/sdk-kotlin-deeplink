package net.plumt.deeplink

import net.plumt.deeplink.internal.DeepLinkQueue
import net.plumt.deeplink.CallerIdentity
import net.plumt.deeplink.CallerVerification
import net.plumt.deeplink.DeepLinkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 큐 자체의 계약: 순서, 상한과 넘칠 때의 동작, 객체 보존, 스레드 안전. */
class DeepLinkQueueTest {

    private fun request(id: Int) = DeepLinkRequest("myapp://item/$id")

    @Test fun `넣은 순서대로 나온다`() {
        val queue = DeepLinkQueue()
        val requests = List(3) { request(it) }
        requests.forEach { assertNull(queue.offer(it)) }
        assertEquals(3, queue.size)
        requests.forEach { assertSame(it, queue.poll()) }
        assertNull(queue.poll())
        assertEquals(0, queue.size)
    }

    @Test fun `기본 상한`() {
        assertEquals(8, DeepLinkQueue.DEFAULT_CAPACITY)
        assertEquals(8, DeepLinkQueue().capacity)
    }

    @Test fun `상한은 1 이상`() {
        assertIae("capacity 는 1 이상이어야 한다: 0") { DeepLinkQueue(0) }
        assertIae("capacity 는 1 이상이어야 한다: -1") { DeepLinkQueue(-1) }
        assertEquals(1, DeepLinkQueue(1).capacity)
    }

    @Test fun `넘치면 가장 오래된 링크를 버리고 돌려준다`() {
        val queue = DeepLinkQueue(2)
        val (a, b, c, d) = List(4) { request(it) }
        assertNull(queue.offer(a))
        assertNull(queue.offer(b))
        assertSame(a, queue.offer(c))
        assertSame(b, queue.offer(d))
        assertEquals(2, queue.size)
        assertSame(c, queue.poll())
        assertSame(d, queue.poll())
    }

    @Test fun `폭주해도 상한을 넘지 않고 마지막 링크가 남는다`() {
        val queue = DeepLinkQueue(8)
        var evicted = 0
        repeat(10_000) { if (queue.offer(request(it)) != null) evicted++ }
        assertEquals(8, queue.size)
        assertEquals(9_992, evicted)
        assertEquals((9_992 until 10_000).map { "myapp://item/$it" }, List(8) { queue.poll()!!.uri })
    }

    @Test fun `신뢰 높은 링크도 밀려날 수 있지만 다른 링크의 판정을 올리지 않는다`() {
        val queue = DeepLinkQueue(1)
        val self = DeepLinkRequest("myapp://notice", CallerIdentity("com.self", CallerVerification.SELF_ISSUED))
        val external = DeepLinkRequest("myapp://notice")
        queue.offer(self)
        assertSame(self, queue.offer(external))
        val next = queue.poll()!!
        assertSame(external, next)
        assertEquals(CallerIdentity.UNKNOWN, next.caller)
    }

    @Test fun `clear 는 대기 링크를 비운다`() {
        val queue = DeepLinkQueue()
        queue.offer(request(1))
        queue.clear()
        assertEquals(0, queue.size)
        assertNull(queue.poll())
        queue.clear()
    }

    @Test fun `되돌린 링크는 맨 앞이고 자리가 없으면 새 링크를 밀어내지 않는다`() {
        val queue = DeepLinkQueue(2)
        val (a, b, c) = List(3) { request(it) }
        queue.offer(b)
        queue.putBack(a)
        assertSame(a, queue.poll())
        queue.offer(c)
        queue.putBack(a)
        assertEquals(2, queue.size)
        assertSame(b, queue.poll())
        assertSame(c, queue.poll())
    }

    @Test fun `toString 에 URI 가 없다`() {
        val queue = DeepLinkQueue(3)
        queue.offer(DeepLinkRequest("myapp://item/secret-value"))
        assertEquals("DeepLinkQueue(size=1, capacity=3)", queue.toString())
        assertFalse("secret-value" in queue.toString())
    }

    @Test fun `여러 스레드가 넣고 빼도 링크마다 한 번씩만 나온다`() {
        val threads = 8
        val each = 500
        val queue = DeepLinkQueue(threads * each)
        val taken = Collections.synchronizedList(ArrayList<DeepLinkRequest>())
        val start = CountDownLatch(1)
        val offerDone = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads * 2)
        val offered = List(threads) { t -> List(each) { request(t * each + it) } }
        offered.forEach { batch ->
            pool.execute {
                start.await()
                batch.forEach { queue.offer(it) }
                offerDone.countDown()
            }
        }
        repeat(threads) {
            pool.execute {
                start.await()
                while (offerDone.count > 0 || queue.size > 0) queue.poll()?.let { taken += it }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(threads * each, taken.size)
        assertEquals(threads * each, taken.toSet().size)
        assertEquals(offered.flatten().toSet(), taken.toSet())
    }
}
