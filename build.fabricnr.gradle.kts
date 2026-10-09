// 26.x Fabric via Architectury Loom NO-REMAP variant. MC >=26 ships UNOBFUSCATED (Mojang's
// first unobf release), so there is no mappings/remap step: apply loom-no-remap, omit the
// mappings(...) dependency, and the output artifact is `jar` (not remapJar). Node id stays
// "<mc>-fabric" so //? if fabric gating is unchanged; only the buildscript differs.
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

sourceSets.named("main") {
	java {
		exclude("codx/codxlib/neoforge/**", "codx/codxlib/forge/**")
		if (sc.current.parsed < "1.20.5") exclude("codx/codxlib/api/network/**", "codx/codxlib/fabric/network/**")
	}
}

loom {
	silentMojangMappingsLicense()
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
}

configurations.all {
	resolutionStrategy {
		force("net.fabricmc:fabric-loader:${prop("deps.fabric-loader")}")
	}
}

dependencies {
	minecraft("com.mojang:minecraft:${prop("deps.minecraft")}")
	// NO mappings(...) and plain implementation (not modImplementation) — loom-no-remap skips
	// all remapping on the unobfuscated 26.x jar, so mod deps are used as-is.
	implementation("net.fabricmc:fabric-loader:${prop("deps.fabric-loader")}")
	implementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric-api")}")
}
