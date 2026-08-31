/*
 * Copyright (c) 2020-2026 dzikoysk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reposilite.maven

import com.reposilite.journalist.Logger
import com.reposilite.maven.application.MavenSettings
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Keeps mitm rewrite routes aligned with Reposilite proxied mirrors by shelling out to
 * `mitm/sync_routes.py` (single source of truth for the routes JSON).
 *
 * Layout assumption: workingDirectory is `<repo>/data`.
 */
internal object MitmRoutesAligner {

    fun alignFromSharedFile(workingDirectory: Path, logger: Logger, restartMitm: Boolean = true) {
        runSync(
            workingDirectory = workingDirectory,
            logger = logger,
            restartMitm = restartMitm,
            stdinRefsJson = null,
        )
    }

    fun alignFromSettings(workingDirectory: Path, settings: MavenSettings, logger: Logger, restartMitm: Boolean = true) {
        val refs = settings.repositories
            .asSequence()
            .flatMap { it.proxied.asSequence() }
            .map { it.reference.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val refsJson = refs.joinToString(prefix = "[", postfix = "]") { ref ->
            "\"${ref.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        runSync(
            workingDirectory = workingDirectory,
            logger = logger,
            restartMitm = restartMitm,
            stdinRefsJson = refsJson,
        )
    }

    private fun runSync(
        workingDirectory: Path,
        logger: Logger,
        restartMitm: Boolean,
        stdinRefsJson: String?,
    ) {
        val root = workingDirectory.toAbsolutePath().normalize().parent ?: return
        val script = root.resolve("mitm/sync_routes.py").toFile()
        if (!script.isFile) {
            logger.debug("Mitm routes align skipped — script not found: $script")
            return
        }

        val python = findPython() ?: run {
            logger.warn("Mitm routes align skipped — python3/python not found")
            return
        }

        val command = mutableListOf(
            python,
            script.absolutePath,
            "--root",
            root.toString(),
        )
        if (!restartMitm) {
            command.add("--no-restart")
        }
        if (stdinRefsJson != null) {
            command.add("--stdin-refs")
        }

        try {
            val process = ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start()

            if (stdinRefsJson != null) {
                process.outputStream.use { out ->
                    out.write(stdinRefsJson.toByteArray(StandardCharsets.UTF_8))
                }
            }

            val finished = process.waitFor(30, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText().trim()
            if (!finished) {
                process.destroyForcibly()
                logger.warn("Mitm routes align timed out")
                return
            }
            if (process.exitValue() != 0) {
                logger.warn("Mitm routes align failed (exit=${process.exitValue()}): $output")
                return
            }
            if (output.isNotEmpty()) {
                logger.info("Mitm routes: $output")
            }
        } catch (exception: Exception) {
            logger.warn("Mitm routes align error: ${exception.message}")
        }
    }

    private fun findPython(): String? {
        for (candidate in listOf("python3", "python")) {
            try {
                val proc = ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start()
                if (proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0) {
                    return candidate
                }
            } catch (_: Exception) {
                // try next
            }
        }
        return null
    }
}
