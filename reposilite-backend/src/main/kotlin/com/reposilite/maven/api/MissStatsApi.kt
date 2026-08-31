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

package com.reposilite.maven.api

import io.javalin.openapi.JsonSchema

@JsonSchema(requireNonNulls = false)
data class MissStatsEntryResponse(
    val path: String,
    val count: Int,
    val lastMissAt: String,
    val shortCircuited: Boolean,
)

@JsonSchema(requireNonNulls = false)
data class MissStatsListResponse(
    val repository: String,
    val threshold: Int,
    val dedupeSeconds: Int,
    val retentionDays: Int,
    val entries: List<MissStatsEntryResponse>,
)

@JsonSchema(requireNonNulls = false)
data class MissStatsSettingsResponse(
    val missShortCircuitThreshold: Int,
    val missStatsDedupeSeconds: Int,
    val missStatsRetentionDays: Int,
)

@JsonSchema(requireNonNulls = false)
data class MissStatsSettingsUpdateRequest(
    val missShortCircuitThreshold: Int? = null,
    val missStatsDedupeSeconds: Int? = null,
    val missStatsRetentionDays: Int? = null,
)
