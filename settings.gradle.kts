pluginManagement {
	repositories {
		mavenLocal()
		mavenCentral()
		gradlePluginPortal()
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
		maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
		maven("https://maven.architectury.dev/") { name = "Architectury" }  // architectury-loom (Forge on Gradle 9)
		maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
		maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
		maven("https://maven.parchmentmc.org") { name = "ParchmentMC" }
	}
	// architectury-loom has no plugin marker on the maven; map the id → artifact so the
	// plugins { id("dev.architectury.loom") version "..." } request resolves (incl. snapshots).
	resolutionStrategy {
		eachPlugin {
			if (requested.id.id.startsWith("dev.architectury.loom")) {  // incl. loom-no-remap
				useModule("dev.architectury:architectury-loom:1.17-SNAPSHOT")
			}
		}
	}
	includeBuild("build-logic")
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
	id("dev.kikugie.stonecutter") version "0.9.2"
}

rootProject.name = "CodxLib"

stonecutter {
	create(rootProject) {
		fun match(version: String, vararg loaders: String) =
			loaders.forEach { version("$version-$it", version).buildscript = "build.$it.gradle.kts" }

		// All Forge nodes build on Architectury Loom (build.forgeg.gradle.kts) — the only
		// Gradle-9 tool that builds classic MinecraftForge. Node loader tag stays "forge"
		// (id "<mc>-forge") so //? if forge source gating is shared across every Forge node.
		fun forgeModern(version: String) =
			version("$version-forge", version).apply { buildscript = "build.forgeg.gradle.kts" }

		// Unobfuscated 26.x nodes use the arch-loom NO-REMAP buildscripts (no mappings/remap).
		fun fabricNoRemap(version: String) =
			version("$version-fabric", version).apply { buildscript = "build.fabricnr.gradle.kts" }
		fun forgeNoRemap(version: String) =
			version("$version-forge", version).apply { buildscript = "build.forgenr.gradle.kts" }

		// A node per point release (exact-pinned). Fabric + modern NeoForge on
		// every version 1.20.2+. 1.20.1 is Fabric-only here — its legacy NeoForge
		// (net.neoforged:forge) + classic Forge use the legacy toolchain (Part B/C).
		// 26.x is UNOBFUSCATED → Fabric + Forge use the arch-loom NO-REMAP buildscripts;
		// NeoForge stays on MDG. Requires a Java 25 daemon (arch-loom provisions MC on it).
		match("26.3", "neoforge");   fabricNoRemap("26.3")  // Forge: no 26.3 build upstream
		match("26.2", "neoforge");   fabricNoRemap("26.2");   forgeNoRemap("26.2")
		match("26.1.2", "neoforge"); fabricNoRemap("26.1.2"); forgeNoRemap("26.1.2")
		match("26.1.1", "neoforge"); fabricNoRemap("26.1.1"); forgeNoRemap("26.1.1")
		match("26.1", "neoforge");   fabricNoRemap("26.1");   forgeNoRemap("26.1")
		// Classic range (1.20.1–1.21.11): Fabric + NeoForge + Forge, all on Gradle 9.
		// Forge nodes = every classic version Forge actually ships (no build for 1.20.5 / 1.21.2).
		match("1.21.11", "fabric", "neoforge"); forgeModern("1.21.11")
		match("1.21.10", "fabric", "neoforge"); forgeModern("1.21.10")
		match("1.21.9", "fabric", "neoforge"); forgeModern("1.21.9")
		match("1.21.8", "fabric", "neoforge"); forgeModern("1.21.8")
		match("1.21.7", "fabric", "neoforge"); forgeModern("1.21.7")
		match("1.21.6", "fabric", "neoforge"); forgeModern("1.21.6")
		match("1.21.5", "fabric", "neoforge"); forgeModern("1.21.5")
		match("1.21.4", "fabric", "neoforge"); forgeModern("1.21.4")
		match("1.21.3", "fabric", "neoforge"); forgeModern("1.21.3")
		match("1.21.2", "fabric", "neoforge")  // Forge: no 1.21.2 build upstream
		match("1.21.1", "fabric", "neoforge"); forgeModern("1.21.1")
		match("1.21", "fabric", "neoforge"); forgeModern("1.21")
		match("1.20.6", "fabric", "neoforge"); forgeModern("1.20.6")
		match("1.20.5", "fabric")  // NeoForge + Forge: no build upstream
		match("1.20.4", "fabric", "neoforge"); forgeModern("1.20.4")
		match("1.20.3", "fabric")  // Forge 49.0.1 userdev → dead bootstrap-dev:2.0.0; NeoForge: no bundle
		match("1.20.2", "fabric")  // Forge 48.0.0 short-lived; NeoForge: no bundle
		match("1.20.1", "fabric"); forgeModern("1.20.1")  // NeoForge 1.20.1 = legacy (separate)

		// Canonical/active source = 26.1.2-fabric (26.x, >=26.1 fork branches live). The active
		// node compiles root src/ directly, so it must match how root is flipped (fabric).
		vcsVersion = "26.1.2-fabric"
	}
}

// arch-loom must know its platform BEFORE its plugin applies. Every project is configured
// on any task, so Forge nodes must declare loom.platform=forge or their loom{forge{}} block
// aborts the whole build. Fabric is arch-loom's default; NeoForge uses MDG (ignores this).
gradle.beforeProject {
	if (name.endsWith("-forge")) {
		extensions.extraProperties["loom.platform"] = "forge"
	}
}
