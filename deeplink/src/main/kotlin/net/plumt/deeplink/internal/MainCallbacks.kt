package net.plumt.deeplink.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.plumt.deeplink.DeepLinkHandle
import net.plumt.deeplink.DeepLinkOutcome

/** 콜백 형태 호출. suspend 처리를 메인 스레드 코루틴에서 돌리고 결과를 메인 스레드로 한 번 준다. */
internal class MainCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun launch(block: suspend () -> DeepLinkOutcome, onOutcome: (DeepLinkOutcome) -> Unit): DeepLinkHandle {
        val job: Job = scope.launch { onOutcome(block()) }
        return JobHandle(job)
    }

    private class JobHandle(private val job: Job) : DeepLinkHandle {
        override fun cancel() = job.cancel()
        override fun toString(): String = "DeepLinkHandle(active=${job.isActive})"
    }
}
