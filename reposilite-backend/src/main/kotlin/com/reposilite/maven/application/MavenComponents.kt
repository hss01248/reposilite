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

package com.reposilite.maven.application

import com.reposilite.auth.AuthenticationFacade
import com.reposilite.configuration.shared.SharedConfigurationFacade
import com.reposilite.frontend.application.FrontendSettings
import com.reposilite.journalist.Journalist
import com.reposilite.maven.LatestService
import com.reposilite.maven.MavenFacade
import com.reposilite.maven.MetadataService
import com.reposilite.maven.MirrorService
import com.reposilite.maven.MissStatsFacade
import com.reposilite.maven.MissStatsService
import com.reposilite.maven.MissStatsStore
import com.reposilite.maven.ProxyHitStatsStore
import com.reposilite.maven.ResolutionOriginStore
import com.reposilite.maven.ResolutionProvider
import com.reposilite.maven.RepositoryProvider
import com.reposilite.maven.RepositorySecurityProvider
import com.reposilite.maven.infrastructure.SqlMissStatsStore
import com.reposilite.maven.infrastructure.SqlProxyHitStatsStore
import com.reposilite.maven.infrastructure.SqlResolutionOriginStore
import com.reposilite.plugin.Extensions
import com.reposilite.plugin.api.PluginComponents
import com.reposilite.shared.http.RemoteClientProvider
import com.reposilite.statistics.StatisticsFacade
import com.reposilite.status.FailureFacade
import com.reposilite.storage.StorageFacade
import com.reposilite.token.AccessTokenFacade
import org.jetbrains.exposed.v1.jdbc.Database
import panda.std.reactive.Reference
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ExecutorService

internal class MavenComponents(
    private val clock: Clock,
    private val workingDirectory: Path,
    private val journalist: Journalist,
    private val extensions: Extensions,
    private val remoteClientProvider: RemoteClientProvider,
    private val failureFacade: FailureFacade,
    private val storageFacade: StorageFacade,
    private val authenticationFacade: AuthenticationFacade,
    private val accessTokenFacade: AccessTokenFacade,
    private val statisticsFacade: StatisticsFacade,
    private val ioService: ExecutorService,
    private val mavenSettings: Reference<MavenSettings>,
    private val frontendSettings: Reference<FrontendSettings>,
    private val sharedConfigurationFacade: SharedConfigurationFacade? = null,
    private val database: Database? = null,
) : PluginComponents {

    private fun securityProvider(): RepositorySecurityProvider =
        RepositorySecurityProvider(accessTokenFacade)

    private fun metadataService(): MetadataService =
        MetadataService(securityProvider())

    private fun mirrorService(): MirrorService =
        MirrorService(
            journalist = journalist,
            failureFacade = failureFacade,
            clock = clock,
            ioService = ioService,
        )

    private val sharedMissStatsStore: MissStatsStore? by lazy {
        database?.let { SqlMissStatsStore(it) }
    }

    private val sharedMissStatsService: MissStatsService? by lazy {
        sharedMissStatsStore?.let { MissStatsService(it, mavenSettings, clock) }
    }

    private val sharedProxyHitStatsStore: ProxyHitStatsStore? by lazy {
        database?.let { SqlProxyHitStatsStore(it) }
    }

    private fun resolutionProvider(): ResolutionProvider =
        ResolutionProvider(journalist, sharedMissStatsService, sharedProxyHitStatsStore)

    private fun resolutionOriginStore(): ResolutionOriginStore? =
        database?.let { SqlResolutionOriginStore(it) }

    private fun buildMissStatsFacade(): MissStatsFacade? {
        val store = sharedMissStatsStore ?: return null
        val service = sharedMissStatsService ?: return null
        val shared = sharedConfigurationFacade ?: return null
        return MissStatsFacade(store, service, mavenSettings, shared)
    }

    private fun repositoryProvider(
        mirrorService: MirrorService = mirrorService(),
        resolutionProvider: ResolutionProvider = resolutionProvider(),
        securityProvider: RepositorySecurityProvider = securityProvider(),
        resolutionOriginStore: ResolutionOriginStore? = resolutionOriginStore(),
        proxyHitStatsStore: ProxyHitStatsStore? = sharedProxyHitStatsStore,
    ): RepositoryProvider =
        RepositoryProvider(
            journalist = journalist,
            workingDirectory = workingDirectory,
            remoteClientProvider = remoteClientProvider,
            authenticationFacade = authenticationFacade,
            failureFacade = failureFacade,
            storageFacade = storageFacade,
            mirrorService = mirrorService,
            resolutionProvider = resolutionProvider,
            statisticsFacade = statisticsFacade,
            extensions = extensions,
            repositorySecurityProvider = securityProvider,
            repositoriesSource = mavenSettings.computed { it.repositories },
            resolutionOriginStore = resolutionOriginStore,
            proxyHitStatsStore = proxyHitStatsStore,
        )

    private fun latestService(): LatestService =
        LatestService(frontendSettings.computed { it.id })

    fun mavenFacade(
        securityProvider: RepositorySecurityProvider = securityProvider(),
        metadataService: MetadataService = metadataService(),
        latestService: LatestService = latestService(),
        repositoryProvider: RepositoryProvider = repositoryProvider(),
    ): MavenFacade =
        MavenFacade(
            journalist = journalist,
            repositorySecurityProvider = securityProvider,
            repositoryProvider = repositoryProvider,
            metadataService = metadataService,
            latestService = latestService
        )

    fun missStatsFacade(): MissStatsFacade? = buildMissStatsFacade()

}
