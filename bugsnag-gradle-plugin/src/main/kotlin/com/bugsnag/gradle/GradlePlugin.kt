package com.bugsnag.gradle

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.tasks.ExternalNativeBuildTask
import com.bugsnag.gradle.android.AndroidVariant
import com.bugsnag.gradle.android.CreateBuildTask
import com.bugsnag.gradle.android.ExtractBugsnagJniLibsTask
import com.bugsnag.gradle.android.HasAndroidOptions
import com.bugsnag.gradle.android.UploadBundleTask
import com.bugsnag.gradle.android.UploadMappingTask
import com.bugsnag.gradle.android.UploadNativeSymbolsTask
import com.bugsnag.gradle.android.configureFrom
import com.bugsnag.gradle.android.from
import com.bugsnag.gradle.android.onAndroidVariant
import com.bugsnag.gradle.dsl.BugsnagExtension
import com.bugsnag.gradle.dsl.VariantConfiguration
import com.bugsnag.gradle.dsl.debug
import com.bugsnag.gradle.util.wireFinalizer
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.process.ExecOperations
import javax.inject.Inject

internal const val TASK_GROUP = "BugSnag"
internal const val UPLOAD_TASK_PREFIX = "bugsnagUpload"
internal const val CREATE_BUILD_TASK_PREFIX = "bugsnagCreate"
internal const val CLEAN_TASK = "Clean"

class GradlePlugin @Inject constructor(
    private val execOperations: ExecOperations
) : Plugin<Project> {
    override fun apply(target: Project) {
        val bugsnag = target.extensions.create("bugsnag", BugsnagExtension::class.java)
        // turn-off the 'debug' variant by default
        bugsnag.variants.debug.enabled = false

        configurePlugin(bugsnag, target)
    }

    private fun configurePlugin(bugsnag: BugsnagExtension, target: Project) {
        target.afterEvaluate {
            if (bugsnag.enabled && bugsnag.enableLegacyNativeExtraction) {
                registerNdkLibInstallTask(target)
            }
        }

        target.onAndroidVariant { variant: AndroidVariant ->
            val variantConfiguration = VariantConfiguration(bugsnag, bugsnag.variants.findByName(variant.name))

            if (!variantConfiguration.enabled) {
                return@onAndroidVariant
            }

            val uploadBundleTask = target.tasks.register(
                variant.name.toTaskName(prefix = UPLOAD_TASK_PREFIX, suffix = "Bundle"),
                UploadBundleTask::class.java,
                configureUploadBundleTask(target, variantConfiguration, variant)
            )

            if (variantConfiguration.autoUploadBundle) {
                target.wireFinalizer(uploadBundleTask, variant.bundleTaskName)
            }

            val createBuildTask = target.tasks.register(
                variant.name.toTaskName(prefix = CREATE_BUILD_TASK_PREFIX, suffix = "Build"),
                CreateBuildTask::class.java,
                configureCreateBuildTask(target, variantConfiguration, variant)
            )

            if (variantConfiguration.autoCreateBuild) {
                target.wireFinalizer(createBuildTask, variant.bundleTaskName)
            }

            if (variant.obfuscationMappingFile != null) {
                target.tasks.register(
                    variant.name.toTaskName(prefix = UPLOAD_TASK_PREFIX, suffix = "ProguardMapping"),
                    UploadMappingTask::class.java
                ) { task ->
                    configureAndroidTask(task, variantConfiguration, variant)
                    task.mappingFile.set(variant.obfuscationMappingFile)
                    task.androidVariantMetadata.configureFrom(variantConfiguration, variant)
                    variant.dexClassesDir?.let { task.dexClassesDir.set(it) }
                    variantConfiguration.buildUuid?.let { task.buildUuid.set(it) }
                }
            }

            if (variant.nativeSymbols != null) {
                target.tasks.register(
                    variant.name.toTaskName(prefix = UPLOAD_TASK_PREFIX, suffix = "NativeSymbols"),
                    UploadNativeSymbolsTask::class.java,
                    configureUploadNativeSymbolsTask(variantConfiguration, variant, target)
                )
            }
        }
    }

    private fun registerNdkLibInstallTask(project: Project) {
        val ndkTasks = project.tasks.withType(ExternalNativeBuildTask::class.java)
        val cleanTasks = ndkTasks.filter { it.name.contains(CLEAN_TASK) }.toSet()
        val buildTasks = ndkTasks.filter { !it.name.contains(CLEAN_TASK) }.toSet()

        if (buildTasks.isNotEmpty()) {
            val ndkSetupTask = project.tasks.register(
                "bugsnagInstallJniLibsTask",
                ExtractBugsnagJniLibsTask::class.java
            ) { task ->
                task.group = TASK_GROUP
                task.bugsnagArtifacts.from(ExtractBugsnagJniLibsTask.resolveBugsnagArtifacts(project))
            }

            ndkSetupTask.configure { it.mustRunAfter(cleanTasks) }
            buildTasks.forEach { it.dependsOn(ndkSetupTask) }
        }
    }

    private fun configureUploadNativeSymbolsTask(
        variantConfiguration: VariantConfiguration,
        variant: AndroidVariant,
        target: Project
    ) = Action<UploadNativeSymbolsTask> { task ->
        configureAndroidTask(task, variantConfiguration, variant)
        task.symbolFiles.from(variant.nativeSymbols)

        val projectRoot = variantConfiguration.projectRoot ?: target.rootDir.toString()
        val ndkRoot =
            variantConfiguration.ndkRoot ?: target.extensions.getByType(BaseExtension::class.java).ndkDirectory
        task.projectRoot.set(projectRoot)
        task.ndkRoot.set(ndkRoot)
        task.androidVariantMetadata.configureFrom(variantConfiguration, variant)

        task.dependsOn(variant.name.toTaskName(prefix = "extract", suffix = "NativeSymbolTables"))
    }

    private fun configureCreateBuildTask(target: Project, bugsnag: VariantConfiguration, variant: AndroidVariant) =
        Action<CreateBuildTask> { task ->
            task.group = TASK_GROUP
            task.globalOptions.configureFrom(bugsnag, execOperations)
            task.systemMetadata.configureFrom(target, bugsnag)
            task.metadata.set(bugsnag.metadata)
            task.variantMetadata.configureFrom(bugsnag, variant)
            task.androidManifestFile.set(variant.manifestFile)
            task.projectDirectory.set(target.layout.projectDirectory)
        }

    private fun configureUploadBundleTask(target: Project, bugsnag: VariantConfiguration, variant: AndroidVariant) =
        Action<UploadBundleTask> { task ->
            configureBugsnagCliTask(task, bugsnag)
            task.bundleFile.set(variant.bundleFile)

            val projectRoot = bugsnag.projectRoot ?: target.rootDir.toString()
            task.projectRoot.set(projectRoot)

            // make sure that the bundle is actually built first
            task.dependsOn(variant.bundleTaskName)
        }

    private fun configureAndroidTask(task: BugsnagCliTask, bugsnag: VariantConfiguration, variant: AndroidVariant) {
        configureBugsnagCliTask(task, bugsnag)

        if (task is HasAndroidOptions) {
            task.androidOptions.from(variant)
        }
    }

    private fun configureBugsnagCliTask(task: BugsnagCliTask, bugsnag: VariantConfiguration) {
        task.group = TASK_GROUP
        task.globalOptions.configureFrom(bugsnag, execOperations)

        if (task is AbstractUploadTask) {
            task.uploadOptions.configureFrom(bugsnag)
        }
    }
}
