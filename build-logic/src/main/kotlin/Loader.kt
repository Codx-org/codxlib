@file:Suppress("unused")

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.peanuuutz.tomlkt.Toml
import org.gradle.api.NamedDomainObjectContainer
import java.util.*

private val JSON = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }
private val TOML = Toml { }

sealed class Loader(val id: String) {
	abstract val modManifestPath: String
	abstract val excludedResources: List<String>

	open val isFabricLike: Boolean = false

	// Path/exclusions can depend on the MC version (see NeoForge's legacy manifest name).
	// Defaults fall back to the static values above.
	open fun manifestPathFor(ctx: Context): String = modManifestPath
	open fun excludedResourcesFor(ctx: Context): List<String> = excludedResources

	abstract fun generateManifest(ctx: Context): String

	object Fabric : Loader("fabric") {
		override val isFabricLike = true
		override val modManifestPath = "fabric.mod.json"
		override val excludedResources = listOf(
			"META-INF/mods.toml", "META-INF/neoforge.mods.toml", "aw/*.cfg", ".cache", "pack.mcmeta"
		)

		override fun generateManifest(ctx: Context): String {
			val manifest = FabricManifest(
				id = ctx.modId,
				name = ctx.modName,
				version = ctx.baseVersion,
				authors = ctx.authors,
				contributors = ctx.contributors,
				contact = mapOf(
					"sources" to ctx.sourcesUrl, "issues" to ctx.issuesUrl, "homepage" to ctx.homepageUrl
				),
				custom = ctx.discordUrl.takeIf { it.isNotEmpty() }?.let { url ->
					buildJsonObject {
						putJsonObject("modmenu") {
							putJsonObject("links") {
								put("modmenu.discord", url)
							}
						}
					}
				},
				description = ctx.description,
				icon = "assets/${ctx.modId}/icon.png",
				license = ctx.licenseName,
				accessWidener = null,
				entrypoints = mapOf(
					"main" to listOf("${ctx.modGroup}.${ctx.modId}.fabric.CodxLibFabric"),
					"client" to listOf("${ctx.modGroup}.${ctx.modId}.fabric.CodxLibFabricClient")
				),
				mixins = listOf("${ctx.modId}.mixins.json"),
				depends = ctx.extension.dependencies.required.associate { it.modid.get() to it.fabricLikeVersionRange.get() },
				recommends = ctx.extension.dependencies.optional.associate { it.modid.get() to it.fabricLikeVersionRange.get() },
				breaks = ctx.extension.dependencies.incompatible.associate { it.modid.get() to it.fabricLikeVersionRange.get() },
				provides = ctx.extension.dependencies.embeds.map { it.modid.get() }
			)
			return JSON.encodeToString(manifest)
		}
	}

	sealed class ForgeLike(id: String) : Loader(id) {
		override val excludedResources = listOf(
			"fabric.mod.json", "aw/*.accesswidener", ".cache"
		)

		override fun generateManifest(ctx: Context): String {
			val forgeDeps = mutableListOf<ForgeDependency>()

			fun addDeps(container: NamedDomainObjectContainer<Dependency>, type: String) {
				container.forEach {
					forgeDeps.add(
						ForgeDependency(
							modId = it.modid.get(),
							side = it.environment.get().uppercase(Locale.getDefault()),
							versionRange = it.forgeLikeVersionRange.get(),
							mandatory = type == "required",
							type = type
						)
					)
				}
			}

			addDeps(ctx.extension.dependencies.required, "required")
			addDeps(ctx.extension.dependencies.optional, "optional")
			addDeps(ctx.extension.dependencies.incompatible, "incompatible")

			val manifest = ForgeManifest(
				license = ctx.licenseName,
				issueTrackerURL = ctx.issuesUrl,
				mods = listOf(
					ForgeMod(
						modId = ctx.modId,
						displayName = ctx.modName,
						version = ctx.baseVersion,
						displayURL = ctx.homepageUrl,
						modUrl = ctx.homepageUrl,
						logoFile = "assets/icon.png",
						authors = ctx.authors.joinToString(", "),
						credits = "${ctx.authors.joinToString(", ")} Contributors: ${ctx.contributors.joinToString(", ")}",
						description = ctx.description
					)
				),
				dependencies = mapOf(ctx.modId to forgeDeps),
				mixins = listOf(ForgeMixin("${ctx.modId}.mixins.json")),
				accessTransformers = emptyList()  // codxlib ships no AT file
			)

			return TOML.encodeToString(manifest)
		}
	}

	object NeoForge : ForgeLike("neoforge") {
		override val modManifestPath = "META-INF/neoforge.mods.toml"
		override val excludedResources = (super.excludedResources + "META-INF/mods.toml") + "pack.mcmeta"

		// NeoForge renamed META-INF/mods.toml -> META-INF/neoforge.mods.toml at MC 1.20.5
		// (NeoForge 20.5). Older NeoForge (20.4 and below) reads ONLY the legacy filename,
		// so on those nodes emit META-INF/mods.toml instead — otherwise the manifest is
		// never found and the mod is silently absent at runtime (bit us on 1.20.4-neoforge).
		private fun isLegacy(ctx: Context) = !ctx.stonecutter.eval(ctx.currentMcVersion, ">=1.20.5")

		override fun manifestPathFor(ctx: Context) =
			if (isLegacy(ctx)) "META-INF/mods.toml" else "META-INF/neoforge.mods.toml"

		override fun excludedResourcesFor(ctx: Context) =
			if (isLegacy(ctx)) (super.excludedResources + "META-INF/neoforge.mods.toml") + "pack.mcmeta"
			else excludedResources
	}

	object Forge : ForgeLike("forge") {
		override val modManifestPath = "META-INF/mods.toml"
		override val excludedResources = super.excludedResources + "META-INF/neoforge.mods.toml"
		val mixinConfigAttribute = "MixinConfigs"
	}

	companion object {
		fun of(id: String): Loader = when (id) {
			"fabric" -> Fabric
			"neoforge" -> NeoForge
			// "fabricnr"/"forgenr" = the arch-loom NO-REMAP buildscripts for the unobfuscated
			// 26.x nodes; "forgeg" = the arch-loom Forge script for classic nodes. All resolve
			// to the same loader (gating is driven by the node id, not the buildscript name).
			"fabricnr" -> Fabric
			"forge", "forgeg", "forgenr" -> Forge
			else -> error("Unknown loader: '$id'")
		}
	}
}
