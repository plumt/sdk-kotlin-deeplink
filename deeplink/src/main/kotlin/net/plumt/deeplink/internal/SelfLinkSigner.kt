package net.plumt.deeplink.internal

import android.content.Context
import net.plumt.deeplink.DeepLinkException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 자기 앱이 발급한 링크에 붙이는 서명 토큰. 키는 앱 전용 저장소(백업 제외 디렉터리)의 파일에 두고
 * 다른 앱은 읽을 수 없다. 토큰은 URI 문자열 전체에 묶이므로 다른 URI 에 옮겨 붙이면 맞지 않는다.
 */
internal class SelfLinkSigner(private val keyFile: File, private val random: SecureRandom = SecureRandom()) {

    private val lock = Any()

    @Volatile
    private var key: ByteArray? = null

    /**
     * @throws DeepLinkException 키를 만들거나 읽지 못할 때(저장소 오류). cause 에 원래 예외가 있다
     */
    fun sign(uri: String): String = hex(mac(loadOrCreate(), uri))

    /** 토큰이 맞는지. 키가 아직 없거나 읽지 못하면 `false` — 이 앱이 발급한 적이 없다는 뜻이다. 키를 만들지 않는다. */
    fun verify(uri: String, token: String): Boolean {
        if (token.length != TOKEN_LENGTH) return false
        val existing = synchronized(lock) { key ?: readKey()?.also { key = it } } ?: return false
        return MessageDigest.isEqual(hex(mac(existing, uri)).toByteArray(), token.toByteArray())
    }

    private fun loadOrCreate(): ByteArray = synchronized(lock) {
        key ?: (readKey() ?: create()).also { key = it }
    }

    private fun readKey(): ByteArray? = try {
        if (keyFile.isFile) keyFile.readBytes().takeIf { it.size == KEY_SIZE } else null
    } catch (_: IOException) {
        // 읽을 수 없는 키는 없는 것으로 본다. 서명 쪽은 새로 만들고, 확인 쪽은 false 로 거부한다
        null
    }

    private fun create(): ByteArray = try {
        val dir = keyFile.parentFile ?: throw IOException("키 파일의 디렉터리가 없다")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("키 디렉터리를 만들지 못했다")
        val tmp = File(dir, keyFile.name + ".tmp")
        tmp.writeBytes(ByteArray(KEY_SIZE).also(random::nextBytes))
        if (!tmp.renameTo(keyFile)) throw IOException("키 파일을 옮기지 못했다")
        // 다른 프로세스가 동시에 만들었을 수 있다 — 파일에 남은 값을 쓴다
        readKey() ?: throw IOException("키 파일을 다시 읽지 못했다")
    } catch (e: IOException) {
        throw DeepLinkException("자기 링크 서명 키를 준비하지 못했다", e)
    } catch (e: SecurityException) {
        throw DeepLinkException("자기 링크 서명 키를 준비하지 못했다", e)
    }

    private fun mac(key: ByteArray, uri: String): ByteArray =
        Mac.getInstance(ALGORITHM).run {
            init(SecretKeySpec(key, ALGORITHM))
            doFinal((DOMAIN + uri).toByteArray(Charsets.UTF_8))
        }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        private const val KEY_SIZE = 32
        private const val DOMAIN = "net.plumt.deeplink.self-link.v1\n"
        const val TOKEN_LENGTH = 64
        private const val KEY_FILE = "net.plumt.deeplink.self-link.key"
        private val shared = ConcurrentHashMap<String, SelfLinkSigner>()

        /** 프로세스 안에서 앱마다 하나. 키를 한 번만 읽는다. */
        fun of(context: Context): SelfLinkSigner {
            val file = File(context.applicationContext.noBackupFilesDir, KEY_FILE)
            return shared.getOrPut(file.absolutePath) { SelfLinkSigner(file) }
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/** 토큰은 data URI 의 프래그먼트로 싣는다. extras 를 쓰지 않으므로 받는 쪽이 extras 를 풀 필요가 없다. */
internal object SelfLinkToken {
    private const val FRAGMENT = "plumt-self="
    const val MARKER = "#$FRAGMENT"

    /** (토큰 프래그먼트를 뗀 URI, 토큰). 토큰 프래그먼트가 없으면 토큰은 `null`. */
    fun split(data: String): Pair<String, String?> {
        val hash = data.indexOf('#')
        if (hash < 0 || !data.startsWith(FRAGMENT, hash + 1)) return data to null
        return data.substring(0, hash) to data.substring(hash + MARKER.length)
    }
}
