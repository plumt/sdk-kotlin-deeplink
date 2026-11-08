package net.plumt.deeplink

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import net.plumt.deeplink.internal.SignatureChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 최소(23)·레거시 끝(27)·서명 API 시작(28)·중간(33)·최신 지원(35). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 27, 28, 33, 35])
class SignatureCheckerTest {

    private val pm: PackageManager get() = ApplicationProvider.getApplicationContext<android.app.Application>().packageManager
    private fun checker(sdk: Int = Build.VERSION.SDK_INT) = SignatureChecker(pm, sdk)

    @Test fun `단일 서명자`() {
        installPackage("partner.app", arrayOf(SIG_A))
        assertEquals(setOf(digest(SIG_A)), checker().signerDigests("partner.app"))
        assertTrue(checker().matches("partner.app", setOf(digest(SIG_A))))
        assertTrue(checker().matches("partner.app", setOf(digest(SIG_B), digest(SIG_A))))
        assertFalse(checker().matches("partner.app", setOf(digest(SIG_B))))
        assertFalse(checker().matches("partner.app", emptySet()))
    }

    @Test fun `여러 서명자는 모두 허용 목록에 있어야 한다`() {
        installPackage("multi.app", arrayOf(SIG_A, SIG_B))
        assertEquals(setOf(digest(SIG_A), digest(SIG_B)), checker().signerDigests("multi.app"))
        assertFalse(checker().matches("multi.app", setOf(digest(SIG_A))))
        assertTrue(checker().matches("multi.app", setOf(digest(SIG_A), digest(SIG_B))))
    }

    @Test fun `설치되지 않은 패키지`() {
        assertNull(checker().signerDigests("missing.app"))
        assertFalse(checker().matches("missing.app", setOf(digest(SIG_A))))
    }

    @Test fun `서명 정보가 없는 패키지`() {
        installPackage("empty.app", emptyArray())
        assertNull(checker().signerDigests("empty.app"))
        assertFalse(checker().matches("empty.app", setOf(digest(SIG_A))))
    }

    @Test fun `레거시 경로를 강제해도 같은 결과`() {
        installPackage("partner.app", arrayOf(SIG_A))
        assertEquals(setOf(digest(SIG_A)), checker(sdk = 27).signerDigests("partner.app"))
    }

    @Test fun `교체 이력의 옛 인증서는 받지 않는다`() {
        if (Build.VERSION.SDK_INT < 28) return
        installPackage("rotated.app", arrayOf(SIG_B), past = arrayOf(SIG_A, SIG_B))
        assertFalse(checker().matches("rotated.app", setOf(digest(SIG_A))))
        assertTrue(checker().matches("rotated.app", setOf(digest(SIG_B))))
    }
}
