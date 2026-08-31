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

import com.reposilite.auth.AuthenticationFacade
import com.reposilite.journalist.Journalist
import com.reposilite.maven.application.MirroredRepositorySettings
import com.reposilite.maven.application.RepositorySettings
import com.reposilite.shared.http.RemoteClientProvider
import com.reposilite.shared.http.createHttpProxy
import com.reposilite.status.FailureFacade
import com.reposilite.storage.StorageFacade
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

internal class RepositoryFactory(
    private val journalist: Journalist,
    private val workingDirectory: Path,
    private val authenticationFacade: AuthenticationFacade,
    private val remoteClientProvider: RemoteClientProvider,
    private val repositoryService: RepositoryService,
    private val failureFacade: FailureFacade,
    private val storageFacade: StorageFacade,
    private val repositoriesNames: Collection<String>,
    private val resolutionOriginStore: ResolutionOriginStore? = null,
    private val proxyHitStatsStore: ProxyHitStatsStore? = null,
) {

    private val repositoriesDirectory = Paths.get("repositories")

    fun createRepository(repositoryName: String, configuration: RepositorySettings): Repository {
        val name = repositoryName.ifEmpty { UUID.randomUUID().toString() }
        val hitCounts = proxyHitStatsStore?.getCounts(name) ?: emptyMap()
        val mirrorHosts = configuration.proxied
            .mapNotNull { createMirroredHostConfiguration(it) }
            .sortedByDescending { hitCounts[it.host] ?: 0 }

        if (mirrorHosts.isNotEmpty() && hitCounts.isNotEmpty()) {
            val preview = mirrorHosts.take(5).joinToString { host ->
                "${host.host}(${hitCounts[host.host] ?: 0})"
            }
            journalist.logger.info("Repository '$name' | proxied order by pom-hit: $preview")
        }

        return Repository(
            name = name,
            visibility = configuration.visibility,
            redeployment = configuration.redeployment,
            preserveSnapshots = configuration.preserveSnapshots,
            mirrorHosts = mirrorHosts,
            storageProvider =
                storageFacade
                    .createStorageProvider(
                        journalist = journalist,
                        failureFacade = failureFacade,
                        workingDirectory = workingDirectory.resolve(repositoriesDirectory),
                        repository = repositoryName,
                        storageSettings = configuration.storageProvider,
                    )
                    ?: throw IllegalArgumentException("Unknown storage provider '${configuration.storageProvider.type}'"),
            storagePolicy = configuration.storagePolicy,
            metadataMaxAgeInSeconds = configuration.metadataMaxAge,
            parallelMetadataLookup = configuration.parallelMetadataLookup,
            metadataRaceWindowMs = configuration.metadataRaceWindowMs,
            resolutionCacheMaxEntries = configuration.resolutionCacheMaxEntries,
            resolutionOriginStore = resolutionOriginStore,
        )
    }

    private fun createMirroredHostConfiguration(configurationSource: MirroredRepositorySettings): MirrorHost? {
        val name = configurationSource.reference.trim()

        val host = when {
            name.isEmpty() -> {
                failureFacade.logger.warn("Empty reference value of mirrored repository found, skipping configuration entry.")
                return null
            }
            name.endsWith("/") -> name.substring(0, name.length - 1)
            else -> name
        }

        val remoteClient = when {
            repositoriesNames.contains(host) ->
                RepositoryLoopbackClient(
                    authenticationFacade = authenticationFacade,
                    repositoryService = repositoryService,
                    repositoryName = host
                )
            else ->
                configurationSource.httpProxy
                    .takeIf { it.isNotEmpty() }
                    ?.let { createHttpProxy(it) }
                    ?.let { remoteClientProvider.createClient(it) }
                    ?: remoteClientProvider.defaultClient
        }

        return MirrorHost(host, configurationSource, remoteClient)
    }

}
