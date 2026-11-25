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
    // Test-only: proves the JSON Kotlin writes matches contracts/schemas.
    // Never shipped in the app, so Jackson is not an APK dependency.
    testImplementation(libs.json.schema.validator)
}

tasks.withType<Test>().configureEach {
    useJUnit()

    val fixtureDir = rootProject.layout.projectDirectory.dir("../contracts/fixtures")
    val schemaDir = rootProject.layout.projectDirectory.dir("../contracts/schemas")
    inputs.dir(fixtureDir).withPropertyName("contractFixtures")
    inputs.dir(schemaDir).withPropertyName("contractSchemas")
    systemProperty("contracts.fixtures", fixtureDir.asFile.absolutePath)
    systemProperty("contracts.schemas", schemaDir.asFile.absolutePath)
}