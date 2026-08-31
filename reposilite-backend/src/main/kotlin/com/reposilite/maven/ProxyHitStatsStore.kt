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

/**
 * Durable counters for Remote pin wins on .pom/.module (per repository + proxied host URL).
 * Used to reorder [MirrorHost] lists at repository rebuild time.
 */
interface ProxyHitStatsStore {

    fun getCount(repository: String, host: String): Int

    /** All hosts with non-zero counts for [repository]. Missing hosts imply 0. */
    fun getCounts(repository: String): Map<String, Int>

    fun increment(repository: String, host: String, nowMillis: Long): Int
}
