/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import freemarker.cache.ClassTemplateLoader
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.json.serializer.KotlinxSerializer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.features.CallLogging
import io.ktor.features.DefaultHeaders
import io.ktor.freemarker.FreeMarker
import io.ktor.freemarker.FreeMarkerContent
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun Application.module() {
    val locationAsync = locationAsync()

    install(DefaultHeaders)
    install(CallLogging)
    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(javaClass.classLoader, "templates")
    }
    install(Routing) {
        get("/") {
            val location = locationAsync.await()
            if (location != null)
                call.respond(FreeMarkerContent("index.ftl", mapOf("location" to location)))
            else
                call.respondText("Could not get location")
        }
        static("assets") {
            resources("assets")
        }
    }
}

suspend fun gcpLocation(client: HttpClient): String? {
    // https://cloud.google.com/compute/docs/regions-zones/
    val zones = mapOf(
            "asia-east1" to "Changhua County, Taiwan",
            "asia-east2" to "Hong Kong",
            "asia-northeast1" to "Tokyo, Japan",
            "asia-south1" to "Mumbai, India",
            "asia-southeast1" to "Jurong West, Singapore",
            "australia-southeast1" to "Sydney, Australia",
            "europe-north1" to "Hamina, Finland",
            "europe-west1" to "St. Ghislain, Belgium",
            "europe-west2" to "London, England, UK",
            "europe-west3" to "Frankfurt, Germany",
            "europe-west4" to "Eemshaven, Netherlands",
            "northamerica-northeast1" to "Montréal, Québec, Canada",
            "southamerica-east1" to "São Paulo, Brazil",
            "us-central1" to "Council Bluffs, Iowa, USA",
            "us-east1" to "Moncks Corner, South Carolina, USA",
            "us-east4" to "Ashburn, Virginia, USA",
            "us-west1" to "The Dalles, Oregon, USA",
            "us-west2" to "Los Angeles, California, USA"
    )

    val zone = client.get<String> {
        url("http://metadata/computeMetadata/v1/instance/zone")
        header("Metadata-Flavor", "Google")
    }.split("/").last()

    return zones.filterKeys{ zone.startsWith(it) }.values.lastOrNull()
}

suspend fun ipLocation(client: HttpClient): String? {
    @Serializable
    data class Geo(
            val city: String,
            val countryCode: String,
            val regionName: String
    )

    val ip = client.get<String>("https://api.ipify.org")

    val geo = client.get<Geo>("http://ip-api.com/json/$ip")

    return "${geo.city}, ${geo.regionName}, ${geo.countryCode}"
}

fun locationAsync(): Deferred<String?> {
    return GlobalScope.async {
        val client = HttpClient {
            install(JsonFeature) {
                serializer = KotlinxSerializer(Json.nonstrict)
            }
        }

        val maybeLocation = try {
            gcpLocation(client)
        }
        catch (e: Exception) {
            try {
                ipLocation(client)
            }
            catch (e: Exception) {
                null
            }
        }

        client.close()

        maybeLocation
    }
}


@KtorExperimentalAPI
fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(CIO, port, watchPaths = listOf("build"), module = Application::module).start(true)
}
