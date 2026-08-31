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

import com.reposilite.maven.MissStatsFacade
import com.reposilite.maven.api.MissStatsListResponse
import com.reposilite.maven.api.MissStatsSettingsResponse
import com.reposilite.maven.api.MissStatsSettingsUpdateRequest
import com.reposilite.shared.ErrorResponse
import com.reposilite.shared.badRequestError
import com.reposilite.web.api.ReposiliteRoute
import com.reposilite.web.api.ReposiliteRoutes
import io.javalin.community.routing.Route.DELETE
import io.javalin.community.routing.Route.GET
import io.javalin.community.routing.Route.PUT
import io.javalin.openapi.HttpMethod
import io.javalin.openapi.OpenApi
import io.javalin.openapi.OpenApiContent
import io.javalin.openapi.OpenApiParam
import io.javalin.openapi.OpenApiResponse
import panda.std.asSuccess

internal class MissStatsEndpoint(
    private val missStatsFacade: MissStatsFacade,
) : ReposiliteRoutes() {

    @OpenApi(
        tags = ["Maven"],
        path = "/api/maven/miss-stats",
        methods = [HttpMethod.GET],
        queryParams = [
            OpenApiParam(name = "repository", required = true),
            OpenApiParam(name = "showAll", required = false),
            OpenApiParam(name = "limit", required = false),
            OpenApiParam(name = "offset", required = false),
        ],
        responses = [
            OpenApiResponse(status = "200", content = [OpenApiContent(from = MissStatsListResponse::class)]),
        ],
    )
    private val listMissStats = ReposiliteRoute<MissStatsListResponse>("/api/maven/miss-stats", GET) {
        managerOnly {
            val repository = ctx.queryParam("repository")
            if (repository == null) {
                response = badRequestError("repository is required")
                return@managerOnly
            }
            val showAll = ctx.queryParam("showAll")?.toBooleanStrictOrNull() ?: false
            val limit = ctx.queryParam("limit")?.toIntOrNull()?.coerceIn(1, 500) ?: 100
            val offset = ctx.queryParam("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            response = missStatsFacade.listEntries(repository, showAll, limit, offset).asSuccess()
        }
    }

    @OpenApi(
        tags = ["Maven"],
        path = "/api/maven/miss-stats/settings",
        methods = [HttpMethod.GET],
        responses = [
            OpenApiResponse(status = "200", content = [OpenApiContent(from = MissStatsSettingsResponse::class)]),
        ],
    )
    private val getSettings = ReposiliteRoute<MissStatsSettingsResponse>("/api/maven/miss-stats/settings", GET) {
        managerOnly {
            response = missStatsFacade.getSettings().asSuccess()
        }
    }

    @OpenApi(
        tags = ["Maven"],
        path = "/api/maven/miss-stats/settings",
        methods = [HttpMethod.PUT],
        responses = [
            OpenApiResponse(status = "200", content = [OpenApiContent(from = MissStatsSettingsResponse::class)]),
        ],
    )
    private val updateSettings = ReposiliteRoute<MissStatsSettingsResponse>("/api/maven/miss-stats/settings", PUT) {
        managerOnly {
            response = missStatsFacade.updateSettings(ctx.bodyAsClass(MissStatsSettingsUpdateRequest::class.java))
        }
    }

    @OpenApi(
        tags = ["Maven"],
        path = "/api/maven/miss-stats/entry",
        methods = [HttpMethod.DELETE],
        queryParams = [
            OpenApiParam(name = "repository", required = true),
            OpenApiParam(name = "path", required = true),
        ],
    )
    private val resetEntry = ReposiliteRoute<String>("/api/maven/miss-stats/entry", DELETE) {
        managerOnly {
            val repository = ctx.queryParam("repository")
            val path = ctx.queryParam("path")
            response = when {
                repository == null -> badRequestError("repository is required")
                path == null -> badRequestError("path is required")
                else -> {
                    missStatsFacade.resetEntry(repository, path)
                    "OK".asSuccess()
                }
            }
        }
    }

    @OpenApi(
        tags = ["Maven"],
        path = "/api/maven/miss-stats/repository/{repository}",
        methods = [HttpMethod.DELETE],
        pathParams = [
            OpenApiParam(name = "repository", required = true),
        ],
    )
    private val clearRepository = ReposiliteRoute<String>("/api/maven/miss-stats/repository/{repository}", DELETE) {
        managerOnly {
            missStatsFacade.clearRepository(requireParameter("repository"))
            response = "OK".asSuccess()
        }
    }

    override val routes = routes(listMissStats, getSettings, updateSettings, resetEntry, clearRepository)
}
