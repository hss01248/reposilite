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

import com.reposilite.configuration.shared.SharedConfigurationFacade
import com.reposilite.maven.api.MissStatsEntryResponse
import com.reposilite.maven.api.MissStatsListResponse
import com.reposilite.maven.api.MissStatsSettingsResponse
import com.reposilite.maven.api.MissStatsSettingsUpdateRequest
import com.reposilite.maven.application.MavenSettings
import com.reposilite.shared.ErrorResponse
import com.reposilite.shared.badRequest
import com.reposilite.shared.badRequestError
import panda.std.Result
import panda.std.reactive.Reference
import java.time.Instant

class MissStatsFacade internal constructor(
    private val store: MissStatsStore,
    private val missStatsService: MissStatsService,
    private val mavenSettings: Reference<MavenSettings>,
    private val sharedConfigurationFacade: SharedConfigurationFacade,
) {

    fun getSettings(): MissStatsSettingsResponse {
        val settings = mavenSettings.get()
        return MissStatsSettingsResponse(
            missShortCircuitThreshold = settings.missShortCircuitThreshold,
            missStatsDedupeSeconds = settings.missStatsDedupeSeconds,
            missStatsRetentionDays = settings.missStatsRetentionDays,
        )
    }

    fun updateSettings(request: MissStatsSettingsUpdateRequest): Result<MissStatsSettingsResponse, ErrorResponse> {
        val current = mavenSettings.get()
        val updated = current.copy(
            missShortCircuitThreshold = request.missShortCircuitThreshold ?: current.missShortCircuitThreshold,
            missStatsDedupeSeconds = request.missStatsDedupeSeconds ?: current.missStatsDedupeSeconds,
            missStatsRetentionDays = request.missStatsRetentionDays ?: current.missStatsRetentionDays,
        )
        if (updated.missShortCircuitThreshold < 1) {
            return badRequestError("missShortCircuitThreshold must be >= 1")
        }
        return sharedConfigurationFacade
            .updateSharedSettings(MAVEN_DOMAIN, updated)
            ?.map { getSettings() }
            ?.mapErr { badRequest(it.toString()) }
            ?: badRequestError("Maven settings domain not found")
    }

    fun listEntries(
        repository: String,
        showAll: Boolean,
        limit: Int,
        offset: Int,
    ): MissStatsListResponse {
        val settings = mavenSettings.get()
        val threshold = settings.missShortCircuitThreshold.coerceAtLeast(1)
        val minCount = if (showAll) 1 else threshold
        val entries = store.listEntries(repository, minCount, limit, offset).map { row ->
            MissStatsEntryResponse(
                path = row.path,
                count = row.count,
                lastMissAt = Instant.ofEpochMilli(row.lastMissAtMillis).toString(),
                shortCircuited = row.count >= threshold,
            )
        }
        return MissStatsListResponse(
            repository = repository,
            threshold = threshold,
            dedupeSeconds = settings.missStatsDedupeSeconds,
            retentionDays = settings.missStatsRetentionDays,
            entries = entries,
        )
    }

    fun resetEntry(repository: String, path: String) {
        missStatsService.invalidate(repository, path)
    }

    fun clearRepository(repository: String) {
        missStatsService.invalidateRepository(repository)
    }

    companion object {
        const val MAVEN_DOMAIN = "maven"
    }
}
