import com.google.protobuf.gradle.*

plugins {
    `java-library`
    id("com.google.protobuf")
}

dependencies {
    api("com.google.protobuf:protobuf-java:4.28.2")
    compileOnly("jakarta.annotation:jakarta.annotation-api:2.1.1")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.28.2"
    }
}
