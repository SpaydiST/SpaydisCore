import org.gradle.api.tasks.JavaExec

plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.0.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.maxhenkel.de/repository/public")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.49-alpha")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.20")
    testImplementation("io.papermc.paper:paper-api:26.3.build.49-alpha")
    testImplementation("de.maxhenkel.voicechat:voicechat-api:2.6.20")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    val regressionTest = register<JavaExec>("regressionTest") {
        dependsOn(testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("macronux.spaydiscore.SpaydisCoreRegressionTest")
        jvmArgs("-ea")
    }

    test {
        dependsOn(regressionTest)
        failOnNoDiscoveredTests = false
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.3")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("plugin.yml") {
            expand(props)
        }
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}
