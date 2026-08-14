plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":apm-contracts"))
    
    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    
    // High-Performance Off-Heap & Primitives
    implementation("it.unimi.dsi:fastutil:8.5.15")
    implementation("org.agrona:agrona:1.23.1")
    implementation("com.lmax:disruptor:3.4.4")
    
    // ClickHouse & HTTP5 Client
    implementation("com.clickhouse:clickhouse-jdbc:0.6.5:http")
    implementation("com.clickhouse:clickhouse-client:0.6.5")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.3.1")
    
    // Protobuf Runtime
    implementation("com.google.protobuf:protobuf-java:4.28.2")

    // Utilities & Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
}
