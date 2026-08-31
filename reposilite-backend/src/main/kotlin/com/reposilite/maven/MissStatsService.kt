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

import com.reposilite.maven.application.MavenSettings
import com.reposilite.shared.ErrorResponse
import com.reposilite.storage.api.Location
import panda.std.reactive.Reference
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent miss counts (SQLite) drive long-lived short-circuit after [MavenSettings.missShortCircuitThreshold].
 * In-memory negative cache + single-flight cut the short-window n×m upstream 404 storm:
 * - after the first confirmed final 404, further requests within the dedupe window skip mirrors
 * - concurrent identical probes coalesce so only one leader hits remotes
 */
internal class MissStatsService(
    private val store: MissStatsStore,
    private val mavenSettings: Reference<MavenSettings>,
    private val clock: Clock,
) {

    private data class Key(
        val repository: String,
        val path: String,
        val authenticated: Boolean,
    )

    private val negativeCache = ConcurrentHashMap<Key, Long>()
    private val inFlightProbes = ConcurrentHashMap<Key, CompletableFuture<Unit>>()

    fun shouldShortCircuit(repositoryName: String, gav: Location, authenticated: Boolean): Boolean {
        val path = gav.toString()
        val key = Key(repositoryName, path, authenticated)
        val now = clock.millis()
        val cachedUntil = negativeCache[key]
        if (cachedUntil != null) {
            if (cachedUntil > now) {
                return true
            }
            negativeCache.remove(key, cachedUntil)
        }
        val threshold = mavenSettings.get().missShortCircuitThreshold
        if (threshold <= 0) {
            return false
        }
        val count = store.getCount(repositoryName, path) ?: return false
        return count >= threshold
    }

    fun recordFinalMiss(repositoryName: String, gav: Location, authenticated: Boolean, finalError: ErrorResponse) {
        if (finalError.status != 404) {
            return
        }
        val settings = mavenSettings.get()
        val path = gav.toString()
        val now = clock.millis()
        store.recordMiss(
            repository = repositoryName,
            path = path,
            nowMillis = now,
            dedupeSeconds = settings.missStatsDedupeSeconds,
        )
        val ttlSeconds = settings.missStatsDedupeSeconds.coerceAtLeast(0)
        if (ttlSeconds > 0) {
            negativeCache[Key(repositoryName, path, authenticated)] = now + ttlSeconds * 1000L
        }
        store.purgeStale(
            repository = repositoryName,
            threshold = settings.missShortCircuitThreshold,
            retentionDays = settings.missStatsRetentionDays,
            nowMillis = now,
        )
    }

    fun invalidate(repositoryName: String, path: String) {
        negativeCache.keys.removeIf { it.repository == repositoryName && it.path == path }
        store.reset(repositoryName, path)
    }

    fun invalidateRepository(repositoryName: String) {
        negativeCache.keys.removeIf { it.repository == repositoryName }
        store.clearRepository(repositoryName)
    }

    /**
     * Only one caller runs [leader]; waiters block until the leader finishes, then run [waiter]
     * (typically: re-check short-circuit / local-only after a miss was recorded).
     */
    fun <T> coalesce(repositoryName: String, gav: Location, authenticated: Boolean, leader: () -> T, waiter: () -> T): T {
        val key = Key(repositoryName, gav.toString(), authenticated)
        var isLeader = false
        val future = inFlightProbes.computeIfAbsent(key) {
            isLeader = true
            CompletableFuture()
        }
        if (!isLeader) {
            future.join()
            return waiter()
        }
        try {
            return leader()
        } finally {
            future.complete(Unit)
            inFlightProbes.remove(key, future)
        }
    }
}
