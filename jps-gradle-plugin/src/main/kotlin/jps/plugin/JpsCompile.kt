package jps.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property
import org.gradle.process.ExecOperations
import java.io.File
import java.nio.file.Files
import javax.inject.Inject

@Suppress("unused")
abstract class JpsCompile @Inject constructor(
    objectFactory: ObjectFactory,
    providerFactory: ProviderFactory,
    private val archiveOperations: ArchiveOperations,
    private val execOperations: ExecOperations,
    private val fs: FileSystemOperations,
    private val projectLayout: ProjectLayout,
) : DefaultTask() {
    companion object {
        const val PROPERTY_PREFIX = "build"
    }

    @Input
    val jpsVersion = objectFactory.property<String>()

    @Input
    val jpsWrapperVersion = objectFactory.property<String>()

    @Input
    @Optional
    val moduleName = objectFactory.property<String>()

    @Input
    @Optional
    val projectPath = objectFactory.property<String>()

    @OutputFile
    val classpathOutputFilePath = objectFactory.fileProperty()

    @Input
    val incremental = objectFactory.property<Boolean>()

    @Input
    val includeRuntimeDependencies = objectFactory.property<Boolean>()

    @Input
    val includeTests = objectFactory.property<Boolean>()

    @Input
    val parallel = objectFactory.property<Boolean>()

    @Input
    val withProgress = objectFactory.property<Boolean>()

    @Input
    val kotlinVersion = objectFactory.property<String>()

    @InputFile
    val kotlinDistZip = objectFactory.fileProperty()

    @InputFile
    val kotlinJpsPlugin = objectFactory.fileProperty()

    @InputFile
    val jpsWrapper = objectFactory.fileProperty()

    @InputFile
    val jpsStandaloneZip = objectFactory.fileProperty()

    @Optional
    @Input
    val systemProperties = objectFactory.mapProperty<String, String>()

    @Optional
    @Input
    val jvmArgs = objectFactory.listProperty<String>()

    @OutputDirectory
    val outputPath = objectFactory.directoryProperty()

    @Input
    @Optional
    val jdkTableContent = objectFactory.mapProperty<String, String>()

    init {
        outputs.upToDateWhen { false }
        jpsVersion.convention(DEFAULT_JPS_VERSION)
        jpsWrapperVersion.convention(DEFAULT_JPS_WRAPPER_VERSION)
        classpathOutputFilePath.convention(projectLayout.file(providerFactory.provider {
            Files.createTempFile(temporaryDir.toPath(), "classpath", "").toFile()
        }))
        incremental.convention(true)
        includeRuntimeDependencies.convention(true)
        includeTests.convention(true)
        parallel.convention(true)
        withProgress.convention(false)
        outputPath.convention(projectLayout.buildDirectory.dir("jps/out"))
    }

    @TaskAction
    fun compile() {
        val kotlinDirectory = extractZip(kotlinDistZip.get().asFile, destinationFolderName = "kotlin-dist")
        val jpsStandaloneDirectory = extractZip(jpsStandaloneZip.get().asFile, destinationFolderName = "jps-standalone")
        val jpsClasspath = jpsStandaloneDirectory.listFiles()?.toList() ?: emptyList()

        val jdkTable = temporaryDir.resolve("jdkTable.txt")
        jdkTable.writeText(jdkTableContent.get().entries.joinToString("\n") { (k, v) -> "$k=$v" })

        val extraProperties = systemProperties.orNull
        val extraJvmArgs = jvmArgs.orNull
        execOperations.javaexec {
            classpath = projectLayout.files(jpsWrapper.asFile, jpsClasspath, kotlinJpsPlugin.asFile)
            mainClass.set("jps.wrapper.MainKt")

            listOf(
                "moduleName" to moduleName.orNull,
                "projectPath" to projectPath.orNull,
                "classpathOutputFilePath" to classpathOutputFilePath.get().asFile.absolutePath,
                "includeTests" to includeTests.get().toString(),
                "includeRuntimeDependencies" to includeRuntimeDependencies.get().toString(),
                "incremental" to incremental.get().toString(),
                "parallel" to parallel.get().toString(),
                "withProgress" to withProgress.get().toString(),
                "jdkTable" to jdkTable.absolutePath,
                "outputPath" to outputPath.get().asFile.absolutePath,
            ).forEach { (name, value) ->
                systemProperty(name.withPrefix(), value)
            }

            systemProperties(extraProperties)
            systemProperty("kotlinHome".withPrefix(), kotlinDirectory)
            jvmArgs(extraJvmArgs)
        }
    }

    private fun extractZip(archive: File, destinationFolderName: String): File {
        val dir = temporaryDir.resolve(destinationFolderName)
        fs.sync {
            from(archiveOperations.zipTree(archive))
            into(dir)
        }
        return dir
    }

    private fun String.withPrefix() = "$PROPERTY_PREFIX.$this"
}
