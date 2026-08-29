package com.masaibar.datastore.inspector.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import java.io.File

internal object AndroidVariantIntegration {
  private val SERVICE_RESOURCES =
    setOf(
      "META-INF/services/com.masaibar.datastore.inspector.runtime.core.StoreAdapterFactory",
      "META-INF/services/com.masaibar.datastore.inspector.runtime.core.StoreCatalogProvider"
    )

  fun configureApplication(
    project: Project,
    extension: DataStoreInspectorExtension
  ) {
    val report = project.tasks.register(
      "generateDataStoreInspectorInstrumentationReport",
      GenerateInstrumentationReportTask::class.java
    ) { task ->
      task.group = "datastore inspector"
      task.description = "DataStore Inspectorのvariant・matcher診断を生成します。"
      task.lines.convention(emptyList())
      task.outputFile.set(
        project.layout.buildDirectory.file("reports/datastore-inspector/instrumentation.txt")
      )
    }
    val signals = DependencySignals()
    val observedProjects = linkedSetOf<String>()
    val debuggableVariants = linkedSetOf<String>()
    val debuggableProjectVariants = linkedMapOf<String, MutableSet<String>>()
    val schemaTasks = linkedMapOf<String, TaskProvider<GenerateDataStoreInspectorSchemaTask>>()

    project.extensions.configure(ApplicationExtension::class.java) { android ->
      SERVICE_RESOURCES.forEach(android.packaging.resources.merges::add)
    }
    observeProjectDependencies(
      rootApplication = project,
      candidate = project,
      signals = signals,
      observedProjects = observedProjects,
      debuggableProjectVariants = debuggableProjectVariants,
      report = report
    )

    val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
    components.onVariants(components.selector().all()) { variant ->
      if (!variant.debuggable) {
        report.configure { it.lines.add("variant ${variant.name}: non-debuggable、計装・依存注入なし") }
        return@onVariants
      }
      debuggableVariants += variant.name
      debuggableProjectVariants.getOrPut(project.path, ::linkedSetOf) += variant.name
      variant.instrumentation.transformClassesWith(
        DataStoreDelegateVisitorFactory::class.java,
        InstrumentationScope.ALL
      ) {}
      variant.instrumentation.setAsmFramesComputationMode(
        FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
      )
      report.configure {
        it.lines.add(
          "variant ${variant.name}: ALL計装、" +
            "${InvocationRoutes.routes.size} method matcher・" +
            "${ConstructorRoute.routes.size} constructor matcher有効"
        )
      }
      val capitalizedVariant = variant.name.replaceFirstChar(Char::uppercaseChar)
      val schemaTask =
        project.tasks.register(
          "generate${capitalizedVariant}DataStoreInspectorSchema",
          GenerateDataStoreInspectorSchemaTask::class.java
        ) { task ->
          task.group = "datastore inspector"
          task.description = "${variant.name}用のschema assetを生成します。"
          task.schemaMappings.set(extension.schemaMappings)
          task.outputDirectory.set(
            project.layout.buildDirectory.dir(
              "generated/datastoreInspectorSchema/${variant.name}"
            )
          )
        }
      schemaTasks[variant.name] = schemaTask
      variant.sources.assets?.addGeneratedSourceDirectory(
        schemaTask,
        GenerateDataStoreInspectorSchemaTask::outputDirectory
      )
      val codecTask =
        project.tasks.register(
          "generate${capitalizedVariant}DataStoreInspectorCustomCodecs",
          GenerateCustomCodecBindingsTask::class.java
        ) { task ->
          task.group = "datastore inspector"
          task.description =
            "${variant.name}用のdebug-only Custom codec providerを生成します。"
          task.bindings.set(extension.customCodecBindings)
          task.javaOutputDirectory.set(
            project.layout.buildDirectory.dir(
              "generated/datastoreInspectorCustomCodecs/${variant.name}/java"
            )
          )
          task.resourcesOutputDirectory.set(
            project.layout.buildDirectory.dir(
              "generated/datastoreInspectorCustomCodecs/${variant.name}/resources"
            )
          )
        }
      variant.sources.java?.addGeneratedSourceDirectory(
        codecTask,
        GenerateCustomCodecBindingsTask::javaOutputDirectory
      )
      variant.sources.resources?.addGeneratedSourceDirectory(
        codecTask,
        GenerateCustomCodecBindingsTask::resourcesOutputDirectory
      )
    }

    project.gradle.projectsEvaluated {
      scanDependencySignals(project, mutableSetOf(), signals)
      configureSchemaFragments(
        application = project,
        reachableProjectPaths = observedProjects,
        debuggableProjectVariants = debuggableProjectVariants,
        schemaTasks = schemaTasks,
        report = report
      )
      debuggableVariants.forEach { variantName ->
        val configuration = "${variantName}Implementation"
        InspectorDependencyPlanner.runtimeArtifacts(signals).forEach { artifact ->
          addOnce(project, configuration, artifact)
        }
      }
      report.configure { task ->
        task.lines.add("reachable first-party projects: ${observedProjects.sorted()}")
        task.lines.add("preferences adapter: ${signals.preferences}")
        task.lines.add("protobuf adapter: ${signals.protobuf}")
        task.lines.add("KMP createWithPath matcher: 無効（fallback手動登録）")
        task.lines.add(
          "instrumentation budget: target classes <= " +
            "${InstrumentationBudget.TARGET_CLASS_COUNT}, elapsed <= " +
            "${InstrumentationBudget.TARGET_ELAPSED_MILLIS} ms"
        )
      }
    }
  }

  fun configureLibrary(project: Project) {
    val report =
      project.tasks.register(
        "generateDataStoreInspectorInstrumentationReport",
        GenerateInstrumentationReportTask::class.java
      ) { task ->
        task.group = "datastore inspector"
        task.description = "DataStore Inspector library variant・matcher診断を生成します。"
        task.lines.convention(emptyList())
        task.outputFile.set(
          project.layout.buildDirectory.file(
            "reports/datastore-inspector/instrumentation.txt"
          )
        )
      }
    val components =
      project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
    components.onVariants(components.selector().all()) { variant ->
      if (!variant.debuggable) {
        report.configure {
          it.lines.add("variant ${variant.name}: non-debuggable、計装・依存注入なし")
        }
        return@onVariants
      }
      variant.instrumentation.transformClassesWith(
        DataStoreDelegateVisitorFactory::class.java,
        InstrumentationScope.PROJECT
      ) {}
      variant.instrumentation.setAsmFramesComputationMode(
        FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
      )
      report.configure {
        it.lines.add(
          "variant ${variant.name}: PROJECT計装、" +
            "${InvocationRoutes.routes.size} method matcher・" +
            "${ConstructorRoute.routes.size} constructor matcher有効"
        )
        it.lines.add(
          "instrumentation budget: target classes <= " +
            "${InstrumentationBudget.TARGET_CLASS_COUNT}, elapsed <= " +
            "${InstrumentationBudget.TARGET_ELAPSED_MILLIS} ms"
        )
      }
    }
  }

  private fun configureSchemaFragments(
    application: Project,
    reachableProjectPaths: Set<String>,
    debuggableProjectVariants: Map<String, Set<String>>,
    schemaTasks: Map<String, TaskProvider<GenerateDataStoreInspectorSchemaTask>>,
    report: TaskProvider<GenerateInstrumentationReportTask>
  ) {
    val protobufProjects =
      reachableProjectPaths
        .map(application.rootProject::project)
        .filter { it.pluginManager.hasPlugin("com.google.protobuf") }
        .filterNot(::isInspectorComponent)
    protobufProjects.forEach(::configureDescriptorGeneration)

    schemaTasks.forEach { (variantName, schemaTask) ->
      val attachedProjects = mutableListOf<String>()
      protobufProjects.forEach { protobufProject ->
        val availableVariants = debuggableProjectVariants[protobufProject.path].orEmpty()
        val sourceVariant = when {
          variantName in availableVariants -> variantName
          "debug" in availableVariants -> "debug"
          else -> return@forEach
        }
        val exactTaskName =
          "generate${sourceVariant.replaceFirstChar(Char::uppercaseChar)}Proto"
        val descriptor =
          protobufProject.layout.buildDirectory.file(
            "descriptors/$exactTaskName.pb"
          )
        schemaTask.configure { task ->
          task.dependsOn("${protobufProject.path}:$exactTaskName")
          task.descriptorFragments.from(descriptor)
          task.protoSources.from(
            protobufProject.tasks.named(exactTaskName).map { generateProtoTask ->
              generateProtoTask.javaClass.methods
                .first { it.name == "getSourceDirs" && it.parameterCount == 0 }
                .invoke(generateProtoTask)
            }
          )
        }
        attachedProjects += "${protobufProject.path}($sourceVariant)"
      }
      report.configure {
        it.lines.add(
          "schema $variantName: descriptor projects=${attachedProjects.sorted()}"
        )
      }
    }
  }

  private fun configureDescriptorGeneration(project: Project) {
    project.tasks.configureEach { task ->
      if (!task.name.startsWith("generate") || !task.name.endsWith("Proto")) {
        return@configureEach
      }
      val setGenerateDescriptorSet =
        task.javaClass.methods.firstOrNull {
          it.name == "setGenerateDescriptorSet" && it.parameterCount == 1
        } ?: return@configureEach
      setGenerateDescriptorSet.invoke(task, true)
      val options =
        task.javaClass.methods.first { it.name == "getDescriptorSetOptions" }.invoke(task)
      val descriptorPath =
        project.layout.buildDirectory.file("descriptors/${task.name}.pb").get().asFile.path
      options.javaClass.methods.first { it.name == "setPath" }.invoke(options, descriptorPath)
      options.javaClass.methods.first { it.name == "setIncludeImports" }.invoke(options, true)
      options.javaClass.methods.first { it.name == "setIncludeSourceInfo" }.invoke(options, true)
    }
  }

  private fun observeProjectDependencies(
    rootApplication: Project,
    candidate: Project,
    signals: DependencySignals,
    observedProjects: MutableSet<String>,
    debuggableProjectVariants: MutableMap<String, MutableSet<String>>,
    report: org.gradle.api.tasks.TaskProvider<GenerateInstrumentationReportTask>
  ) {
    if (!observedProjects.add(candidate.path) || isInspectorComponent(candidate)) return

    candidate.pluginManager.withPlugin("com.google.protobuf") {
      signals.protobuf = true
    }
    candidate.configurations.configureEach { configuration ->
      configuration.dependencies.whenObjectAdded { dependency ->
        when (dependency) {
          is ProjectDependency -> {
            val dependencyProject = rootApplication.rootProject.project(dependency.path)
            observeProjectDependencies(
              rootApplication,
              dependencyProject,
              signals,
              observedProjects,
              debuggableProjectVariants,
              report
            )
          }
        }
        observeExternalDependency(dependency.group, dependency.name, signals)
      }
    }

    if (candidate != rootApplication) {
      candidate.pluginManager.withPlugin("com.android.library") {
        if (isInspectorComponent(candidate)) {
          return@withPlugin
        }
        val components =
          candidate.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
        components.onVariants(components.selector().all()) { variant ->
          if (!variant.debuggable) return@onVariants
          debuggableProjectVariants.getOrPut(candidate.path, ::linkedSetOf) += variant.name
          report.configure {
            it.lines.add(
              "dependency ${candidate.path}:${variant.name}: " +
                "application ALL scopeで計装"
            )
          }
        }
      }
    }
  }

  private fun isInspectorComponent(project: Project): Boolean =
    project.group.toString() == ArtifactCoordinates.GROUP &&
      project.name in
      setOf(
        "protocol",
        "runtime-core",
        "runtime-preferences",
        "runtime-protobuf",
        "runtime-shared-preferences"
      )

  private fun scanDependencySignals(
    project: Project,
    visited: MutableSet<String>,
    signals: DependencySignals
  ) {
    if (!visited.add(project.path)) return
    if (project.pluginManager.hasPlugin("com.google.protobuf")) signals.protobuf = true
    project.configurations.forEach { configuration ->
      configuration.dependencies.forEach { dependency ->
        observeExternalDependency(dependency.group, dependency.name, signals)
        if (dependency is ProjectDependency) {
          val dependencyProject = project.rootProject.project(dependency.path)
          if (!isInspectorComponent(dependencyProject)) {
            scanDependencySignals(dependencyProject, visited, signals)
          }
        }
      }
    }
  }

  private fun observeExternalDependency(
    group: String?,
    name: String,
    signals: DependencySignals
  ) {
    InspectorDependencyPlanner.observe(group, name, signals)
  }

  private fun addOnce(project: Project, configuration: String, artifact: String) {
    val dependencies = project.configurations.getByName(configuration).dependencies
    val local = project.rootProject.findProject(":$artifact")
    val alreadyPresent = dependencies.any { dependency ->
      (local != null && dependency is ProjectDependency && dependency.path == local.path) ||
        (dependency.group == ArtifactCoordinates.GROUP && dependency.name == artifact)
    }
    if (alreadyPresent) return
    val notation: Any = local?.let {
      project.dependencies.project(mapOf("path" to it.path))
    } ?: "${ArtifactCoordinates.GROUP}:$artifact:${ArtifactCoordinates.VERSION}"
    project.dependencies.add(configuration, notation)
  }
}

internal data class DependencySignals(
  var preferences: Boolean = false,
  var protobuf: Boolean = false
)

internal object InspectorDependencyPlanner {
  fun observe(group: String?, name: String, signals: DependencySignals) {
    if (group == "androidx.datastore" && name.contains("preferences")) {
      signals.preferences = true
    }
    if (group == "com.google.protobuf") signals.protobuf = true
  }

  fun runtimeArtifacts(signals: DependencySignals): List<String> = buildList {
    add("runtime-core")
    add("runtime-shared-preferences")
    if (signals.preferences) add("runtime-preferences")
    if (signals.protobuf) add("runtime-protobuf")
  }
}

@CacheableTask
@InternalDataStoreInspectorGradleApi
public abstract class GenerateInstrumentationReportTask : DefaultTask() {
  @get:Input
  public abstract val lines: ListProperty<String>

  @get:OutputFile
  public abstract val outputFile: RegularFileProperty

  @TaskAction
  public fun generate() {
    val output: File = outputFile.get().asFile
    output.parentFile.mkdirs()
    output.writeText(lines.get().distinct().sorted().joinToString("\n", postfix = "\n"))
    logger.lifecycle("DataStore Inspector計装report生成成功: ${output.path}")
  }
}
