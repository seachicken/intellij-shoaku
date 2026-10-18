import org.jetbrains.changelog.markdownToHTML

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
    alias(libs.plugins.changelog)
    alias(libs.plugins.compose)
}

group = "shoaku"
version = "0.7.4"

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        composeUI()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        changeNotes = """
            <h2>Improvements</h2>
            <ul>
                <li>Run independent goals without waiting for other sessions</li>
                <li>Keep plan objectives and implementation steps aligned, and resume Explorer when plans change</li>
                <li>Consolidate implementation corrections into the relevant commits</li>
                <li>Simplify outcome review with a commit tree and linked diff navigation</li>
                <li>Move Ask Codex into the plan section</li>
                <li>Distinguish Navigator and Explorer in Codex session titles</li>
                <li>Reduce internal chat output by submitting plans through a dedicated tool in new sessions</li>
                <li>Have Navigator supply external context so Explorer can implement and verify offline</li>
            </ul>
            <h2>Bug Fixes</h2>
            <ul>
                <li>Keep Explorer goal objectives within the API length limit while preserving complete plans in files</li>
                <li>Allow failed plan handoffs to be retried without changing the plan</li>
            </ul>
        """.trimIndent()
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    buildPlugin {
        from(layout.projectDirectory.dir("server/dist")) {
            into("node-module")
        }
    }
}
