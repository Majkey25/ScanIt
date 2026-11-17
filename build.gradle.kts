buildscript {
    // Keep build-only transitive security overrides aligned.
    configurations.classpath {
        resolutionStrategy.force(
            "org.apache.commons:commons-lang3:3.22.0",
            "org.bitbucket.b_c:jose4j:0.9.7",
            "org.bouncycastle:bcpkix-jdk18on:1.87",
            "org.bouncycastle:bcprov-jdk18on:1.87",
            "org.bouncycastle:bcutil-jdk18on:1.87",
            "org.jdom:jdom2:2.0.6.1",
        )
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
