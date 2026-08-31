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

data class MissStatsRow(
    val path: String,
    val count: Int,
    val lastMissAtMillis: Long,
)

/** Durable store for final-404 miss counts (per repository + artifact path). */
interface MissStatsStore {

    fun getCount(repository: String, path: String): Int?

    /** Returns count after record (may be unchanged inside dedupe window). */
    fun recordMiss(repository: String, path: String, nowMillis: Long, dedupeSeconds: Int): Int

    fun reset(repository: String, path: String)

    fun clearRepository(repository: String)

    fun listEntries(
        repository: String,
        minCount: Int,
        limit: Int,
        offset: Int,
    ): List<MissStatsRow>

    fun purgeStale(repository: String, threshold: Int, retentionDays: Int, nowMillis: Long)
}
