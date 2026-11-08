package net.plumt.deeplink

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * 항목을 더할 때 고치는 곳은 정의 한 곳이다. 그 정의에서 파생되는 문서 표(ERRORS.md·README 의 내장 파라미터)가
 * 어긋나면 여기서 실패한다.
 */
class DocsConsistencyTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val errors = File(root, "ERRORS.md").readText()
    private val readme = File(root, "README.md").readText()

    @Test fun `ERRORS 는 모든 거부 사유를 적는다`() {
        val names = Rejection::class.java.declaredClasses
            .filter { Rejection::class.java.isAssignableFrom(it) && !it.isInterface }
            .map { it.simpleName }
        assertTrue(names.size >= 12)
        val missing = names.filterNot { errors.contains("`$it`") || errors.contains("`$it(") }
        assertTrue("ERRORS.md 에 없는 거부 사유: $missing", missing.isEmpty())
    }

    @Test fun `ERRORS 는 모든 형식 오류와 상한을 적는다`() {
        val missing = MalformedReason.values.map { "Malformed(${it.name})" }.filterNot { errors.contains("`$it`") } +
            LimitKind.values.map { "TooLong(${it.name})" }.filterNot { errors.contains("`$it`") }
        assertTrue(MalformedReason.values.size >= 14 && LimitKind.values.size >= 5)
        assertTrue("ERRORS.md 에 없는 사유: $missing", missing.isEmpty())
    }

    @Test fun `README 는 모든 내장 파라미터를 적는다`() {
        val builtIns = Param.Companion::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.returnType == Param::class.java }
            .map { it.name }
            .toSet()
        assertTrue(builtIns.containsAll(setOf("string", "int", "long", "boolean", "oneOf", "uuid", "httpsUrl")))
        val missing = builtIns.filterNot { readme.contains("`Param.$it(") }
        assertTrue("README 내장 파라미터 표에 없는 검증기: $missing", missing.isEmpty())
    }

    @Test fun `README 설치 줄과 빌드 버전이 같다`() {
        val version = File(root, "deeplink/gradle.properties").readLines()
            .first { it.startsWith("VERSION_NAME=") }.substringAfter('=')
        assertTrue(readme.contains("net.plumt.deeplink:deeplink:$version"))
    }
}
