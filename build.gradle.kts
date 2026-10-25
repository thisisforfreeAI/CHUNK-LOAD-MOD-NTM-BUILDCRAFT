plugins {
    java
    id("net.neoforged.moddev") version "2.0.148"
}

version = property("mod_version")!!
group = property("mod_group_id")!!

base {
    archivesName = "${property("mod_id")}-neoforge-${property("minecraft_version")}"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases/")
}

neoForge {
    version = property("neo_version") as String

    runs {
        create("server") {
            server()
            programArgument("--nogui")
        }
        create("client") {
            client()
        }
    }

    mods {
        create(property("mod_id") as String) {
            sourceSet(sourceSets.main.get())
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

val modProps = mapOf(
    "mod_id" to property("mod_id"),
    "mod_name" to property("mod_name"),
    "mod_version" to property("mod_version"),
)

tasks.processResources {
    inputs.properties(modProps)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(modProps)
    }
}
