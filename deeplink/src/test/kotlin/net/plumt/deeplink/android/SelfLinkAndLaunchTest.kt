package net.plumt.deeplink.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import net.plumt.deeplink.CallerIdentity
import net.plumt.deeplink.CallerVerification
import net.plumt.deeplink.DeepLinkException
import net.plumt.deeplink.android.internal.SelfLinkSigner
import net.plumt.deeplink.android.internal.SelfLinkToken
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 자기 링크 서명 키·토큰. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 33, 35])
class SelfLinkSignerTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private fun keyFile(name: String = "k.key") = File(context.noBackupFilesDir, "t/$name")

    @Test fun `서명과 확인 - URI 에 묶인다`() {
        val signer = SelfLinkSigner(keyFile())
        val token = signer.sign("myapp://a")
        assertEquals(SelfLinkSigner.TOKEN_LENGTH, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(signer.verify("myapp://a", token))
        assertFalse(signer.verify("myapp://b", token))
        assertFalse(signer.verify("myapp://a", token.uppercase()))
        assertFalse(signer.verify("myapp://a", token.dropLast(1)))
        assertFalse(signer.verify("myapp://a", ""))
    }

    @Test fun `키가 없으면 확인은 false 이고 키를 만들지 않는다`() {
        val file = keyFile("none.key")
        assertFalse(SelfLinkSigner(file).verify("myapp://a", "0".repeat(64)))
        assertFalse(file.exists())
    }

    @Test fun `프로세스 재시작 - 새 인스턴스도 같은 키로 확인한다`() {
        val file = keyFile("restart.key")
        val token = SelfLinkSigner(file).sign("myapp://a")
        assertTrue(SelfLinkSigner(file).verify("myapp://a", token))
        assertEquals(32, file.length())
    }

    @Test fun `다른 키로 만든 토큰은 맞지 않는다`() {
        val token = SelfLinkSigner(keyFile("one.key")).sign("myapp://a")
        val other = SelfLinkSigner(keyFile("two.key"))
        other.sign("myapp://warm")
        assertFalse(other.verify("myapp://a", token))
    }

    @Test fun `손상된 키 파일 - 확인은 false, 서명은 새 키로 바꾼다`() {
        val file = keyFile("broken.key")
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(5))
        assertFalse(SelfLinkSigner(file).verify("myapp://a", "0".repeat(64)))
        val signer = SelfLinkSigner(file)
        val token = signer.sign("myapp://a")
        assertEquals(32, file.length())
        assertTrue(SelfLinkSigner(file).verify("myapp://a", token))
    }

    @Test fun `키를 만들 수 없으면 DeepLinkException 에 원인을 담는다`() {
        val blocker = keyFile("blocker")
        blocker.parentFile!!.mkdirs()
        blocker.writeText("x")  // 디렉터리 자리에 파일이 있다
        val e = runCatching { SelfLinkSigner(File(blocker, "k.key")).sign("myapp://a") }.exceptionOrNull()
        assertTrue("$e", e is DeepLinkException)
        assertEquals("자기 링크 서명 키를 준비하지 못했다", e!!.message)
        assertTrue(e.cause is java.io.IOException)
        val noParent = runCatching { SelfLinkSigner(File("k.key")).sign("myapp://a") }.exceptionOrNull()
        assertTrue(noParent is DeepLinkException)
    }

    @Test fun `키 생성은 난수를 쓰고 동시에 불러도 키 하나다`() {
        val file = keyFile("concurrent.key")
        val fixed = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) = bytes.fill(7)
        }
        val signer = SelfLinkSigner(file, fixed)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val tokens = (1..32).map { pool.submit<String> { start.await(); signer.sign("myapp://a") } }
        start.countDown()
        val distinct = tokens.map { it.get(10, TimeUnit.SECONDS) }.toSet()
        pool.shutdown()
        assertEquals(1, distinct.size)
        assertArrayEquals(ByteArray(32) { 7 }, file.readBytes())
    }

    @Test fun `앱마다 공유 인스턴스`() {
        assertTrue(SelfLinkSigner.of(context) === SelfLinkSigner.of(context))
    }

    @Test fun `토큰 프래그먼트 분리`() {
        assertEquals("myapp://a" to "t", SelfLinkToken.split("myapp://a#plumt-self=t"))
        assertEquals("myapp://a#other" to null, SelfLinkToken.split("myapp://a#other"))
        assertEquals("myapp://a" to null, SelfLinkToken.split("myapp://a"))
        assertEquals("myapp://a" to "", SelfLinkToken.split("myapp://a#plumt-self="))
    }
}

/** C2 — 처음 실행과 onNewIntent 를 나눠 읽는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34, 35])
class LaunchReadTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    /** 처음 실행 여부를 기록하는 판정기. */
    private class Recording : CallerVerifier {
        val initial = mutableListOf<Boolean>()
        override fun identify(activity: Activity, intent: Intent, initialLaunch: Boolean): CallerIdentity {
            initial += initialLaunch
            return CallerIdentity.UNKNOWN
        }
    }

    @Test fun `C2 readLaunch 만 처음 실행으로 본다`() {
        val recording = Recording()
        val reader = DeepLinkIntentReader.create(recording)
        val activity = launch(view("myapp://a"))
        reader.readLaunch(activity, null)
        reader.readLaunch(activity, null)              // 같은 인텐트로 다시 — 여전히 처음 실행
        reader.readNewIntent(activity, view("myapp://b"))
        activity.intent = view("myapp://c")            // onNewIntent 에서 setIntent 한 뒤 readLaunch 를 잘못 부름
        reader.readLaunch(activity, null)
        assertEquals(listOf(true, true, false, false), recording.initial)
    }

    @Test fun `C2 재생성(savedInstanceState 있음)은 다시 처리하지 않는다`() {
        val recording = Recording()
        val reader = DeepLinkIntentReader.create(recording)
        assertEquals(IntentReadResult.NotDeepLink, reader.readLaunch(launch(view("myapp://a")), Bundle()))
        assertTrue(recording.initial.isEmpty())
    }

    @Test @Config(sdk = [34, 35])
    fun `C2 시나리오 - 처음 띄운 제휴 앱 정보가 나중 링크에 붙지 않는다`() {
        installPackage("partner.app", arrayOf(SIG_A))
        setUidPackages(10_123, "partner.app")
        val reader = DeepLinkIntentReader.create(context, listOf(TrustedCaller("partner.app", setOf(digest(SIG_A)))))
        val activity = launchFrom(view("myapp://a"), 10_123, "partner.app")
        val first = reader.readLaunch(activity, null) as IntentReadResult.Link
        assertEquals(CallerVerification.SIGNATURE_VERIFIED, first.request.caller.verification)
        val evil = view("myapp://partner-only")
        activity.intent = evil
        val second = reader.readNewIntent(activity, evil) as IntentReadResult.Link
        assertEquals(CallerIdentity.UNKNOWN, second.request.caller)
        val misuse = reader.readLaunch(activity, null) as IntentReadResult.Link
        assertEquals(CallerIdentity.UNKNOWN, misuse.request.caller)
    }

    @Test @Config(sdk = [34, 35])
    fun `H2 자기 앱 Splash 가 띄운 외부 링크는 SELF 가 아니다`() {
        setUidPackages(Process.myUid(), context.packageName)
        val reader = DeepLinkIntentReader.create(context)
        val activity = launchFrom(view("myapp://notice"), Process.myUid(), context.packageName)
        val link = reader.readLaunch(activity, null) as IntentReadResult.Link
        assertEquals(CallerIdentity(context.packageName, CallerVerification.OS_VERIFIED), link.request.caller)
        assertEquals(net.plumt.deeplink.TrustLevel.PUBLIC, AndroidDeepLinks.trustPolicy(context).resolve(link.request.caller))
    }
}

/** H3 — 실제 Parcel 로 만든 extras. 알 수 없는 Parcelable 을 담는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 32, 33, 34, 35])
class ParcelledExtrasTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    /** 받는 앱에 없는 클래스를 흉내 낸다. CREATOR 가 불리면 역직렬화된 것이다. */
    class Foreign() : Parcelable {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(1)

        companion object {
            @Volatile var created = 0

            @JvmField val CREATOR = object : Parcelable.Creator<Foreign> {
                override fun createFromParcel(source: Parcel): Foreign { created++; source.readInt(); return Foreign() }
                override fun newArray(size: Int): Array<Foreign?> = arrayOfNulls(size)
            }
        }
    }

    /** 발신 프로세스에서 넘어온 것처럼 Parcel 을 거친 인텐트. [loader] 로 extras 를 푼다. */
    private fun received(loader: ClassLoader): Intent {
        val sent = Intent(Intent.ACTION_VIEW, Uri.parse("myapp://item/1")).putExtra("s", "hello").putExtra("evil", Foreign())
        val parcel = Parcel.obtain()
        try {
            sent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return Intent.CREATOR.createFromParcel(parcel).apply { setExtrasClassLoader(loader) }
        } finally {
            parcel.recycle()
        }
    }

    // 테스트 클래스를 찾을 수 없는 클래스로더 — "받는 앱에 없는 클래스"
    private val isolated = object : ClassLoader(null) {}

    @Test fun `H3 알 수 없는 Parcelable - API 33 미만은 Unsafe, 33 이상은 감지하지 않고 꺼내지도 않는다`() {
        val reader = DeepLinkIntentReader.create(context)
        val result = reader.readNewIntent(launch(Intent(Intent.ACTION_VIEW)), received(isolated))
        if (android.os.Build.VERSION.SDK_INT < 33) {
            assertEquals(IntentReadResult.Unsafe(UnsafeIntentReason.UNREADABLE_EXTRAS), result)
        } else {
            // 계약: 구조만 읽고 값은 역직렬화하지 않으므로 Link 다. 그 값은 아무도 꺼내지 않는다
            assertEquals("myapp://item/1", (result as IntentReadResult.Link).request.uri)
        }
    }

    @Test fun `H3 리더는 extras 값을 역직렬화하지 않는다(API 33+)`() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        Foreign.created = 0
        val reader = DeepLinkIntentReader.create(context)
        reader.readNewIntent(launch(Intent(Intent.ACTION_VIEW)), received(javaClass.classLoader!!))
        assertEquals(0, Foreign.created)
        // SafeExtras 로 다른 키를 읽어도 그 값은 그대로 둔다
        val intent = received(javaClass.classLoader!!)
        assertEquals(ExtraRead.Present("hello"), SafeExtras.string(intent, "s"))
        assertEquals(0, Foreign.created)
        // 그 키를 읽으면 역직렬화된다(KDoc 에 적은 대가) — 타입이 달라 WRONG_TYPE
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.WRONG_TYPE), SafeExtras.string(intent, "evil"))
        assertEquals(1, Foreign.created)
    }

    @Test fun `H3 클래스를 찾을 수 없는 값을 SafeExtras 로 읽으면 UNREADABLE`() {
        val intent = received(isolated)
        assertEquals(ExtraRead.Invalid(ExtraInvalidReason.UNREADABLE), SafeExtras.string(intent, "evil"))
        val other = received(isolated)
        val expected = if (android.os.Build.VERSION.SDK_INT < 33) ExtraRead.Invalid(ExtraInvalidReason.UNREADABLE) else ExtraRead.Present("hello")
        assertEquals(expected, SafeExtras.string(other, "s"))
    }
}
