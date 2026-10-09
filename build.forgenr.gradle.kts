// 26.x Forge via Architectury Loom NO-REMAP variant (unobfuscated 26.x, no mappings/remap).
// Node id stays "<mc>-forge" so //? if forge gating is unchanged. 26.x uses the new
// bus-group event API branch (>=1.21.6) in the shared forge source.
plugins {
	id("mod-platform")
	id("dev.architectury.loom-no-remap")
}

stonecutter {
	val (version, loader) = current.project.split('-', limit = 2)
	properties.tags(version, loader)

	replacements.string(current.parsed >= "1.21.11") {
		replace("ResourceLocation", "Identifier")
		replace("location()", "identifier()")
	}

	// codxlib's client UI toolkit (api.ui) is written against the 26.x GUI names, where the
	// old immediate-mode draw calls became "render state extraction". Those are pure renames,
	// so map them back here rather than duplicating every method body. Genuine structural
	// differences (who runs the background pass, the widget content hook, the mouse-event
	// record) are //? gates in the sources instead.
	replacements.string(current.parsed < "26.1") {
		replace("GuiGraphicsExtractor", "GuiGraphics")
		replace("extractRenderState", "render")
		replace("graphics.centeredText(", "graphics.drawCenteredString(")
		replace("graphics.text(", "graphics.drawString(")
	}
	// GuiGraphics.renderOutline was briefly renamed submitOutline for 1.21.9/1.21.10 only
	// (1.21.11 renamed it back), so this one rename is not monotonic in the version order.
	replacements.string(current.parsed >= "1.21.9" && current.parsed < "1.21.11") {
		replace("graphics.outline(", "graphics.submitOutline(")
	}
	replacements.string(current.parsed < "1.21.9" || (current.parsed >= "1.21.11" && current.parsed < "26.1")) {
		replace("graphics.outline(", "graphics.renderOutline(")
	}
	// 26.2 renamed Minecraft.setScreen to setScreenAndShow.
	replacements.string(current.parsed >= "26.2") {
		replace("minecraft.setScreen(", "minecraft.setScreenAndShow(")
	}
	// Screen.renderTransparentBackground arrived in 1.20.2; on 1.20.1 the only background
	// helper is the 2-arg renderBackground, which that node's //? branch calls directly.
	replacements.string(current.parsed < "26.1" && current.parsed >= "1.20.2") {
		replace("extractTransparentBackground", "renderTransparentBackground")
	}
}

platform {
	loader = "forge"
	dependencies {
		required("minecraft") {
			forgeLikeVersionRange = prop("deps.minecraft")
		}
		required("forge") {
			forgeLikeVersionRange.set("[1,)")
		}
	}
}

sourceSets.named("main") {
	java {
		exclude("codx/codxlib/fabric/**", "codx/codxlib/neoforge/**")
		if (sc.current.parsed < "1.20.5") exclude("codx/codxlib/api/network/**", "codx/codxlib/forge/network/**")
	}
}

loom {
	silentMojangMappingsLicense()
	forge {
		mixinConfig("codxlib.mixins.json")
	}
	runs {
		named("server") {
			server()
			runDir = "run/"
		}
		named("client") {
			client()
			runDir = "run/"
		}
	}
}

repositories {
	mavenCentral()
	maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
}

dependencies {
	minecraft("com.mojang:minecraft:${prop("deps.minecraft")}")
	// NO mappings(...) — loom-no-remap on the unobfuscated 26.x jar.
	"forge"("net.minecraftforge:forge:${prop("deps.minecraft")}-${prop("deps.forge")}")
}

// loom-no-remap still pulls in Architectury's dev naming + mixin-remapper services, which
// exist only to remap mixin refmaps between namespaces. On unobfuscated 26.x there is no
// remapping to do, yet those services always run and abort the dev server demanding a
// mappings tree / `architectury.naming.sourceNamespace` that loom-no-remap never sets
// ("Missing required system property"). Keep them off the run classpath entirely.
configurations.configureEach {
	exclude(group = "dev.architectury", module = "architectury-naming-service")
	exclude(group = "dev.architectury", module = "architectury-mixin-remapper-service")
}

// Forge 62+ (the 26.x fork) uses a stricter securemodules that, in a dev runServer,
// scans each classpath entry for transformer services and derives an automatic module
// name for it. For the mod's *exploded* output dirs (build/classes/**, build/resources/main)
// there's no module-info and no Automatic-Module-Name, so its filename heuristic yields an
// EMPTY name → "Invalid module name: '' is not a Java identifier" and the server aborts
// before loading. Classic Forge (<=61) tolerated it. Fix: drop a MANIFEST.MF carrying an
// explicit Automatic-Module-Name into the dev resources so securemodules names it directly.
// Written as processResources' own final step (not a separate task) so runServer, which
// consumes build/resources/main, doesn't trip Gradle's implicit-dependency validation.
tasks.named("processResources") {
	val mfFile = layout.buildDirectory.file("resources/main/META-INF/MANIFEST.MF")
	val moduleName = prop("mod.id")
	doLast {
		val f = mfFile.get().asFile
		f.parentFile.mkdirs()
		f.writeText("Manifest-Version: 1.0\nAutomatic-Module-Name: $moduleName\n")
	}
}
// The built jar generates its own MANIFEST.MF; don't let the dev copy above collide.
tasks.withType<Jar>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
