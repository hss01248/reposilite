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

import com.reposilite.maven.api.METADATA_FILE
import com.reposilite.storage.api.Location

private val VERSION_DIRECTORY = Regex("""^\d[\w.\-]*$""")

internal fun Location.isResolutionMetadata(): Boolean =
    getSimpleName().let { name ->
        when {
            name.endsWith(".pom") -> true
            name.endsWith(".module") -> true
            name == METADATA_FILE -> true
            name.endsWith(".xml") -> name == "ivy.xml" || name.startsWith("ivy-")
            else -> false
        }
    }

internal fun Location.isVersionDirectory(): Boolean {
    val segment = getParent().getSimpleName()
    return segment.contains("-SNAPSHOT") || VERSION_DIRECTORY.matches(segment)
}

internal fun Location.isArtifactLevelMetadata(): Boolean =
    getSimpleName() == METADATA_FILE && !isVersionDirectory()

internal fun Location.isSnapshotMetadata(): Boolean =
    getSimpleName() == METADATA_FILE && getParent().getSimpleName().contains("-SNAPSHOT")

internal fun Location.shouldPinRemoteOrigin(): Boolean =
    when {
        getSimpleName().endsWith(".pom") || getSimpleName().endsWith(".module") -> true
        isArtifactLevelMetadata() || isSnapshotMetadata() -> false
        else -> false
    }

/**
 * Optional classifiers that are frequently unpublished.
 * Full mirror scans for these create 404 storms with little benefit.
 */
internal fun Location.isOptionalClassifierArtifact(): Boolean {
    var name = getSimpleName()
    for (suffix in listOf(".sha512", ".sha256", ".sha1", ".md5", ".asc")) {
        if (name.endsWith(suffix)) {
            name = name.removeSuffix(suffix)
            break
        }
    }
    return name.endsWith("-javadoc.jar") ||
        name.endsWith("-sources.jar") ||
        name.endsWith("-source.jar") ||
        name.endsWith("-tests.jar") ||
        name.endsWith("-test-fixtures.jar")
}

/**
 * Pin-miss full remirror is only for primary binaries (and their checksums).
 * Optional classifiers like -javadoc / -sources are usually unpublished — scanning every
 * upstream for them only creates 404 storms after a successful pom pin.
 */
internal fun Location.shouldTrackMissStats(): Boolean {
    if (isOptionalClassifierArtifact()) {
        return false
    }
    val name = getSimpleName()
    if (name.endsWith(".sha512") || name.endsWith(".sha256") || name.endsWith(".sha1") || name.endsWith(".md5") || name.endsWith(".asc")) {
        return false
    }
    return name.endsWith(".jar") ||
        name.endsWith(".aar") ||
        name.endsWith(".war") ||
        name.endsWith(".pom") ||
        name.endsWith(".module")
}

internal fun Location.shouldRetryAllMirrorsOnPinMiss(): Boolean {
    if (isOptionalClassifierArtifact()) {
        return false
    }
    var name = getSimpleName()
    for (suffix in listOf(".sha512", ".sha256", ".sha1", ".md5", ".asc")) {
        if (name.endsWith(suffix)) {
            name = name.removeSuffix(suffix)
            break
        }
    }
    return name.endsWith(".jar") || name.endsWith(".aar") || name.endsWith(".war")
}

internal enum class ParallelLookupMode {
    SERIAL,
    FIRST_OK,
    PRIORITY_RACE,
}

internal fun parallelLookupMode(repository: Repository, gav: Location): ParallelLookupMode =
    when {
        !repository.parallelMetadataLookup || !gav.isResolutionMetadata() -> ParallelLookupMode.SERIAL
        gav.isArtifactLevelMetadata() || gav.isSnapshotMetadata() -> ParallelLookupMode.PRIORITY_RACE
        else -> ParallelLookupMode.FIRST_OK
    }
