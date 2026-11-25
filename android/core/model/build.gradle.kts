plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()

    val fixtureDir = rootProject.layout.projectDirectory.dir("../contracts/fixtures")
    inputs.dir(fixtureDir).withPropertyName("contractFixtures")
    systemProperty("contracts.fixtures", fixtureDir.asFile.absolutePath)
}