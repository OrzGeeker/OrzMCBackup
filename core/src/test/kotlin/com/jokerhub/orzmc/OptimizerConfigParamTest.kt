package com.jokerhub.orzmc

import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Paths

class OptimizerConfigParamTest {
    @ParameterizedTest
    @CsvSource(
        "false,1",
        "true,1",
        "false,2",
        "true,2"
    )
    fun `run with OptimizerConfig combinations`(removeUnknown: Boolean, parallelism: Int) {
        val url = this::class.java.classLoader.getResource("Fixtures/world")
        assertTrue(url != null)
        val input = Paths.get(url!!.toURI())
        val out = Files.createTempDirectory("optimizer-config-out-")
        val events = mutableListOf<ProgressEvent>()
        val config = OptimizerConfig(
            input = input,
            output = out,
            inhabitedThresholdSeconds = 0,
            removeUnknown = removeUnknown,
            progressMode = ProgressMode.Off,
            zipOutput = false,
            inPlace = false,
            force = true,
            strict = false,
            progressInterval = 100,
            onProgress = null,
            parallelism = parallelism,
            progressSink = CallbackProgressSink { e -> events.add(e) },
            reportSink = null
        )
        val report = Optimizer.run(config)
        assertTrue(events.any { it.stage == ProgressStage.Done })
        assertTrue(report.processedChunks > 0)
        Files.walk(out).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
