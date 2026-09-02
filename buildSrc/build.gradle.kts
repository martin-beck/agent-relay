plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.28.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
