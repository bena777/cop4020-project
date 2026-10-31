plugins {
    id("java")
}

group = "plc.project"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.guava:guava:33.7.1-jre")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_26
}

tasks.test {
    useJUnitPlatform()
}
