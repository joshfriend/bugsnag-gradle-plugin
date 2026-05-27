package com.bugsnag.gradle.android

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.CanMinifyCode
import com.android.build.api.variant.Variant
import com.bugsnag.gradle.capitalise
import com.bugsnag.gradle.toTaskName
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.Provider

internal data class AndroidVariant(
    val name: String,
    val manifestFile: Provider<RegularFile>,
    val bundleFile: Provider<RegularFile>,
    val nativeSymbols: Provider<List<Directory>>?,
    /**
     * The provider pointing to the obfuscation mapping file (typically `mapping.txt`) or `null` if minification is
     * not enabled for this variant.
     */
    val obfuscationMappingFile: Provider<RegularFile>?,
    val versionName: Provider<String>?,
    val versionCode: Provider<Int>?,
    val applicationId: Provider<String>?,
    val dexClassesDir: Provider<Directory>?
) {
    val bundleTaskName: String
        get() = name.toTaskName(prefix = "bundle")
}

internal fun Project.isMinifyEnabledFor(variant: Variant): Boolean {
    return variant is CanMinifyCode && variant.isMinifyEnabled || hasDexguardPlugin()
}

internal fun Project.onAndroidVariant(consumer: (variant: AndroidVariant) -> Unit) {
    try {
        project.plugins.withType(BasePlugin::class.java) {
            collectVariants(consumer)
        }
    } catch (ex: NoClassDefFoundError) {
        // ignore these - AGP is not available in this Project
    }
}

private fun Project.collectVariants(consumer: (variant: AndroidVariant) -> Unit) {
    try {
        val androidExtension = extensions.findByType(AndroidComponentsExtension::class.java)

        androidExtension?.onVariants { variant: Variant ->
            when (variant) {
                is ApplicationVariant -> variant.outputs.onEach { output ->
                    if (output.enabled.get()) {
                        consumer(
                            AndroidVariant(
                                variant.name,
                                variant.artifacts.get(SingleArtifact.MERGED_MANIFEST),
                                variant.artifacts.get(SingleArtifact.BUNDLE),
                                getNativeSymbolDirs(variant),
                                variant.artifacts
                                    .get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
                                    .takeIf { isMinifyEnabledFor(variant) },
                                output.versionName,
                                output.versionCode,
                                variant.applicationId,
                                getDexFiles(variant)
                            )
                        )
                    }
                }

                else -> consumer(
                    AndroidVariant(
                        variant.name,
                        variant.artifacts.get(SingleArtifact.MERGED_MANIFEST),
                        variant.artifacts.get(SingleArtifact.BUNDLE),
                        getNativeSymbolDirs(variant),
                        variant.artifacts
                            .get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
                            .takeIf { isMinifyEnabledFor(variant) },
                        null,
                        null,
                        null,
                        getDexFiles(variant)
                    )
                )
            }
        }
    } catch (ex: NoClassDefFoundError) {
        // ignore these - AGP is not available in this Project
    }
}

private fun Project.getNativeSymbolDirs(variant: Variant): Provider<List<Directory>>? {
    if (variant.externalNativeBuild == null) {
        return null
    }

    return project.layout.buildDirectory.map {
        listOf(it.dir("intermediates/merged_native_libs/${variant.name}/out/lib"))
    }
}

private fun Project.getDexFiles(variant: Variant): Provider<Directory> {
    return if (isMinifyEnabledFor(variant)) {
        layout.buildDirectory.dir("intermediates/dex/${variant.name}/minify${variant.name.capitalise()}WithR8/")
    } else {
        layout.buildDirectory.dir("intermediates/dex/${variant.name}/mergeExtDex${variant.name.capitalise()}/")
    }
}
