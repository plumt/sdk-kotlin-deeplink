package net.plumt.deeplink.internal

import android.content.Intent
import net.plumt.deeplink.ExtraInvalidReason
import net.plumt.deeplink.ExtraRead

/**
 * 외부에서 온 인텐트의 extras 를 원시 타입으로만 읽는다.
 *
 * Intent·PendingIntent·Bundle·Parcelable·Uri 를 읽는 함수는 일부러 없다. 받은 Intent 를 그대로
 * `startActivity` 하면 외부 앱이 우리 권한으로 비공개 컴포넌트를 열게 된다(인텐트 리다이렉션).
 *
 * **역직렬화 주의**: 키 하나를 읽으면 그 키의 값은 타입을 확인하기 **전에** 역직렬화된다. 발신 앱이 그 키에
 * Parcelable·Serializable 을 넣었다면 앱 클래스로더로 만들어진 뒤 [ExtraInvalidReason.WRONG_TYPE] 이 된다.
 * API 33+ 는 읽은 키의 값만, API 23~32 는 extras 에 처음 닿을 때 Bundle 전체가 역직렬화된다.
 * 신뢰할 수 없는 발신자의 extras 는 꼭 필요한 키만 읽는다.
 */
internal object SafeExtras {

    /** 문자열 상한 기본값. */
    const val DEFAULT_MAX_LENGTH: Int = 1024

    /**
     * @throws IllegalArgumentException [maxLength] 가 1 미만일 때
     */
    fun string(intent: Intent, key: String, maxLength: Int = DEFAULT_MAX_LENGTH): ExtraRead<String> {
        require(maxLength >= 1) { "maxLength 는 1 이상이어야 한다: $maxLength" }
        return when (val read = raw(intent, key)) {
            is ExtraRead.Present -> {
                val value = read.value as? String ?: return ExtraRead.Invalid(ExtraInvalidReason.WRONG_TYPE)
                if (value.length > maxLength) ExtraRead.Invalid(ExtraInvalidReason.TOO_LONG) else ExtraRead.Present(value)
            }
            is ExtraRead.Invalid -> read
            ExtraRead.Absent -> ExtraRead.Absent
        }
    }

    fun int(intent: Intent, key: String): ExtraRead<Int> = typed(intent, key)

    fun long(intent: Intent, key: String): ExtraRead<Long> = typed(intent, key)

    fun boolean(intent: Intent, key: String): ExtraRead<Boolean> = typed(intent, key)

    private inline fun <reified T : Any> typed(intent: Intent, key: String): ExtraRead<T> =
        when (val read = raw(intent, key)) {
            is ExtraRead.Present -> (read.value as? T)?.let { ExtraRead.Present(it) } ?: ExtraRead.Invalid(ExtraInvalidReason.WRONG_TYPE)
            is ExtraRead.Invalid -> read
            ExtraRead.Absent -> ExtraRead.Absent
        }

    private fun raw(intent: Intent, key: String): ExtraRead<Any> = try {
        val extras = intent.extras
        if (extras == null || !extras.containsKey(key)) {
            ExtraRead.Absent
        } else {
            @Suppress("DEPRECATION")
            val value = extras.get(key)
            if (value == null) ExtraRead.Invalid(ExtraInvalidReason.WRONG_TYPE) else ExtraRead.Present(value)
        }
    } catch (_: RuntimeException) {
        // 풀 수 없는 extras 는 Invalid(UNREADABLE) 로 알린다
        ExtraRead.Invalid(ExtraInvalidReason.UNREADABLE)
    }
}
