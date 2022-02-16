plugins {
    id("soffa.java8")
    id("soffa.maven-publish")
    id("soffa.test.junit5")

}

dependencies {
    api(project(":soffa-foundation-api"))
    api(project(":soffa-foundation-commons"))
    api(project(":soffa-foundation-models"))

    api("com.jayway.jsonpath:json-path:2.7.0")
    api("javax.transaction:javax.transaction-api:1.3")
    api("com.netflix.graphql.dgs:graphql-dgs:4.9.17")
    api("com.amazonaws:aws-java-sdk-s3:1.12.154")
    api("org.hibernate.javax.persistence:hibernate-jpa-2.1-api:1.0.2.Final")
    compileOnly("org.jobrunr:jobrunr:4.0.7") {
        exclude(group = "com.zaxxer")
        exclude(group = "com.h2database")
    }
    compileOnly("org.jdbi:jdbi3-core:3.27.1") {
        exclude(group = "com.github.ben-manes.caffeine")
    }
    @Suppress("GradlePackageUpdate")
    api("com.github.ben-manes.caffeine:caffeine:2.9.3") // Don't use version 3, it's not compatible with Java8
    api("org.liquibase:liquibase-core:4.7.1")
    @Suppress("GradlePackageUpdate")
    api("com.h2database:h2:2.1.210") // warning: version 2.x add KEY and VALUE as reserved words
    @Suppress("GradlePackageUpdate")

    implementation("org.apache.commons:commons-email:1.5")
    implementation("io.pebbletemplates:pebble:3.1.5")
    implementation("com.auth0:java-jwt:3.18.3")
    implementation("com.nimbusds:nimbus-jose-jwt:9.19")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:2.6.3")

}
