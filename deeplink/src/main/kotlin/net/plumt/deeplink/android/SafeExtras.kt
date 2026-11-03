package net.plumt.deeplink.android

import android.content.Intent

/** [SafeExtras] 의 읽기 결과. */
public sealed class ExtraRead<out T : Any> {
    /** 값이 있고 형식이 맞다. */
    public data class Present<out T : Any>(val value: T) : ExtraRead<T>()

    /** 키가 없다. */
    public data object Absent : ExtraRead<Nothing>()

    /** 키는 있는데 쓸 수 없다. */
    public data class Invalid(val reason: ExtraInvalidReason) : ExtraRead<Nothing>()
}

/** [ExtraRead.Invalid] 의 사유. */
public enum class ExtraInvalidReason {
    /** 기대한 타입이 아니다. */
    WRONG_TYPE,

    /** 문자열이 상한보다 길다. */
    TOO_LONG,

    /** extras 를 풀 수 없다. */
    UNREADABLE,
}

/**
 * 외부에서 온 인텐트의 extras 를 원시 타입으로만 읽는다.
 *
 * Intent·PendingIntent·Bundle·Parcelable·Uri 를 읽는 함수는 일부러 없다. 받은 Intent 를 그대로
 * `startActivity` 하면 외부 앱이 우리 권한으로 비공개 컴포넌트를 열게 된다(인텐트 리다이렉션).
 */
public object SafeExtras {

    /** 문자열 상한 기본값. */
    public const val DEFAULT_MAX_LENGTH: Int = 1024

    /**
     * @throws IllegalArgumentException [maxLength] 가 1 미만일 때
     */
    @JvmStatic
    @JvmOverloads
    public fun string(intent: Intent, key: String, maxLength: Int = DEFAULT_MAX_LENGTH): ExtraRead<String> {
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

    @JvmStatic
    public fun int(intent: Intent, key: String): ExtraRead<Int> = typed(intent, key)

    @JvmStatic
    public fun long(intent: Intent, key: String): ExtraRead<Long> = typed(intent, key)

    @JvmStatic
    public fun boolean(intent: Intent, key: String): ExtraRead<Boolean> = typed(intent, key)

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
