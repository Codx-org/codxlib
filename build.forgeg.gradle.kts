// Forge via Architectury Loom (builds Forge on modern Gradle 9 — no ForgeGradle, so it
// avoids the FG6/Gradle-8 wall). Applied ONLY to Forge nodes; Fabric (Loom) and NeoForge
// (MDG) keep their own toolchains untouched.
plugins {
	id("mod-platform")
	id("dev.architectury.loom")  // version pinned in settings.gradle.kts resolutionStrategy
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

// Flat-src: compile only this loader's package on this node.
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
}

repositories {
	mavenCentral()
	maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
}

dependencies {
	minecraft("com.mojang:minecraft:${prop("deps.minecraft")}")
	mappings(loom.officialMojangMappings())
	"forge"("net.minecraftforge:forge:${prop("deps.minecraft")}-${prop("deps.forge")}")

	// Forge 51.0.x (MC 1.21) ships a userdev POM that forgets jopt-simple, even though
	// cpw.mods.modlauncher's module-info `requires jopt.simple`, so its dev server aborts
	// with "Module jopt.simple not found" (Forge 52+ include it). Add it to loom's Forge
	// runtime library set so it lands on the dev module path. Harmless on nodes that
	// already provide it (duplicate classpath entry).
	"forgeRuntimeLibrary"("net.sf.jopt-simple:jopt-simple:5.0.4")
}

// modlauncher `requires jopt.simple` — the automatic module name of jopt-simple 5.0.4.
// jopt-simple 6.0-alpha-* ships a real module-info named `joptsimple` (no dot), which does
// NOT satisfy that requires. Forge 51's constraints try to pull 6.0-alpha-3, so pin 5.0.4.
configurations.configureEach {
	resolutionStrategy { force("net.sf.jopt-simple:jopt-simple:5.0.4") }
}
