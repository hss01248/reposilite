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

import com.reposilite.journalist.Journalist
import com.reposilite.journalist.Logger
import com.reposilite.maven.api.METADATA_FILE
import com.reposilite.shared.ErrorResponse
import com.reposilite.shared.notFoundError
import com.reposilite.storage.api.Location
import com.reposilite.token.AccessTokenIdentifier
import panda.std.Result

internal class ResolutionProvider(
    private val journalist: Journalist,
    private val missStatsService: MissStatsService? = null,
    private val proxyHitStatsStore: ProxyHitStatsStore? = null,
) : Journalist {

    private data class ResolveAttempt<T>(
        val result: Result<T, ErrorResponse>,
        val remote: MirrorResolution<T>?,
    )

    fun <T : Any> resolve(
        repository: Repository,
        gav: Location,
        accessToken: AccessTokenIdentifier?,
        mirrorFirst: Boolean,
        tryLocal: () -> Result<T, ErrorResponse>,
        tryRemote: (hosts: List<MirrorHost>) -> MirrorResolution<T>,
    ): Result<T, ErrorResponse> {
        val cache = repository.resolutionCache
        val authenticated = accessToken != null
        val cached = cache?.lookup(gav, authenticated)
        val isMetadata = gav.isResolutionMetadata()
        val notFoundMessage = "Cannot find '$gav' in local or remote repositories"

        if (isMetadata && cached == ResolutionCache.Origin.MissingMetadata) {
            return notFoundError(notFoundMessage)
        }

        val pinnedRemote = cached as? ResolutionCache.Origin.Remote

        // Optional classifiers (-javadoc/-sources/…) without a Remote pin: local only.
        // No pin usually means Local disk hit for pom, or cold cache — full remirror only
        // burns Sync time on packages that are rarely published upstream.
        if (gav.isOptionalClassifierArtifact() && pinnedRemote == null) {
            logger.debug("Resolution | Optional '$gav' without Remote pin — local only, skipping mirrors")
            return tryLocal().flatMapErr { notFoundError(notFoundMessage) }
        }

        val missStats = missStatsService
        if (gav.shouldTrackMissStats() && missStats != null) {
            if (missStats.shouldShortCircuit(repository.name, gav, authenticated)) {
                logger.debug("Resolution | Miss short-circuit for '$gav' — skipping mirrors")
                return tryLocal().flatMapErr { notFoundError(notFoundMessage) }
            }
            return missStats.coalesce(
                repositoryName = repository.name,
                gav = gav,
                authenticated = authenticated,
                leader = {
                    resolveWithMirrors(repository, gav, authenticated, mirrorFirst, pinnedRemote, tryLocal, tryRemote, notFoundMessage)
                },
                waiter = {
                    if (missStats.shouldShortCircuit(repository.name, gav, authenticated)) {
                        logger.debug("Resolution | Miss short-circuit (coalesced) for '$gav' — skipping mirrors")
                        tryLocal().flatMapErr { notFoundError(notFoundMessage) }
                    } else {
                        resolveWithMirrors(repository, gav, authenticated, mirrorFirst, pinnedRemote, tryLocal, tryRemote, notFoundMessage)
                    }
                },
            )
        }

        return resolveWithMirrors(repository, gav, authenticated, mirrorFirst, pinnedRemote, tryLocal, tryRemote, notFoundMessage)
    }

    private fun <T : Any> resolveWithMirrors(
        repository: Repository,
        gav: Location,
        authenticated: Boolean,
        mirrorFirst: Boolean,
        pinnedRemote: ResolutionCache.Origin.Remote?,
        tryLocal: () -> Result<T, ErrorResponse>,
        tryRemote: (hosts: List<MirrorHost>) -> MirrorResolution<T>,
        notFoundMessage: String,
    ): Result<T, ErrorResponse> {
        val cache = repository.resolutionCache
        val isMetadata = gav.isResolutionMetadata()

        val hosts = pinnedRemote
            ?.let { decision -> repository.mirrorHosts.filter { it.host == decision.host }.ifEmpty { repository.mirrorHosts } }
            ?: repository.mirrorHosts

        var attempt = when {
            mirrorFirst || (pinnedRemote != null && !repository.parallelMetadataLookup) ->
                tryRemoteFirst(tryLocal, tryRemote, hosts, notFoundMessage)
            else -> tryLocalFirst(tryLocal, tryRemote, hosts, notFoundMessage)
        }

        // Incomplete mirrors can win the pom race (e.g. gradle-plugin has .pom but not .jar).
        // If the pinned host misses a *primary* binary, drop the pin and re-probe once.
        // Do not do this for -javadoc/-sources: they are often unpublished and would 404-storm.
        if (
            cache != null &&
            pinnedRemote != null &&
            !attempt.result.isOk &&
            hosts.size < repository.mirrorHosts.size &&
            gav.shouldRetryAllMirrorsOnPinMiss()
        ) {
            logger.debug("Resolution | Pinned host '${pinnedRemote.host}' missed primary '$gav' — invalidating pin and retrying all mirrors")
            cache.invalidate(gav)
            attempt = when {
                mirrorFirst -> tryRemoteFirst(tryLocal, tryRemote, repository.mirrorHosts, notFoundMessage)
                else -> tryLocalFirst(tryLocal, tryRemote, repository.mirrorHosts, notFoundMessage)
            }
            if (attempt.result.isOk && attempt.remote is MirrorResolution.Resolved) {
                val prefix = gav.getParent()
                if (prefix != Location.empty()) {
                    cache.record(prefix, authenticated, ResolutionCache.Origin.Remote(attempt.remote.mirror.host))
                }
            }
            if (!attempt.result.isOk && gav.shouldTrackMissStats()) {
                missStatsService?.recordFinalMiss(repository.name, gav, authenticated, attempt.result.error)
            }
            return attempt.result
        }

        if (
            pinnedRemote != null &&
            !attempt.result.isOk &&
            hosts.size < repository.mirrorHosts.size &&
            !gav.shouldRetryAllMirrorsOnPinMiss()
        ) {
            logger.debug("Resolution | Pinned host '${pinnedRemote.host}' missed optional '$gav' — not retrying all mirrors")
        }

        if (cache != null && isMetadata) {
            attempt.recordTo(cache, repository.name, gav, authenticated, repository.parallelMetadataLookup)
        }

        if (!attempt.result.isOk && gav.shouldTrackMissStats()) {
            missStatsService?.recordFinalMiss(repository.name, gav, authenticated, attempt.result.error)
        }

        return attempt.result
    }

    fun invalidate(repository: Repository, gav: Location): Boolean {
        if (gav.isResolutionMetadata()) {
            repository.resolutionCache?.invalidate(gav)
            return true
        }

        return false
    }

    private fun <T : Any> tryLocalFirst(
        tryLocal: () -> Result<T, ErrorResponse>,
        tryRemote: (List<MirrorHost>) -> MirrorResolution<T>,
        hosts: List<MirrorHost>,
        notFoundMessage: String,
    ): ResolveAttempt<T> =
        tryLocal().let { local ->
            when {
                local.isOk -> ResolveAttempt(local, remote = null)
                else -> {
                    val remote = tryRemote(hosts)
                    ResolveAttempt(remote.toResult(notFoundMessage).flatMapErr { local }, remote)
                }
            }
        }

    private fun <T : Any> tryRemoteFirst(
        tryLocal: () -> Result<T, ErrorResponse>,
        tryRemote: (List<MirrorHost>) -> MirrorResolution<T>,
        hosts: List<MirrorHost>,
        notFoundMessage: String,
    ): ResolveAttempt<T> =
        when (val remote = tryRemote(hosts)) {
            is MirrorResolution.Resolved -> ResolveAttempt(Result.ok(remote.value), remote)
            else -> ResolveAttempt(tryLocal().flatMapErr { remote.toResult(notFoundMessage) }, remote)
        }

    private fun <T> ResolveAttempt<T>.recordTo(
        cache: ResolutionCache,
        repositoryName: String,
        gav: Location,
        authenticated: Boolean,
        parallelMetadataLookup: Boolean,
    ) {
        val prefix = gav.getParent()
        if (prefix == Location.empty()) {
            return
        }

        // Successful resolve but upstream actually failed — we served a local fallback.
        // Log it for visibility, but do not cache: the upstream may recover.
        if (result.isOk && remote is MirrorResolution.Failed) {
            logger.warn("Resolution | Upstream failed (${remote.error.status}: ${remote.error.message}) — served '$gav' from local fallback")
            return
        }

        // Failed resolve: only NotFound is a stable signal worth caching. Everything else is transient.
        if (!result.isOk) {
            if (remote is MirrorResolution.NotFound) {
                cache.record(prefix, authenticated, ResolutionCache.Origin.MissingMetadata)
            }
            return
        }

        // Parallel lookup retains the winning mirror for version-scoped metadata; artifact-level metadata stays Local.
        val origin = when {
            remote is MirrorResolution.Resolved &&
                gav.shouldPinRemoteOrigin() &&
                (parallelMetadataLookup || !remote.mirror.configuration.store) ->
                ResolutionCache.Origin.Remote(remote.mirror.host)
            remote is MirrorResolution.Resolved &&
                !parallelMetadataLookup &&
                !remote.mirror.configuration.store ->
                ResolutionCache.Origin.Remote(remote.mirror.host)
            else ->
                ResolutionCache.Origin.Local
        }

        cache.record(prefix, authenticated, origin)

        // Count .pom/.module Remote pin wins for proxied-host reorder (not jar remirror pins).
        if (origin is ResolutionCache.Origin.Remote && gav.shouldPinRemoteOrigin()) {
            proxyHitStatsStore?.increment(repositoryName, origin.host, System.currentTimeMillis())
        }
    }

    override fun getLogger(): Logger =
        journalist.logger

}
