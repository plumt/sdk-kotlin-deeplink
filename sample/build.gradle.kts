import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 발행하지 않는 확인용 앱. SDK 를 의존한 앱의 병합 매니페스트가 앱이 선언한 것만 담는지, R8 release 가 consumer-rules.pro 만으로 되는지 본다
android {
    namespace = "net.plumt.deeplink.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.plumt.deeplink.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        // 소비 앱처럼 R8 로 줄여 consumer-rules.pro 만으로 충분한지 본다(서명 없는 release 산출물)
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":deeplink"))
    implementation(libs.androidx.activity)
}

/**
 * 병합 매니페스트 확인. 앱이 선언하지 않은 권한, SDK 패키지의 컴포넌트, `<queries>` 가 있으면 실패한다.
 * androidx 가 넣는 동적 리시버 보호 권한은 이 SDK 와 무관하게 모든 androidx 앱에 들어가 허용한다.
 */
abstract class VerifyMergedManifest : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val expectedPermissions: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest.get().asFile)
        val android = "http://schemas.android.com/apk/res/android"
        fun names(tag: String): List<String> {
            val nodes = document.getElementsByTagName(tag)
            return (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }
        }
        val permissions = (names("uses-permission") + names("uses-permission-sdk-23")).toSet()
        val components = listOf("activity", "service", "receiver", "provider").flatMap { tag -> names(tag).map { "$tag $it" } }
        val problems = ArrayList<String>()
        val extra = permissions - expectedPermissions.get()
        if (extra.isNotEmpty()) problems += "앱이 선언하지 않은 권한이 병합됐다: $extra"
        if (document.getElementsByTagName("queries").length > 0) problems += "<queries> 가 병합됐다"
        val sdkComponents = components.filter { it.contains("net.plumt.deeplink.") && !it.contains("net.plumt.deeplink.sample.") }
        if (sdkComponents.isNotEmpty()) problems += "SDK 컴포넌트가 병합됐다: $sdkComponents"
        report.get().asFile.writeText(
            "permissions=" + permissions.sorted() + "\ncomponents=" + components.sorted() + "\nproblems=" + problems + "\n",
        )
        check(problems.isEmpty()) { problems.joinToString("\n") }
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyMergedManifest>("verify${name}MergedManifest") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            expectedPermissions.set(
                setOf(
                    // androidx.core 가 모든 앱에 넣는 서명 보호 권한 — 이 SDK 와 무관하다
                    "${variant.applicationId.get()}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                ),
            )
            report.set(layout.buildDirectory.file("reports/merged-manifest/${variant.name}.txt"))
        }
        tasks.named("check") { dependsOn(verify) }
    }
}
