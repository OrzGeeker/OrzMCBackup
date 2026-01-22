package com.jokerhub.orzmc

import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Paths
import com.jokerhub.orzmc.world.Cleaner
import com.jokerhub.orzmc.util.TestPaths
import com.jokerhub.orzmc.util.TestTmp

class OptimizerConfigParamTest {
    @ParameterizedTest
    @CsvSource(
        "false,1",
        "true,1",
        "false,2",
        "true,2"
    )
    fun `run with OptimizerConfig combinations`(removeUnknown: Boolean, parallelism: Int) {
        val input = TestPaths.world()
        val region = input.resolve("region")
        val mcaCount = Files.list(region).use { s -> s.filter { it.fileName.toString().endsWith(".mca") }.count() }
        println("region/*.mca count: $mcaCount")
        assertTrue(mcaCount > 0)
        val out = TestTmp.createTempDirectory("optimizer-config-out-")
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
        Cleaner.deleteTreeWithRetry(out, 5, 10)
    }
}
