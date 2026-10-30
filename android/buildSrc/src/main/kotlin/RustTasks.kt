import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

// Builds of the Rust core for the app; registered in app/build.gradle.kts.

abstract class CargoNdkTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles abstract val sources: ConfigurableFileCollection
    @get:Input abstract val ndkHome: Property<String>
    @get:Internal abstract val workspace: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty
    @get:Input abstract val targets: ListProperty<String>

    @TaskAction
    fun run() {
        exec.exec {
            workingDir = workspace.get().asFile
            environment("ANDROID_NDK_HOME", ndkHome.get())
            commandLine(
                listOf("cargo", "ndk") + targets.get().flatMap { listOf("-t", it) } + listOf(
                    "-P", "29",
                    "-o", outDir.get().asFile.absolutePath,
                    "build", "--release", "-p", "jarvis-ffi",
                ),
            )
        }
        // cargo-ndk copies every .so in the target dir, including cdylibs some dependencies build.
        outDir.get().asFile.walk().filter { it.isFile && it.name != "libjarvis_ffi.so" }.forEach { it.delete() }
    }
}

abstract class UniffiBindgenTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val workspace: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction
    fun run() {
        val root = workspace.get().asFile
        val out = outDir.get().asFile
        out.deleteRecursively()
        exec.exec {
            workingDir = root
            commandLine("cargo", "build", "-q", "-p", "jarvis-ffi")
        }
        exec.exec {
            workingDir = root
            commandLine(
                "cargo", "run", "-q", "-p", "jarvis-ffi", "--features", "bindgen",
                "--bin", "uniffi-bindgen", "--", "generate",
                "--library", "target/debug/libjarvis_ffi.so",
                "--language", "kotlin", "--no-format", "--out-dir", out.absolutePath,
            )
        }
    }
}
