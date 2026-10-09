plugins {
	id("mod-platform")
	id("net.neoforged.moddev")
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
	loader = "neoforge"
	dependencies {
		required("minecraft") {
			forgeLikeVersionRange = prop("deps.minecraft")
		}
		required("neoforge") {
			forgeLikeVersionRange.set("[1,)")
		}
	}
}

// Flat-src: compile only this loader's package on this node.
sourceSets.named("main") {
	java {
		exclude("codx/codxlib/fabric/**", "codx/codxlib/forge/**")
		// CustomPacketPayload/StreamCodec networking is 1.20.5+; gate it out below.
		if (sc.current.parsed < "1.20.5") exclude("codx/codxlib/api/network/**", "codx/codxlib/neoforge/network/**")
	}
}

// 26.3 needs a NEWER NeoFormRuntime than moddev 2.0.141 defaults to (2.0.18). With that
// default, :createMinecraftArtifacts fails in its own `recompile` step, on VANILLA source,
// before any of our code is looked at: its decompiler emits the anonymous HolderSet$1
// inside HolderSet.Named with a package-private contents(), and javac rejects overriding a
// public method with it ("attempting to assign weaker access privileges"). There is no
// newer upstream build to move to -- 26.3.0.1-beta is the newest NeoForge 26.3 and
// neoform 26.3-1 the newest NeoForm -- and flipping useEclipseCompiler only trades the
// error for another (2.0.18's bundled ECJ calls Java 25's flexible constructor bodies,
// which vanilla's NativeLibrariesBootstrap uses, a disabled preview feature). NFRT 2.0.31
// decompiles that method correctly and recompiles under plain javac. Scoped to 26.3 so the
// other 18 NeoForge nodes keep using the version they were built and shipped with.
if (stonecutter.current.parsed >= "26.3") {
	neoFormRuntime {
		version = "2.0.31"
	}
}

neoForge {
	version = prop("deps.neoforge")

	runs {
		register("client") {
			client()
			gameDirectory = file("run/")
			ideName = "NeoForge Client (${stonecutter.current.version})"
			programArgument("--username=Dev")
		}
		register("server") {
			server()
			gameDirectory = file("run/")
			ideName = "NeoForge Server (${stonecutter.current.version})"
		}
	}

	mods {
		register(prop("mod.id")) {
			sourceSet(sourceSets["main"])
		}
	}
}

repositories {
	mavenCentral()

	// The NeoForged maven serves a ZERO-BYTE maven-metadata.xml for org.apache.logging.log4j
	// (HTTP 200, empty body -- not a 404), and neoforge 20.4/20.6 pull in
	// net.minecraftforge:unsafe:0.2.0, which asks for the dynamic version log4j:2.11.+.
	// Listing a dynamic version queries every repo, Gradle fails to parse the empty XML and
	// aborts the whole resolution instead of falling through to Maven Central. log4j has no
	// business coming from there anyway, so take it off that repo's menu.
	// (The moddev plugin adds the NeoForged repo itself, hence configureEach rather than a
	// declaration here.)
	withType<MavenArtifactRepository>().configureEach {
		if (url.toString().contains("maven.neoforged.net")) {
			content { excludeGroupByRegex("org\\.apache\\.logging\\.log4j.*") }
		}
	}
}

dependencies {
}

tasks.named("createMinecraftArtifacts") {
	dependsOn(tasks.named("stonecutterGenerate"))
}
