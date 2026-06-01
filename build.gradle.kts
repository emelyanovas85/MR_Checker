plugins {
    java
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
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
    // Корпоративный Artifactory CBR — dev-снапшоты и релизы
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
    mavenCentral()
}

extra["springAiVersion"] = "1.0.6"

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.gitlab4j:gitlab4j-api:6.0.0")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.8")

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
