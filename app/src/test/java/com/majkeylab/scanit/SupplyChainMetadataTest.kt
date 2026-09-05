package com.majkeylab.scanit

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplyChainMetadataTest {
    @Test
    fun dependencyVerificationCoversUbuntuCiAapt2Classifier() {
        val repository = File("..").canonicalFile
        val buildScript = File(repository, "build.gradle.kts").readText()
        val agpVersion =
            checkNotNull(
                Regex("""id\("com\.android\.application"\) version "([^"]+)"""")
                    .find(buildScript)
                    ?.groupValues
                    ?.get(1),
            )
        val metadata = File(repository, "gradle/verification-metadata.xml").readText()
        val component =
            checkNotNull(
                Regex(
                    """<component group="com\.android\.tools\.build" name="aapt2" version="([^"]+)">([\s\S]*?)</component>""",
                ).findAll(metadata).singleOrNull { it.groupValues[1].startsWith("$agpVersion-") },
            )
        val aapt2Version = component.groupValues[1]

        assertTrue(aapt2Version.startsWith("$agpVersion-"))
        assertTrue(
            "Ubuntu CI requires the Linux aapt2 classifier checksum",
            Regex(
                """<artifact name="aapt2-${Regex.escape(aapt2Version)}-linux\.jar">\s*<sha256 value="[0-9a-f]{64}"""",
            ).containsMatchIn(component.groupValues[2]),
        )
    }
}
