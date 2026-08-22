package com.masaibar.datastore.inspector.gradle

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

private const val PERFORMANCE_REPORT_PROPERTY =
  "datastore.inspector.instrumentation.performance.report"
private const val TASK_TIMING_OUTPUT_ENV =
  "DATASTORE_INSPECTOR_INSTRUMENTATION_TASK_TIMING_OUTPUT"
private const val FIXED_CORPUS_CLASS_COUNT = 256
private const val FIXED_CORPUS_CLASS_PREFIX =
  "dev.example.external.PerformanceCorpus"

class ExternalAarInstrumentationIntegrationSpec : DescribeSpec({
  describe("application ALL scope integration") {
    context("既知DataStore call-siteを含むexternal AARへ依存するとき") {
      lateinit var fixture: ExternalAarFixture
      val performanceReport =
        System.getProperty(PERFORMANCE_REPORT_PROPERTY)
          ?.let(Path::of)
          ?: Path.of(
            "build/reports/datastore-inspector/" +
              "instrumentation-performance.txt"
          )

      beforeEach {
        fixture = ExternalAarFixture.create()
      }

      afterEach {
        fixture.close()
      }

      it("external classをbridge化して発見metadataをdebug APKへ格納する") {
        val result =
          fixture.runner
            .withArguments(
              "assembleDebug",
              "generateDataStoreInspectorInstrumentationReport",
              "--stacktrace",
              "--console=plain",
              "--no-configuration-cache",
              "--no-build-cache",
              "--init-script",
              fixture.timingInitScript.toString()
            )
            .build()

        result.task(":assembleDebug")?.outcome shouldBe TaskOutcome.SUCCESS
        val performance = fixture.writePerformanceReport(performanceReport)
        performance.fixedCorpusVisitorCount shouldBe FIXED_CORPUS_CLASS_COUNT
        performance.actualVisitorClassCount shouldBeLessThanOrEqual
          InstrumentationBudget.TARGET_CLASS_COUNT
        performance.instrumentationTaskTimings.size shouldBe 1
        performance.instrumentationTaskWallElapsedMillis shouldBeLessThanOrEqual
          InstrumentationBudget.TARGET_ELAPSED_MILLIS
        fixture.instrumentationReport.readText() shouldContain
          "variant debug: ALL計装"
        val dexBytes = fixture.debugDexBytes()
        dexBytes.containsAscii(
          "com/masaibar/datastore/inspector/runtime/core/" +
            "TypedDataStoreDelegateBridge"
        ) shouldBe true
        dexBytes.containsAscii("dev.example.external.ExternalStores") shouldBe true
        dexBytes.containsAscii(
          "dev.example.external.ExternalStores#declare#0#" +
            "typed-delegate-explicit-v1"
        ) shouldBe true
      }
    }
  }
})

private class ExternalAarFixture private constructor(
  val root: Path,
  val runner: GradleRunner
) : AutoCloseable {
  val instrumentationReport: Path =
    root.resolve("build/reports/datastore-inspector/instrumentation.txt")
  val timingInitScript: Path =
    root.resolve("instrumentation-timing.init.gradle.kts")
  private val debugApk: Path =
    root.resolve("build/outputs/apk/debug/external-aar-consumer-debug.apk")
  private val visitorMetricsDirectory: Path =
    root.resolve("instrumentation-metrics/visitors")
  private val taskTimingOutput: Path =
    root.resolve("instrumentation-metrics/task-timing.metric")

  fun debugDexBytes(): ByteArray =
    ZipFile(debugApk.toFile()).use { apk ->
      apk.entries().asSequence()
        .filter { entry ->
          entry.name.startsWith("classes") && entry.name.endsWith(".dex")
        }
        .flatMap { entry ->
          apk.getInputStream(entry).use { input ->
            input.readBytes().asSequence()
          }
        }
        .toList()
        .toByteArray()
    }

  fun writePerformanceReport(output: Path): InstrumentationPerformanceSummary {
    val visitorMetrics =
      if (Files.isDirectory(visitorMetricsDirectory)) {
        Files.list(visitorMetricsDirectory).use { records ->
          records
            .filter(Files::isRegularFile)
            .map { record ->
              val parts = record.readText().trim().split('\t')
              check(parts.size == 2) { "visitor metricの形式が不正です。" }
              VisitorMetric(
                elapsedNanos = parts[0].toLong(),
                fixedCorpusClass = parts[1].toBooleanStrict()
              )
            }
            .toList()
        }
      } else {
        emptyList()
      }
    val taskTimings =
      if (Files.isRegularFile(taskTimingOutput)) {
        taskTimingOutput.readText()
          .lineSequence()
          .filter(String::isNotBlank)
          .map { line ->
            val parts = line.split('\t')
            check(parts.size == 2) { "task timing metricの形式が不正です。" }
            InstrumentationTaskTiming(
              taskPath = parts[0],
              elapsedNanos = parts[1].toLong()
            )
          }
          .toList()
      } else {
        emptyList()
      }
    return InstrumentationPerformanceSummary(
      actualVisitorClassCount = visitorMetrics.size,
      fixedCorpusVisitorCount = visitorMetrics.count(VisitorMetric::fixedCorpusClass),
      aggregateVisitorElapsedNanos = visitorMetrics.sumOf(VisitorMetric::elapsedNanos),
      instrumentationTaskTimings = taskTimings
    ).also { summary ->
      summary.writeTo(output)
    }
  }

  override fun close() {
    root.toFile().deleteRecursively()
  }

  companion object {
    fun create(): ExternalAarFixture {
      val root = Files.createTempDirectory("external-aar-instrumentation")
      root.resolve("settings.gradle.kts").writeText(
        """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        maven { url = uri("${root.resolve("maven").toUri()}") }
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = "external-aar-consumer"
        """.trimIndent()
      )
      root.resolve("build.gradle.kts").writeText(
        """
                plugins {
                    id("com.android.application")
                    id("com.masaibar.datastore-inspector")
                }

                android {
                    namespace = "dev.example.consumer"
                    compileSdk = 37
                    defaultConfig {
                        applicationId = "dev.example.consumer"
                        minSdk = 23
                        targetSdk = 36
                        versionCode = 1
                        versionName = "1"
                    }
                }

                dependencies {
                    implementation(files("libs/external-store.aar"))
                    implementation("androidx.datastore:datastore:1.2.1")
                }
        """.trimIndent()
      )
      root.resolve("src/main/AndroidManifest.xml").apply {
        parent.createDirectories()
        writeText("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />")
      }
      root.resolve("instrumentation-timing.init.gradle.kts").writeText(
        """
                import java.nio.file.Files
                import java.nio.file.Path
                import java.nio.file.StandardOpenOption
                import java.util.concurrent.ConcurrentHashMap
                import org.gradle.api.Task
                import org.gradle.api.execution.TaskExecutionListener
                import org.gradle.api.tasks.TaskState

                val timingOutput =
                    Path.of(
                        checkNotNull(System.getenv("$TASK_TIMING_OUTPUT_ENV")) {
                            "instrumentation task timing outputが未設定です。"
                        },
                    )
                val instrumentationTaskStarts = ConcurrentHashMap<String, Long>()

                @Suppress("DEPRECATION")
                gradle.addListener(
                    object : TaskExecutionListener {
                        override fun beforeExecute(task: Task) {
                            if (task.name.contains("ClassesWithAsm", ignoreCase = true)) {
                                instrumentationTaskStarts[task.path] = System.nanoTime()
                            }
                        }

                        override fun afterExecute(task: Task, state: TaskState) {
                            val startedAt =
                                instrumentationTaskStarts.remove(task.path) ?: return
                            val elapsedNanos = System.nanoTime() - startedAt
                            Files.createDirectories(timingOutput.parent)
                            Files.writeString(
                                timingOutput,
                                task.path + "\t" + elapsedNanos + "\n",
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND,
                            )
                        }
                    },
                )
        """.trimIndent()
      )
      root.resolve("libs/external-store.aar").apply {
        parent.createDirectories()
        writeBytes(externalAar())
      }
      val coordinates =
        Properties().apply {
          Path.of("../gradle/artifact-coordinates.properties")
            .toFile()
            .inputStream()
            .use(::load)
        }
      listOf("runtime-core", "runtime-shared-preferences").forEach { artifact ->
        writeStubArtifact(
          repository = root.resolve("maven"),
          group = coordinates.getProperty("group"),
          artifact = artifact,
          version = coordinates.getProperty("version")
        )
      }
      val runner =
        GradleRunner.create()
          .withProjectDir(root.toFile())
          .withTestKitDir(root.resolve("test-kit").toFile())
          .withPluginClasspath(pluginClasspathWithAgp())
          .withEnvironment(
            System.getenv() +
              mapOf(
                InstrumentationPerformanceMetricRecorder
                  .OUTPUT_DIRECTORY_ENV to
                  root.resolve("instrumentation-metrics/visitors").toString(),
                InstrumentationPerformanceMetricRecorder
                  .CORPUS_CLASS_PREFIX_ENV to
                  FIXED_CORPUS_CLASS_PREFIX,
                TASK_TIMING_OUTPUT_ENV to
                  root.resolve(
                    "instrumentation-metrics/task-timing.metric"
                  ).toString()
              )
          )
          .forwardOutput()
      return ExternalAarFixture(root, runner)
    }

    private fun externalAar(): ByteArray =
      zip(
        mapOf(
          "AndroidManifest.xml" to
            (
              "<manifest " +
                "xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                "package=\"dev.example.external\" />"
            ).encodeToByteArray(),
          "classes.jar" to
            zip(
              buildMap {
                put(
                  "dev/example/external/ExternalStores.class",
                  externalStoreClass()
                )
                repeat(FIXED_CORPUS_CLASS_COUNT) { index ->
                  val className =
                    "dev/example/external/PerformanceCorpus" +
                      index.toString().padStart(3, '0')
                  put("$className.class", performanceCorpusClass(className, index))
                }
              }
            ),
          "R.txt" to byteArrayOf()
        )
      )

    private fun externalStoreClass(): ByteArray {
      val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
      writer.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
        "dev/example/external/ExternalStores",
        null,
        "java/lang/Object",
        null
      )
      val method =
        writer.visitMethod(
          Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
          "declare",
          "()V",
          null,
          null
        )
      method.visitCode()
      repeat(5) { method.visitInsn(Opcodes.ACONST_NULL) }
      method.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "androidx/datastore/DataStoreDelegateKt",
        "dataStore",
        "(Ljava/lang/String;Landroidx/datastore/core/Serializer;" +
          "Landroidx/datastore/core/handlers/ReplaceFileCorruptionHandler;" +
          "Lkotlin/jvm/functions/Function1;Lkotlinx/coroutines/CoroutineScope;)" +
          "Lkotlin/properties/ReadOnlyProperty;",
        false
      )
      method.visitInsn(Opcodes.POP)
      method.visitInsn(Opcodes.RETURN)
      method.visitMaxs(0, 0)
      method.visitEnd()
      writer.visitEnd()
      return writer.toByteArray()
    }

    private fun performanceCorpusClass(className: String, index: Int): ByteArray {
      val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
      writer.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
        className,
        null,
        "java/lang/Object",
        null
      )
      val method =
        writer.visitMethod(
          Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
          "corpusIndex",
          "()I",
          null,
          null
        )
      method.visitCode()
      method.visitLdcInsn(index)
      method.visitInsn(Opcodes.IRETURN)
      method.visitMaxs(0, 0)
      method.visitEnd()
      writer.visitEnd()
      return writer.toByteArray()
    }

    private fun writeStubArtifact(
      repository: Path,
      group: String,
      artifact: String,
      version: String
    ) {
      val directory =
        repository
          .resolve(group.replace('.', '/'))
          .resolve(artifact)
          .resolve(version)
      directory.createDirectories()
      directory.resolve("$artifact-$version.jar").writeBytes(zip(emptyMap()))
      directory.resolve("$artifact-$version.pom").writeText(
        """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>$group</groupId>
                  <artifactId>$artifact</artifactId>
                  <version>$version</version>
                </project>
        """.trimIndent()
      )
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray =
      ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { archive ->
          entries.forEach { (name, content) ->
            archive.putNextEntry(ZipEntry(name))
            archive.write(content)
            archive.closeEntry()
          }
        }
        bytes.toByteArray()
      }

    private fun pluginClasspathWithAgp(): List<File> {
      val metadata =
        checkNotNull(
          ExternalAarFixture::class.java.classLoader
            .getResourceAsStream("plugin-under-test-metadata.properties")
        ) { "TestKit plugin metadataがありません。" }
      val properties = Properties().apply { metadata.use(::load) }
      val pluginClasspath =
        properties.getProperty("implementation-classpath")
          .split(File.pathSeparator)
          .filter(String::isNotBlank)
          .map(::File)
      val testRuntimeClasspath =
        System.getProperty("java.class.path")
          .split(File.pathSeparator)
          .filter(String::isNotBlank)
          .map(::File)
      check(
        testRuntimeClasspath.any { file ->
          file.name == "gradle-9.2.1.jar"
        }
      ) {
        "TestKit fixtureのruntime classpathにAGP本体がありません。"
      }
      return (pluginClasspath + testRuntimeClasspath).distinct()
    }
  }
}

private data class VisitorMetric(
  val elapsedNanos: Long,
  val fixedCorpusClass: Boolean
)

private data class InstrumentationTaskTiming(
  val taskPath: String,
  val elapsedNanos: Long
)

private data class InstrumentationPerformanceSummary(
  val actualVisitorClassCount: Int,
  val fixedCorpusVisitorCount: Int,
  val aggregateVisitorElapsedNanos: Long,
  val instrumentationTaskTimings: List<InstrumentationTaskTiming>
) {
  val instrumentationTaskWallElapsedMillis: Long =
    instrumentationTaskTimings.sumOf(InstrumentationTaskTiming::elapsedNanos) / 1_000_000

  fun writeTo(output: Path) {
    val passed =
      fixedCorpusVisitorCount == FIXED_CORPUS_CLASS_COUNT &&
        actualVisitorClassCount <= InstrumentationBudget.TARGET_CLASS_COUNT &&
        instrumentationTaskTimings.size == 1 &&
        instrumentationTaskWallElapsedMillis <=
        InstrumentationBudget.TARGET_ELAPSED_MILLIS
    output.parent.createDirectories()
    output.writeText(
      """
            format_version=1
            status=${if (passed) "PASS" else "FAIL"}
            metric_source=AGP TestKit InstrumentationScope.ALL
            fixed_corpus_expected_class_count=$FIXED_CORPUS_CLASS_COUNT
            fixed_corpus_actual_visitor_count=$fixedCorpusVisitorCount
            actual_visitor_class_count=$actualVisitorClassCount
            class_budget=${InstrumentationBudget.TARGET_CLASS_COUNT}
            instrumentation_task_record_count=${instrumentationTaskTimings.size}
            instrumentation_task=${instrumentationTaskTimings.singleOrNull()?.taskPath.orEmpty()}
            instrumentation_task_wall_elapsed_ms=$instrumentationTaskWallElapsedMillis
            instrumentation_task_wall_budget_ms=${InstrumentationBudget.TARGET_ELAPSED_MILLIS}
            aggregate_visitor_elapsed_ms=${aggregateVisitorElapsedNanos / 1_000_000}
      """.trimIndent() + "\n"
    )
  }
}

private fun ByteArray.containsAscii(value: String): Boolean {
  val expected = value.encodeToByteArray()
  if (size < expected.size) return false
  return (0..size - expected.size).any { offset ->
    expected.indices.all { index -> this[offset + index] == expected[index] }
  }
}
