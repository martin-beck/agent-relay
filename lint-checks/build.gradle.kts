plugins {
    `java-library`
}

dependencies {
    compileOnly("com.android.tools.lint:lint-api:32.0.1")
    compileOnly("com.android.tools.lint:lint-checks:32.0.1")
    testImplementation("com.android.tools.lint:lint-api:32.0.1")
    testImplementation("com.android.tools.lint:lint-tests:32.0.1")
    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.test {
    useJUnit()
}
