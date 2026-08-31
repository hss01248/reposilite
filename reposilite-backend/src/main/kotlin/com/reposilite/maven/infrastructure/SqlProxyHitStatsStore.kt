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

import com.reposilite.maven.ProxyHitStatsStore
import com.reposilite.maven.api.REPOSITORY_NAME_MAX_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private const val HOST_MAX_LENGTH = 2048

@Suppress("RemoveRedundantQualifierName")
internal class SqlProxyHitStatsStore(
    private val database: Database,
) : ProxyHitStatsStore {

    object ProxyHitStatsTable : Table("maven_proxy_hit_stats") {
        val repository = varchar("repository", REPOSITORY_NAME_MAX_LENGTH)
        val host = varchar("host", HOST_MAX_LENGTH)
        val count = integer("count")
        val lastHitAt = long("last_hit_at")

        override val primaryKey = PrimaryKey(repository, host)
    }

    init {
        transaction(database) {
            SchemaUtils.create(ProxyHitStatsTable)
            SchemaUtils.createMissingTablesAndColumns(ProxyHitStatsTable)
        }
    }

    override fun getCount(repository: String, host: String): Int =
        transaction(database) {
            ProxyHitStatsTable
                .selectAll()
                .where {
                    (ProxyHitStatsTable.repository eq repository) and
                        (ProxyHitStatsTable.host eq host.take(HOST_MAX_LENGTH))
                }
                .limit(1)
                .firstOrNull()
                ?.get(ProxyHitStatsTable.count)
                ?: 0
        }

    override fun getCounts(repository: String): Map<String, Int> =
        transaction(database) {
            ProxyHitStatsTable
                .selectAll()
                .where { ProxyHitStatsTable.repository eq repository }
                .associate { row -> row[ProxyHitStatsTable.host] to row[ProxyHitStatsTable.count] }
        }

    override fun increment(repository: String, host: String, nowMillis: Long): Int {
        val truncatedHost = host.take(HOST_MAX_LENGTH)
        return transaction(database) {
            val row = ProxyHitStatsTable
                .selectAll()
                .where {
                    (ProxyHitStatsTable.repository eq repository) and (ProxyHitStatsTable.host eq truncatedHost)
                }
                .limit(1)
                .firstOrNull()

            if (row == null) {
                ProxyHitStatsTable.insert {
                    it[ProxyHitStatsTable.repository] = repository
                    it[ProxyHitStatsTable.host] = truncatedHost
                    it[count] = 1
                    it[lastHitAt] = nowMillis
                }
                return@transaction 1
            }

            val newCount = row[ProxyHitStatsTable.count] + 1
            ProxyHitStatsTable.update({
                (ProxyHitStatsTable.repository eq repository) and (ProxyHitStatsTable.host eq truncatedHost)
            }) {
                it[count] = newCount
                it[lastHitAt] = nowMillis
            }
            newCount
        }
    }
}
