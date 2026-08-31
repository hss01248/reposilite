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

package com.reposilite.maven.infrastructure

import com.reposilite.maven.MissStatsRow
import com.reposilite.maven.MissStatsStore
import com.reposilite.maven.api.GAV_MAX_LENGTH
import com.reposilite.maven.api.REPOSITORY_NAME_MAX_LENGTH
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private val MYSQL_FAMILY_VENDORS = setOf("mysql", "mariadb", "h2")

@Suppress("RemoveRedundantQualifierName")
internal class SqlMissStatsStore(
    private val database: Database,
) : MissStatsStore {

    object MissStatsTable : Table("maven_miss_stats") {
        val repository = varchar("repository", REPOSITORY_NAME_MAX_LENGTH)
        val path = varchar("path", GAV_MAX_LENGTH)
        val count = integer("count")
        val lastMissAt = long("last_miss_at")

        override val primaryKey = PrimaryKey(repository, path)
    }

    init {
        transaction(database) {
            SchemaUtils.create(MissStatsTable)
            SchemaUtils.createMissingTablesAndColumns(MissStatsTable)
        }
    }

    override fun getCount(repository: String, path: String): Int? =
        transaction(database) {
            MissStatsTable
                .selectAll()
                .where {
                    (MissStatsTable.repository eq repository) and (MissStatsTable.path eq path.take(GAV_MAX_LENGTH))
                }
                .limit(1)
                .firstOrNull()
                ?.get(MissStatsTable.count)
        }

    override fun recordMiss(repository: String, path: String, nowMillis: Long, dedupeSeconds: Int): Int {
        val truncatedPath = path.take(GAV_MAX_LENGTH)
        return transaction(database) {
            val row = MissStatsTable
                .selectAll()
                .where {
                    (MissStatsTable.repository eq repository) and (MissStatsTable.path eq truncatedPath)
                }
                .limit(1)
                .firstOrNull()

            if (row == null) {
                MissStatsTable.insert {
                    it[MissStatsTable.repository] = repository
                    it[MissStatsTable.path] = truncatedPath
                    it[count] = 1
                    it[lastMissAt] = nowMillis
                }
                return@transaction 1
            }

            val existingCount = row[MissStatsTable.count]
            val lastAt = row[MissStatsTable.lastMissAt]
            val dedupeMillis = dedupeSeconds.coerceAtLeast(0) * 1000L
            val newCount =
                if (dedupeMillis > 0 && nowMillis - lastAt < dedupeMillis) {
                    existingCount
                } else {
                    existingCount + 1
                }

            MissStatsTable.update({
                (MissStatsTable.repository eq repository) and (MissStatsTable.path eq truncatedPath)
            }) {
                it[count] = newCount
                it[lastMissAt] = nowMillis
            }
            newCount
        }
    }

    override fun reset(repository: String, path: String) {
        transaction(database) {
            MissStatsTable.deleteWhere {
                (MissStatsTable.repository eq repository) and (MissStatsTable.path eq path.take(GAV_MAX_LENGTH))
            }
        }
    }

    override fun clearRepository(repository: String) {
        transaction(database) {
            MissStatsTable.deleteWhere {
                MissStatsTable.repository eq repository
            }
        }
    }

    override fun listEntries(
        repository: String,
        minCount: Int,
        limit: Int,
        offset: Int,
    ): List<MissStatsRow> =
        transaction(database) {
            MissStatsTable
                .selectAll()
                .where {
                    (MissStatsTable.repository eq repository) and (MissStatsTable.count greaterEq minCount.coerceAtLeast(0))
                }
                .orderBy(MissStatsTable.count to SortOrder.DESC, MissStatsTable.lastMissAt to SortOrder.DESC)
                .limit(limit.coerceIn(1, 500))
                .offset(offset.coerceAtLeast(0).toLong())
                .map { row ->
                    MissStatsRow(
                        path = row[MissStatsTable.path],
                        count = row[MissStatsTable.count],
                        lastMissAtMillis = row[MissStatsTable.lastMissAt],
                    )
                }
        }

    override fun purgeStale(repository: String, threshold: Int, retentionDays: Int, nowMillis: Long) {
        if (retentionDays <= 0 || threshold <= 0) {
            return
        }
        val cutoff = nowMillis - retentionDays.coerceAtLeast(1) * 86_400_000L
        transaction(database) {
            MissStatsTable.deleteWhere {
                (MissStatsTable.repository eq repository) and
                    (MissStatsTable.count less threshold) and
                    (MissStatsTable.lastMissAt less cutoff)
            }
        }
    }
}
