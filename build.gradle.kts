//////////////////////////////////////
// gradle build configuration
//////////////////////////////////////

plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"

}

group = "com.oramad"
version = "j17"   // oramad : this string will be embedded in final built jar : usually stands for java version

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.prometheus:simpleclient:0.16.0")
    implementation("io.prometheus:simpleclient_dropwizard:0.16.0")
    implementation("io.prometheus:simpleclient_common:0.16.0") // Added for TextFormat
    implementation("com.google.code.gson:gson:2.10.1") // oramad : added for json parsing
    // compile against the specific version of cassandra the agent will run on
    compileOnly("org.apache.cassandra:cassandra-all:5.0.4")
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

// Configure the shadowJar task to create a fat JAR with the Premain-Class attribute.
// This is the correct way to make a runnable Java agent.
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    manifest {
        attributes["Premain-Class"] = "com.oramad.CassPromFileExporter"
    }
}


tasks.test {
    useJUnitPlatform()
}

//////////////////////////////////////
// gradle build configuration - end
//////////////////////////////////////

