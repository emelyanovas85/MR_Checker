plugins {
    java
    id("org.springframework.boot") version "4.1.0"
}

group = "ru.cbr.bugbusters"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
    maven {
        name = "office_dev"
        url = uri("http://10.1.5.6:8882/artifactory/gradle-dev")
        isAllowInsecureProtocol = true
    }
    maven {
        name = "office_release"
        url = uri("http://10.1.5.6:8882/artifactory/gradle-release-local/")
        isAllowInsecureProtocol = true
    }
}

extra["springAiVersion"] = "2.0.0"

dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // JPA + H2 — implementation (не runtimeOnly!) т.к. H2TcpServerConfig использует org.h2.tools.Server при компиляции
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.h2database:h2")

    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.gitlab4j:gitlab4j-api:6.0.0")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // springdoc 3.x — требуется для Spring Boot 4.x / Spring Framework 7.x
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.0")

    // Guava — RateLimiter для ограничения частоты запросов к LLM (LlmRateLimiter)
    implementation("com.google.guava:guava:33.4.8-jre")

    // webhook-distributor-client — из корпоративного Artifactory CBR
    implementation("bugbusters.modules:webhook-distributor-client:1.0.0")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
