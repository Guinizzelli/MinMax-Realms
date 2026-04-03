plugins {
    id("fabric-loom") version "1.10-SNAPSHOT"
    id("maven-publish")
}

base {
    archivesName = properties["archives_base_name"] as String
    group = properties["maven_group"] as String
    version = (properties["mod_version"] as String) + "-mc" + (properties["minecraft_version"] as String)
}

repositories {
    mavenCentral()
    maven("https://maven.terraformersmc.com")
}

dependencies {
    minecraft("com.mojang:minecraft:${properties["minecraft_version"]}")
    mappings("net.fabricmc:yarn:${properties["yarn_mappings"]}:v2")
    modImplementation("net.fabricmc:fabric-loader:${properties["loader_version"]}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${properties["fapi_version"]}")
}

tasks.processResources {
    val propertyMap = mapOf(
        "version" to project.version,
        "minecraft_version" to project.property("minecraft_version"),
        "loader_version" to project.property("loader_version")
    )

    inputs.properties(propertyMap)
    filesMatching("fabric.mod.json") {
        expand(propertyMap)
    }
}

tasks.withType<JavaCompile> {
    options.release = 21
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val prismLauncherModsDir = File(
    System.getProperty("user.home"),
    "AppData/Roaming/PrismLauncher/instances/1.21.5/minecraft/mods"
)

tasks.register<Copy>("exportJarToWorkspace") {
    group = "build"
    dependsOn("remapJar")
    from(layout.buildDirectory.file("libs/${base.archivesName.get()}-${project.version}.jar"))
    into(projectDir.parentFile.resolve("build"))
}

tasks.register<Copy>("exportJarToPrismLauncherMods") {
    group = "build"
    dependsOn("remapJar")
    from(layout.buildDirectory.file("libs/${base.archivesName.get()}-${project.version}.jar"))
    into(prismLauncherModsDir)
}

tasks.build {
    finalizedBy("exportJarToWorkspace", "exportJarToPrismLauncherMods")
}
