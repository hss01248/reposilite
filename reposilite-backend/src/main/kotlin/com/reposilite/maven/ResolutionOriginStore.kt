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
 * Durable store for [ResolutionCache.Origin.Remote] pins only.
 * [ResolutionCache.Origin.Local] / [ResolutionCache.Origin.MissingMetadata] stay memory-only.
 */
internal interface ResolutionOriginStore {

    fun findRemote(repository: String, prefix: String, authenticated: Boolean): String?

    fun upsertRemote(repository: String, prefix: String, authenticated: Boolean, host: String)

    /** Remove one auth-scoped pin. */
    fun delete(repository: String, prefix: String, authenticated: Boolean)

    /** Remove both anon and auth pins for a version prefix (e.g. invalidate). */
    fun deletePrefix(repository: String, prefix: String)

    fun deleteRepository(repository: String)

}
