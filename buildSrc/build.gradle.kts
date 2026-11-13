plugins { java }

repositories { mavenCentral() }

dependencies {
    implementation("org.ow2.asm:asm:9.7.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
