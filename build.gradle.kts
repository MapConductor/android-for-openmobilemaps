plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("maven-publish")
    id("signing")
    id("com.gradleup.nmcp") version "1.5.0"
}

ktlint {
    android.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

// ドライバー実装点（@InternalMapConductorApi）を使うためのオプトイン。
// android-for-* は地図SDKドライバーなので、モジュール単位で許可する。
kotlin {
    compilerOptions {
        optIn.add("com.mapconductor.core.InternalMapConductorApi")
    }
}

android {
    namespace = "com.mapconductor.openmobilemaps"
    compileSdk = project.property("compileSdk").toString().toInt()

    defaultConfig {
        minSdk = project.property("minSdk").toString().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(project.property("javaVersion").toString())
        targetCompatibility = JavaVersion.toVersion(project.property("javaVersion").toString())
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

val libraryGroupId = project.findProperty("libraryGroupId") as String? ?: "com.mapconductor"
val libraryArtifactId = "for-openmobilemaps"
val libraryVersion = project.findProperty("libraryVersion") as String? ?: "1.0.0"

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.foundation)

    // Lifecycle（MapView が Lifecycle を要求する）
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.common.java8)

    // Open Mobile Maps SDK
    api(libs.openmobilemaps.mapscore)
    // ローカルタイルサーバの 404 を 204 へ書き換えるインターセプタで使う
    // （mapscore の transitive 依存と同じ版。DataLoader が okhttp3.Interceptor を受け取る）
    implementation("com.squareup.okhttp3:okhttp:5.1.0")

    // 集約ビルド（android-sdk）ではプロジェクト参照、単体ビルド（CI のリリース）では
    // Maven 座標で解決する。他プロバイダと同じ形。
    if (findProject(":android-sdk-compose") != null) {
        api(project(":android-sdk-compose"))
    } else {
        api("com.mapconductor:compose:$libraryVersion")
    }

    testImplementation(libs.junit)
}


// Set project version for NMCP plugin
version = libraryVersion
val libraryName = "MapConductor for Open Mobile Maps"
val libraryDescription = "Open Mobile Maps implementation for MapConductor unified mapping library"

// Gradle 9.6 で `by tasks.registering` は非推奨（スクリプトコンパイルエラーになる）。
// googlemaps 等の既存モジュールは古い形のままなので、再コンパイル時に同じ問題が出る。
val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = libraryGroupId
                artifactId = libraryArtifactId
                version = libraryVersion

                artifact(javadocJar.get())

                pom {
                    name.set(libraryName)
                    description.set(libraryDescription)
                    url.set(
                        project.findProperty("libraryUrl") as String?
                            ?: "https://github.com/MapConductor/android-for-openmobilemaps",
                    )

                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }

                    developers {
                        developer {
                            id.set(project.findProperty("developerId") as String? ?: "mapconductor")
                            name.set(project.findProperty("developerName") as String? ?: "MapConductor Team")
                            email.set(project.findProperty("developerEmail") as String? ?: "info@mkgeeklab.com")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/MapConductor/android-for-openmobilemaps.git")
                        developerConnection
                            .set("scm:git:ssh://github.com:MapConductor/android-for-openmobilemaps.git")
                        url.set(
                            project.findProperty("scmUrl") as String?
                                ?: "https://github.com/MapConductor/android-for-openmobilemaps.git",
                        )
                    }
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                setUrl("https://maven.pkg.github.com/MapConductor/android-for-openmobilemaps")
                credentials {
                    username =
                        project.findProperty("gpr.user") as String? ?: System.getenv("GPR_USER")
                            ?: System.getenv("GITHUB_ACTOR")
                    password =
                        project.findProperty("gpr.key") as String? ?: System.getenv("GPR_TOKEN")
                            ?: System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }

    signing {
        val signingKey = findProperty("signingKey") as String?
        val signingPassword = findProperty("signingPassword") as String?
        if (!signingKey.isNullOrEmpty() && !signingPassword.isNullOrEmpty()) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications["release"])
        }
    }

    if (project == rootProject) {
        // standalone build only — in multi-project (android-sdk), parent configures nmcp
        nmcp {
            publishAllPublicationsToCentralPortal {
                username.set(findProperty("ossrh_username") as String? ?: System.getenv("OSSRH_USERNAME") ?: "")
                password.set(findProperty("ossrh_password") as String? ?: System.getenv("OSSRH_PASSWORD") ?: "")
            }
        }
    }
}
