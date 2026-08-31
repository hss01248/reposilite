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

import com.reposilite.maven.ResolutionOriginStore
import com.reposilite.maven.api.GAV_MAX_LENGTH
import com.reposilite.maven.api.REPOSITORY_NAME_MAX_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

private val MYSQL_FAMILY_VENDORS = setOf("mysql", "mariadb", "h2")

private const val HOST_MAX_LENGTH = 2048

@Suppress("RemoveRedundantQualifierName")
internal class SqlResolutionOriginStore(
    private val database: Database,
) : ResolutionOriginStore {

    object ResolutionOriginTable : Table("maven_resolution_origin") {
        val repository = varchar("repository", REPOSITORY_NAME_MAX_LENGTH)
        val prefix = varchar("prefix", GAV_MAX_LENGTH)
        val authenticated = bool("authenticated")
        val host = varchar("host", HOST_MAX_LENGTH)

        override val primaryKey = PrimaryKey(repository, prefix, authenticated)
    }

    init {
        transaction(database) {
            SchemaUtils.create(ResolutionOriginTable)
            SchemaUtils.createMissingTablesAndColumns(ResolutionOriginTable)
        }
    }

    override fun findRemote(repository: String, prefix: String, authenticated: Boolean): String? =
        transaction(database) {
            ResolutionOriginTable
                .selectAll()
                .where {
                    (ResolutionOriginTable.repository eq repository) and
                        (ResolutionOriginTable.prefix eq prefix) and
                        (ResolutionOriginTable.authenticated eq authenticated)
                }
                .limit(1)
                .firstOrNull()
                ?.get(ResolutionOriginTable.host)
        }

    override fun upsertRemote(repository: String, prefix: String, authenticated: Boolean, host: String) {
        val truncatedHost = host.take(HOST_MAX_LENGTH)
        val truncatedPrefix = prefix.take(GAV_MAX_LENGTH)
        transaction(database) {
            val conflictKeys =
                if (database.vendor.lowercase() in MYSQL_FAMILY_VENDORS) emptyArray()
                else arrayOf(
                    ResolutionOriginTable.repository,
                    ResolutionOriginTable.prefix,
                    ResolutionOriginTable.authenticated,
                )

            ResolutionOriginTable.upsert(
                *conflictKeys,
                onUpdate = {
                    it[ResolutionOriginTable.host] = truncatedHost
                }
            ) {
                it[ResolutionOriginTable.repository] = repository
                it[ResolutionOriginTable.prefix] = truncatedPrefix
                it[ResolutionOriginTable.authenticated] = authenticated
                it[ResolutionOriginTable.host] = truncatedHost
            }
        }
    }

    override fun delete(repository: String, prefix: String, authenticated: Boolean) {
        transaction(database) {
            ResolutionOriginTable.deleteWhere {
                (ResolutionOriginTable.repository eq repository) and
                    (ResolutionOriginTable.prefix eq prefix) and
                    (ResolutionOriginTable.authenticated eq authenticated)
            }
        }
    }

    override fun deletePrefix(repository: String, prefix: String) {
        transaction(database) {
            ResolutionOriginTable.deleteWhere {
                (ResolutionOriginTable.repository eq repository) and
                    (ResolutionOriginTable.prefix eq prefix)
            }
        }
    }

    override fun deleteRepository(repository: String) {
        transaction(database) {
            ResolutionOriginTable.deleteWhere {
                ResolutionOriginTable.repository eq repository
            }
        }
    }

}
