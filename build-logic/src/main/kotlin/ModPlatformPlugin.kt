@file:Suppress("unused", "DuplicatedCode")

import dev.kikugie.fletching_table.extension.FletchingTableExtension
import dev.kikugie.stonecutter.StonecutterExperimentalAPI
import dev.kikugie.stonecutter.build.StonecutterBuildExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.extensions.stdlib.toDefaultLowerCase
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.plugins.ide.idea.model.IdeaModel
import javax.inject.Inject

val Project.sc: StonecutterBuildExtension
	get() = extensions.getByType<StonecutterBuildExtension>()

@OptIn(StonecutterExperimentalAPI::class)
fun Project.prop(name: String): String = (project.sc.properties.get<String>(name))

fun Project.env(variable: String): String? = providers.environmentVariable(variable).orNull

fun Project.envTrue(variable: String): Boolean = env(variable)?.toDefaultLowerCase() == "true"

fun RepositoryHandler.strictMaven(
	url: String, vararg groups: String, configure: MavenArtifactRepository.() -> Unit = {}
) = exclusiveContent {
	forRepository { maven(url) { configure() } }
	filter { groups.forEach(::includeGroup) }
}

abstract class GenerateModManifestTask : DefaultTask() {
	@get:Input
	abstract val content: Property<String>

	@get:OutputFile
	abstract val outputFile: RegularFileProperty

	@TaskAction
	fun generate() {
		val file = outputFile.get().asFile
		file.parentFile.mkdirs()
		file.writeText(content.get())
	}
}

abstract class ModPlatformPlugin @Inject constructor() : Plugin<Project> {
	override fun apply(project: Project) = with(project) {
		val inferredLoader = Loader.of(project.buildFile.name.substringAfter('.').replace(".gradle.kts", ""))

		val extension = extensions.create("platform", ModPlatformExtension::class.java).apply {
			loader.convention(inferredLoader.id)
		}

		when (inferredLoader) {
			is Loader.Fabric, is Loader.Forge -> {
				// arch-loom builds both; remapped output is remapJar/remapSourcesJar. On the
				// NO-REMAP variant (unobfuscated 26.x) those tasks don't exist → fall back to
				// jar/sourcesJar. Resolved lazily so the Loom tasks are created by query time.
				extension.jarTask.convention(providers.provider {
					if (tasks.findByName("remapJar") != null) "remapJar" else "jar"
				})
				extension.sourcesJarTask.convention(providers.provider {
					if (tasks.findByName("remapSourcesJar") != null) "remapSourcesJar" else "sourcesJar"
				})
			}
			else -> {
				extension.jarTask.convention("jar")
				extension.sourcesJarTask.convention("sourcesJar")
			}
		}

		listOf("org.jetbrains.kotlin.jvm", "com.google.devtools.ksp", "dev.kikugie.fletching-table").forEach {
			apply(
				plugin = it
			)
		}

		afterEvaluate {
			val ctx = Context(
				project = this,
				extension = extension,
				loader = Loader.of(extension.loader.get()),
				stonecutter = project.sc
			)
			configureProject(ctx)
		}
	}

	private fun Project.configureProject(ctx: Context) {
		listOf("java", "me.modmuss50.mod-publish-plugin", "idea").forEach { apply(plugin = it) }

		version = ctx.fullVersion
		ctx.extension.requiredJava.set(ctx.javaVersion)

		if (ctx.loader.isFabricLike) {
			ctx.extension.dependencies {
				required("java") { fabricLikeVersionRange = ">=${ctx.javaVersion.majorVersion}" }
			}
		}

		configureFletchingTable(ctx)
		registerGenerateManifestTask(ctx)
		configureJarTask(ctx)
		configureIdea()
		configureProcessResources(ctx)
		configureJava(ctx)
		registerBuildAndCollectTask(ctx)

		configureModPublishing(ctx)

		if (envTrue("PUB_MAVEN_ENABLE")) {
			configureMavenPublishing(ctx)
		}
	}

	private fun Project.configureJava(ctx: Context) {
		extensions.configure<JavaPluginExtension>("java") {
			withSourcesJar()
			withJavadocJar()
			// Select a real per-node toolchain (17/21/25) so javac runs on the
			// matching JDK — Gradle auto-provisions (foojay) or uses a system JDK —
			// instead of the daemon JVM. Fixes "invalid source release: 25".
			toolchain {
				languageVersion.set(
					org.gradle.jvm.toolchain.JavaLanguageVersion.of(ctx.javaVersion.majorVersion.toInt())
				)
			}
		}
	}

	private fun Project.registerGenerateManifestTask(ctx: Context) {
		val manifestOutputDir = layout.buildDirectory.dir("generated/modManifest")
		val generateTask = tasks.register<GenerateModManifestTask>("generateModManifest") {
			content.set(ctx.loader.generateManifest(ctx))
			outputFile.set(layout.buildDirectory.file("generated/modManifest/${ctx.loader.manifestPathFor(ctx)}"))
		}

		the<JavaPluginExtension>().sourceSets.named("main") { resources.srcDir(manifestOutputDir) }
		tasks.named<ProcessResources>("processResources") { dependsOn(generateTask) }
	}

	// Data-pack format per MC version (from each version.json `pack_version.data`).
	private fun packFormatFor(mc: String): Int = when (mc) {
		"1.20.1" -> 15
		"1.20.2" -> 18
		"1.20.3", "1.20.4" -> 26
		"1.20.5", "1.20.6" -> 41
		"1.21", "1.21.1" -> 48
		"1.21.2", "1.21.3" -> 57
		"1.21.4" -> 61
		"1.21.5" -> 71
		"1.21.6" -> 80
		"1.21.7", "1.21.8" -> 81
		"1.21.9", "1.21.10" -> 88
		"1.21.11" -> 94
		"26.1", "26.1.1", "26.1.2" -> 101
		"26.2" -> 107
		// 26.3 reset the minor to 0, so it needs no packMinorFor entry.
		"26.3" -> 121
		else -> 48
	}
	private fun packMinorFor(mc: String): Int = when (mc) {
		"1.21.11", "26.1", "26.1.1", "26.1.2", "26.2" -> 1
		else -> 0
	}
	// mcmeta schema changed at 1.21.9 (data-format 88): <=1.21.8 needs a `pack_format` int and
	// rejects min_format/max_format; >=1.21.9 requires min_format/max_format as [major, minor].
	private fun packMetaFieldsFor(mc: String): String {
		val f = packFormatFor(mc)
		val m = packMinorFor(mc)
		return if (f <= 81) "\"pack_format\": $f"
		else "\"pack_format\": $f, \"min_format\": [$f, $m], \"max_format\": [$f, $m]"
	}

	private fun Project.configureProcessResources(ctx: Context) {
		tasks.named<ProcessResources>("processResources") {
			dependsOn(tasks.named("stonecutterGenerate"), "kspKotlin")
			// codxlib currently ships no mixins ("mixins": []). Forge bundles an older Mixin
			// library than Fabric/NeoForge for the same MC (e.g. Forge 50.x on 1.20.6 does not
			// recognise JAVA_21), so pin Forge's mixin compatibilityLevel to JAVA_17 — a level
			// every bundled Mixin across 1.20.1–26.2 accepts. Harmless while the mixin list is
			// empty; revisit if real mixins compiled to newer bytecode are ever added.
			val mixinJava = if (ctx.loader is Loader.Forge) "JAVA_17" else "JAVA_${ctx.javaVersion.majorVersion}"
			filesMatching("*.mixins.json") {
				expand("java" to mixinJava)
			}
			// pack.mcmeta must carry a per-version pack_format int (MC < 1.21.11 requires it and
			// rejects the min_format/max_format-only schema). Forge keeps pack.mcmeta in the jar,
			// so stamp the right value from ${pack_format}.
			filesMatching(listOf("pack.mcmeta", "**/pack.mcmeta")) {
				expand("pack_meta" to packMetaFieldsFor(ctx.currentMcVersion))
			}
			exclude(ctx.loader.excludedResourcesFor(ctx))
		}
	}

	private fun Project.configureJarTask(ctx: Context) {
		val generateTask = tasks.named("generateModManifest")
		tasks.withType<Jar>().configureEach {
			archiveBaseName.set(ctx.modId)
			dependsOn(generateTask)
			if (ctx.loader is Loader.Forge) {
				manifest.attributes(ctx.loader.mixinConfigAttribute to "${ctx.modId}.mixins.json")
			}
		}
	}

	private fun Project.configureIdea() {
		extensions.configure<IdeaModel>("idea") {
			module {
				isDownloadJavadoc = true
				isDownloadSources = true
			}
		}
	}

	private fun Project.configureFletchingTable(ctx: Context) {
		extensions.configure<FletchingTableExtension> {
			mixins.create("main") { mixin("default", "${ctx.modId}.mixins.json") }
			j52j.register("main") { extension("json", "**/*.json5") }
		}
	}

	private fun Project.registerBuildAndCollectTask(ctx: Context) {
		tasks.register<Copy>("buildAndCollect") {
			from(
				tasks.named(ctx.extension.jarTask.get()),
				tasks.named(ctx.extension.sourcesJarTask.get()),
				tasks.named("javadocJar")
			)
			into(rootProject.layout.buildDirectory.file("libs/${ctx.basicVersion}"))
			dependsOn("build")
			group = "build"
		}
	}
}
