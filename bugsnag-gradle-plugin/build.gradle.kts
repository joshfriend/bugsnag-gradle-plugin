import org.gradle.api.attributes.plugin.GradlePluginApiVersion
import org.gradle.plugins.signing.Sign
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-gradle-plugin")
    id("maven-publish")
    id("signing")

    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.gradle.pluginPublish)

    alias(libs.plugins.license)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

val pomName = providers.gradleProperty("POM_NAME")
val pomDescription = providers.gradleProperty("POM_DESCRIPTION")
val pomUrl = providers.gradleProperty("POM_URL")
val pomLicenceName = providers.gradleProperty("POM_LICENCE_NAME")
val pomLicenceUrl = providers.gradleProperty("POM_LICENCE_URL")
val pomDeveloperId = providers.gradleProperty("POM_DEVELOPER_ID")
val pomDeveloperName = providers.gradleProperty("POM_DEVELOPER_NAME")
val pomScmConnection = providers.gradleProperty("POM_SCM_CONNECTION")
val pomScmDevConnection = providers.gradleProperty("POM_SCM_DEV_CONNECTION")
val pomScmUrl = providers.gradleProperty("POM_SCM_URL")

version = providers.gradleProperty("VERSION_NAME").get()
group = providers.gradleProperty("GROUP").get()

val signingInMemoryKey = providers.gradleProperty("signingInMemoryKey")
val signingInMemoryKeyPassword = providers.gradleProperty("signingInMemoryKeyPassword")

dependencies {
    compileOnly(libs.android.plugin)

    implementation(libs.kotlin.stdlib)

    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.mockito)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.9.1")
}

tasks.test {
    useJUnitPlatform()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val bugsnagCliDir = layout.settingsDirectory.dir("bugsnag-cli")

/**
 * makeCli builds all of the `bugsnag-cli` binaries allowing them to be directly included in the Gradle plugin
 */
val makeCli = tasks.register<Exec>("makeCli") {
    workingDir(bugsnagCliDir)
    commandLine("make")
    args("build-all")
}

tasks.processResources {
    dependsOn(makeCli)
    from(bugsnagCliDir.dir("bin"))
}

gradlePlugin {
    website.set(pomUrl)
    vcsUrl.set(pomScmUrl)

    plugins {
        create("bugsnagPlugin") {
            id = "com.bugsnag.gradle"
            displayName = pomName.get()
            description = pomDescription.get()
            implementationClass = "com.bugsnag.gradle.GradlePlugin"
            tags.set(listOf("bugsnag", "proguard", "android", "upload"))
        }
    }
}

listOf("runtimeElements", "apiElements").forEach { configurationName ->
    configurations.named(configurationName).configure {
        attributes {
            attribute(
                GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE,
                objects.named(GradlePluginApiVersion::class.java, libs.versions.minGradle.get())
            )
        }
    }
}

// license checking
license {
    header = layout.settingsDirectory.file("LICENSE").asFile
    ignoreFailures = true
}

java {
    withJavadocJar()
    withSourcesJar()
}

publishing {
    repositories {
        maven {
            name = "ossrhStaging"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials {
                username = providers.gradleProperty("NEXUS_USERNAME")
                    .orElse(providers.environmentVariable("NEXUS_USERNAME"))
                    .orNull
                password = providers.gradleProperty("NEXUS_PASSWORD")
                    .orElse(providers.environmentVariable("NEXUS_PASSWORD"))
                    .orNull
            }
        }
    }
}

publishing.publications {
    withType<MavenPublication>().configureEach {
        pom {
            name.set(pomName)
            description.set(pomDescription)
            url.set(pomUrl)

            licenses {
                license {
                    name.set(pomLicenceName)
                    url.set(pomLicenceUrl)
                }
            }

            developers {
                developer {
                    id.set(pomDeveloperId)
                    name.set(pomDeveloperName)
                }
            }

            scm {
                connection.set(pomScmConnection)
                developerConnection.set(pomScmDevConnection)
                url.set(pomScmUrl)
            }
        }
    }
}

signing {
    useInMemoryPgpKeys(signingInMemoryKey.orNull, signingInMemoryKeyPassword.orNull)
    isRequired = signingInMemoryKey.isPresent
    if (signingInMemoryKey.isPresent) {
        sign(publishing.publications)
    }
}

// Workaround for https://github.com/gradle/gradle/issues/15568
tasks.withType<AbstractPublishToMaven>().configureEach {
    mustRunAfter(tasks.withType<Sign>())
}
