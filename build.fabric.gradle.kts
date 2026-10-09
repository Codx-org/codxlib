// Fabric via Architectury Loom (Gradle-9 native). Arch-loom is Fabric Loom + Forge/NeoForge
// support; for pure Fabric it behaves like Fabric Loom, so this replaces the old
// loom-back-compat shim (which clashed on the classpath with arch-loom's Forge build).
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
	loader = "fabric"
	dependencies {
		required("minecraft") {
			fabricLikeVersionRange = prop("deps.minecraft")
		}
		required("fabric-api") {
			slug("fabric-api")
			// Per-MC FLOOR, not the build-time pin (deps.fabric-api) -- same split as
			// fabricloader below. Declaring the pin here forbids every older build of the
			// same Fabric API line, and because consumers hard-depend on codxlib this floor
			// is transitive: it overrides theirs. See AlexsMobsContinued #118.
			fabricLikeVersionRange = ">=${prop("deps.fabric-api-min")}"
		}
		required("fabricloader") {
			// Per-MC floor, NOT the build-time pin (deps.fabric-loader) — see the note in
			// stonecutter.properties.toml. Using the pin here shipped ">=0.19.3" on every node.
			fabricLikeVersionRange = ">=${prop("deps.fabric-loader-min")}"
		}
	}
}

// Flat-src: compile only this loader's package on this node.
sourceSets.named("main") {
	java {
		exclude("codx/codxlib/neoforge/**", "codx/codxlib/forge/**")
		// CustomPacketPayload/StreamCodec networking is 1.20.5+; gate it out below.
		if (sc.current.parsed < "1.20.5") exclude("codx/codxlib/api/network/**", "codx/codxlib/fabric/network/**")
	}
}

loom {
	silentMojangMappingsLicense()
	runs {
		named("client") {
			client()
			ideConfigGenerated(true)
			runDir = "run/"
			programArgs("--username=Dev")
			configName = "Fabric Client"
		}
		named("server") {
			server()
			ideConfigGenerated(true)
			runDir = "run/"
			configName = "Fabric Server"
		}
	}
}

repositories {
	mavenCentral()
}

configurations.all {
	resolutionStrategy {
		force("net.fabricmc:fabric-loader:${prop("deps.fabric-loader")}")
	}
}

dependencies {
	minecraft("com.mojang:minecraft:${prop("deps.minecraft")}")
	// arch-loom requires a mappings dependency on every node. <26 uses official Mojmap;
	// 26.x ships unobfuscated (no published Mojmap) → empty layered mapping = identity.
	if (sc.current.parsed < "26") {
		mappings(loom.officialMojangMappings())
	} else {
		mappings(loom.layered {})
	}
	modImplementation("net.fabricmc:fabric-loader:${prop("deps.fabric-loader")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric-api")}")
}
