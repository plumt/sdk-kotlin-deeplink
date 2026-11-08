package net.plumt.deeplink.internal

import android.os.Looper
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.channels.Channel
import net.plumt.deeplink.DeepLinkRequest
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Activity 가 읽은 딥링크를 앞에 선(RESUMED) 화면이 가져갈 때까지 보관하는 앱 전역 큐.
 *
 * 인텐트는 화면이 준비되기 전에 오고(콜드 스타트), 살아 있는 Activity 인스턴스는 여럿일 수 있다.
 * `onCreate`·`onNewIntent` 에서 [offer] 로 넣고 [consumeWhileResumed] 로 소비하면 다음이 보장된다.
 *
 * - 링크 하나는 소비자 한 곳에서 한 번만 처리된다. 살아 있는 인스턴스 수와 무관하다
 * - RESUMED 인 소비자만 가져간다. 뒤에 깔린 인스턴스는 가져가지 않는다
 * - 소비자가 아직 없거나 RESUMED 가 아니면 링크는 큐에 남는다
 * - 넣은 [DeepLinkRequest] 객체가 그대로 나온다. 발신자 판정을 다시 하지 않으므로 큐를 지나도 신뢰가 바뀌지 않는다
 *
 * **프로세스에 하나만 두고**(`object`·`Application`·DI 싱글턴) Activity 필드에 두지 않는다. 메모리에만 있고
 * 디스크에 쓰지 않으므로 프로세스가 죽으면 함께 사라진다(URI 에 토큰이 들어 있을 수 있고, 판정을 복원하면 안 된다).
 * 처리 끝난 링크는 큐에 없으므로 회전·재생성으로 다시 처리되지 않는다.
 * 계정이 바뀌면(로그아웃) [clear] 로 비운다.
 *
 * [toString] 은 URI 를 보이지 않는다.
 *
 * @param capacity 대기할 수 있는 최대 개수. 1 이상. 넘치면 가장 오래된 링크부터 버린다([offer])
 * @throws IllegalArgumentException [capacity] 가 1 미만일 때
 */
internal class DeepLinkQueue(
    val capacity: Int = DEFAULT_CAPACITY,
) {
    private val pending = ArrayDeque<DeepLinkRequest>()
    private val wakeups = CopyOnWriteArrayList<Channel<Unit>>()

    init {
        require(capacity >= 1) { "capacity 는 1 이상이어야 한다: $capacity" }
    }

    /** 지금 대기 중인 링크 수. */
    val size: Int get() = synchronized(pending) { pending.size }

    /**
     * 링크를 맨 뒤에 넣는다. 어느 스레드에서든 부를 수 있다.
     *
     * 큐가 가득 차 있으면 **가장 오래된 링크를 버린다.** 외부 앱은 인텐트를 얼마든지 보낼 수 있으므로 대기 수에
     * 상한을 두고, 사용자가 마지막에 누른 링크가 살아남게 한다. 그래서 신뢰 높은 링크도 뒤따르는 링크에
     * 밀려날 수 있다.
     *
     * @return 밀려나 버려진 링크. 없으면 `null`
     */
    fun offer(request: DeepLinkRequest): DeepLinkRequest? {
        val evicted = synchronized(pending) {
            val oldest = if (pending.size >= capacity) pending.removeFirst() else null
            pending.addLast(request)
            oldest
        }
        wake()
        return evicted
    }

    /** 대기 중인 링크를 모두 버린다. 처리 중인 링크는 건드리지 않는다. 어느 스레드에서든 부를 수 있다. */
    fun clear() {
        synchronized(pending) { pending.clear() }
    }

    /**
     * [owner] 가 RESUMED 인 동안 링크를 차례로 가져가 [handle] 을 부른다. 메인 스레드에서 부른다.
     * 소비자는 [owner] 가 파괴되면 스스로 닫힌다. 그 전에 그만 받으려면 돌려준 값의 `close()` 를 메인 스레드에서 부른다.
     *
     * 한 소비자는 링크를 하나씩 처리한다. [handle] 이 끝나야 다음 링크를 가져간다. 소비자가 여럿이면 각자 처리하므로
     * 서로 다른 소비자 사이의 처리 순서는 보장되지 않는다. [handle] 은 메인 스레드에서 돈다.
     *
     * **처리 중 화면이 멈추면(PAUSE)** 취소하지 않고 보류한다. [handle] 의 메인 스레드 코드는 RESUMED 일 때만
     * 이어지므로 다시 앞에 설 때 이어서 끝나고, 멈춘 화면이 라우터를 부르지 않는다. 인터셉터가 띄운 시스템
     * 대화상자 때문에 멈춰도 링크를 잃지 않는다. `withContext` 로 디스패처를 옮긴 구간은 멈춘 동안에도 돌고, 돌아올 때 보류된다.
     *
     * **돌아오지 못하고 소비자가 닫히면**(파괴·`close()`) [handle] 을 취소한다. 끝나지 않은 처리이므로 링크를 큐
     * 맨 앞에 되돌려 다음 소비자가 처음부터 처리한다(회전). 큐가 가득 차 있으면 되돌리지 않고 버린다. [owner] 가
     * `isFinishing` 인 Activity 면 사용자가 떠난 것이므로 버린다. 다시 처리되므로 [handle] 은 라우터를 부른 뒤에
     * suspend 하지 않는다.
     *
     * [handle] 안의 자체 취소(`withTimeout` 이 빠져나온 경우 등)는 그 링크의 처리가 끝난 것으로 보고 다음 링크로 넘어간다.
     * [handle] 이 그 밖의 예외를 던지면 메인 스레드의 처리되지 않은 예외가 된다. 링크는 되돌리지 않는다.
     * `DeepLinkHandler.handle` 은 소비자 코드의 실패를 값으로 돌려주므로 보통 던지지 않는다.
     *
     * @param owner 보통 Activity. Compose 에서는 `NavHost` 목적지 안이 아니라 그 바깥의 `LocalLifecycleOwner` 를 넘긴다
     * @param handle 링크 하나를 처리한다. 보통 `DeepLinkHandler.handle` 을 부른다
     * @return 소비자를 닫는 핸들. 두 번 닫아도 된다
     * @throws IllegalStateException 메인 스레드가 아닐 때
     */
    fun consumeWhileResumed(owner: LifecycleOwner, handle: suspend (DeepLinkRequest) -> Unit): Closeable {
        check(Looper.myLooper() == Looper.getMainLooper()) { "consumeWhileResumed 는 메인 스레드에서 불러야 한다" }
        return ResumedConsumer(this, owner, handle).also { it.start() }
    }

    override fun toString(): String = "DeepLinkQueue(size=$size, capacity=$capacity)"

    internal fun poll(): DeepLinkRequest? = synchronized(pending) { pending.removeFirstOrNull() }

    /** 끝나지 않은 처리를 맨 앞에 되돌린다. 자리가 없으면 그보다 새 링크를 밀어내지 않고 버린다. */
    internal fun putBack(request: DeepLinkRequest) {
        synchronized(pending) {
            if (pending.size >= capacity) return
            pending.addFirst(request)
        }
        wake()
    }

    /** 붙어 있는 소비자 수. 큐가 끝난 화면을 붙들고 있지 않은지 시험에서 본다. */
    internal val consumerCount: Int get() = wakeups.size

    internal fun register(wakeup: Channel<Unit>) {
        wakeups += wakeup
    }

    internal fun unregister(wakeup: Channel<Unit>) {
        wakeups -= wakeup
    }

    private fun wake() {
        wakeups.forEach { it.trySend(Unit) }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = net.plumt.deeplink.DeepLinkConfig.DEFAULT_QUEUE_CAPACITY
    }
}
