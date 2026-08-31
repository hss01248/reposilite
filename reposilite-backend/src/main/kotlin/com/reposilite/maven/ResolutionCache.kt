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

import com.reposilite.storage.api.Location
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

internal class ResolutionCache(
    private val maxEntries: Int,
    private val repositoryName: String = "",
    private val originStore: ResolutionOriginStore? = null,
) {

    private data class Key(val prefix: Location, val authenticated: Boolean)

    private class Entry(val origin: Origin) {
        val hitCount = LongAdder()
    }

    sealed interface Origin {
        data object Local : Origin
        data class Remote(val host: String) : Origin
        // Recorded when any resolution-metadata file (maven-metadata.xml, *.pom, etc.) at this exact prefix
        // is missing both locally and from every probed mirror. Does not inherit to descendants.
        data object MissingMetadata : Origin
    }

    data class ResolutionCacheEntry(val prefix: Location, val authenticated: Boolean, val origin: Origin, val hitCount: Long)

    private val entries = ConcurrentHashMap<Key, Entry>()
    private val evictionLock = Any()

    fun lookup(gav: Location, authenticated: Boolean): Origin? {
        val exactPrefix = gav.getParent()
        var prefix = exactPrefix
        while (prefix != Location.empty()) {
            val key = Key(prefix, authenticated)
            val entry = entries[key]
            if (entry != null) {
                // Negative is authoritative only at the exact prefix — it says "this metadata file returned 404",
                // not "every descendant is empty." Local/Remote pins still inherit down (routing decisions).
                if (entry.origin is Origin.MissingMetadata && prefix != exactPrefix) {
                    prefix = prefix.getParent()
                    continue
                }
                entry.hitCount.increment()
                return entry.origin
            }

            // Memory miss → durable Remote pin (if any). Local / MissingMetadata are not persisted.
            val storedHost = originStore?.findRemote(repositoryName, prefix.toString(), authenticated)
            if (storedHost != null) {
                val remote = Origin.Remote(storedHost)
                putMemory(key, remote)
                entries[key]?.hitCount?.increment()
                return remote
            }

            prefix = prefix.getParent()
        }
        return null
    }

    fun record(prefix: Location, authenticated: Boolean, origin: Origin) {
        if (prefix == Location.empty()) {
            return
        }
        val key = Key(prefix, authenticated)
        // preserve accumulated hitCount on same-origin refresh
        if (entries[key]?.origin == origin) {
            return
        }
        putMemory(key, origin)
        persist(prefix, authenticated, origin)
    }

    fun invalidate(gav: Location) {
        // Only the exact parent prefix — ancestor pins (e.g. group-level routing) remain valid after a child write.
        val prefix = gav.getParent()
        if (prefix == Location.empty()) {
            return
        }
        entries.remove(Key(prefix, authenticated = true))
        entries.remove(Key(prefix, authenticated = false))
        originStore?.deletePrefix(repositoryName, prefix.toString())
    }

    fun purge() {
        entries.clear()
        originStore?.deleteRepository(repositoryName)
    }

    fun size(): Int =
        entries.size

    fun stats(top: Int): List<ResolutionCacheEntry> =
        entries.entries.asSequence()
            .map { (key, entry) -> ResolutionCacheEntry(key.prefix, key.authenticated, entry.origin, entry.hitCount.sum()) }
            .sortedByDescending { it.hitCount }
            .take(top.coerceAtLeast(0))
            .toList()

    private fun putMemory(key: Key, origin: Origin) {
        // Size check + insert is intentionally not synchronized — maxEntries is a soft cap that may
        // transiently overshoot under concurrent writes; the next record self-heals via eviction.
        if (!entries.containsKey(key) && entries.size >= maxEntries) {
            evictDownTo(maxEntries - 1)
        }
        entries[key] = Entry(origin)
    }

    private fun persist(prefix: Location, authenticated: Boolean, origin: Origin) {
        val store = originStore ?: return
        when (origin) {
            is Origin.Remote -> store.upsertRemote(repositoryName, prefix.toString(), authenticated, origin.host)
            // Overwriting a Remote pin with Local / MissingMetadata must drop the durable row,
            // otherwise a restart would revive a stale host.
            else -> store.delete(repositoryName, prefix.toString(), authenticated)
        }
    }

    private fun evictDownTo(targetSize: Int) {
        synchronized(evictionLock) {
            while (entries.size > targetSize) {
                val victim = entries.entries.minByOrNull { it.value.hitCount.sum() } ?: return
                // Evict memory only — durable Remote pins remain and can be reloaded on next miss.
                entries.remove(victim.key, victim.value)
            }
        }
    }

}
