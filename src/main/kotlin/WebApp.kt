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

import com.typesafe.config.ConfigFactory
import freemarker.cache.ClassTemplateLoader
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.json.serializer.KotlinxSerializer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.config.HoconApplicationConfig
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
import java.net.URL

sealed class Try<out A> {
    class Success<out A>(val value: A): Try<A>()
    class Failure(val e: Exception): Try<Nothing>()
}

@KtorExperimentalAPI
fun Application.module() {
    val locationAsync = locationAsync()

    install(DefaultHeaders)
    install(CallLogging)
    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(javaClass.classLoader, "templates")
    }
    install(Routing) {
        get("/") {
            val (geo, imgUrl) = locationAsync.await()

            if (geo != null) {
                when (imgUrl) {
                    is Try.Failure -> {
                        call.application.environment.log.error("Could not get location image", imgUrl.e)
                        call.respond(FreeMarkerContent("index.ftl", mapOf("geo" to geo)))
                    }
                    is Try.Success ->
                        call.respond(FreeMarkerContent("index.ftl", mapOf("geo" to geo, "imgUrl" to imgUrl.value)))
                }
            }
            else {
                call.respondText("Could not get location")
            }
        }
        get("/ping") {
            call.respondText("pong")
        }
        static("assets") {
            resources("assets")
        }
    }
}

@Serializable
data class Geo(
        val city: String,
        val regionName: String?,
        val country: String,
        val countryCode: String
) {
    fun searchString(): String {
        return if (regionName != null)
            "$city, $regionName"
        else
            "$city, $country"
    }
}

suspend fun gcpLocation(client: HttpClient): Geo? {
    // https://cloud.google.com/compute/docs/regions-zones/
    val zones = mapOf(
            "asia-east1" to Geo("Xianxi Township", "Changhua County", "Taiwan", "TWN"),
            "asia-east2" to Geo("Hong Kong", null, "Hong Kong", "HK"),
            "asia-northeast1" to Geo("Tokyo", null, "Japan", "JP"),
            "asia-south1" to Geo("Mumbai", null, "India", "IN"),
            "asia-southeast1" to Geo("Jurong West", null, "Singapore", "SG"),
            "australia-southeast1" to Geo("Sydney", null, "Australia", "AU"),
            "europe-north1" to Geo("Hamina", null, "Finland", "FI"),
            "europe-west1" to Geo("St. Ghislain", null, "Belgium", "BE"),
            "europe-west2" to Geo("London", null, "England", "GB"),
            "europe-west3" to Geo("Frankfurt", null, "Germany", "DE"),
            "europe-west4" to Geo("Eemshaven", null, "Netherlands", "NL"),
            "northamerica-northeast1" to Geo("Montréal", "Québec", "Canada", "CA"),
            "southamerica-east1" to Geo("São Paulo", null, "Brazil", "BR"),
            "us-central1" to Geo("Council Bluffs", "Iowa", "United States", "US"),
            "us-east1" to Geo("Moncks Corner", "South Carolina", "United States", "US"),
            "us-east4" to Geo("Ashburn", "Virginia", "United States", "US"),
            "us-west1" to Geo("The Dalles", "Oregon", "United States", "US"),
            "us-west2" to Geo("Los Angeles", "California", "United States", "US")
    )

    val zone = client.get<String> {
        url("http://metadata/computeMetadata/v1/instance/zone")
        header("Metadata-Flavor", "Google")
    }.split("/").last()

    return zones.filterKeys{ zone.startsWith(it) }.values.lastOrNull()
}

suspend fun ipLocation(client: HttpClient): Geo? {
    val ip = client.get<String>("https://api.ipify.org")

    return client.get<Geo>("http://ip-api.com/json/$ip")
}

@KtorExperimentalAPI
suspend fun imgLocation(client: HttpClient, geo: Geo): URL {
    val config = HoconApplicationConfig(ConfigFactory.load())
    val searchCx = config.propertyOrNull("search.cx")?.getString() ?: throw Exception("Missing setting: search.cx")
    val searchKey = config.propertyOrNull("search.key")?.getString() ?: throw Exception("Missing setting: search.key")

    @Serializable
    data class Result(
            val link: String
    )

    @Serializable
    data class Results(
            val items: Array<Result>
    )

    val results = client.get<Results> {
        url("https://www.googleapis.com/customsearch/v1")
        parameter("q", geo.searchString())
        parameter("num", 1)
        parameter("safe", "active")
        parameter("searchType", "image")
        parameter("rights", "cc_publicdomain")
        parameter("cx", searchCx)
        parameter("key", searchKey)
    }

    return URL(results.items.firstOrNull()?.link)
}

@KtorExperimentalAPI
fun locationAsync(): Deferred<Pair<Geo?, Try<URL>>> {
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

        val maybeImage = try {
            if (maybeLocation != null)
                Try.Success(imgLocation(client, maybeLocation))
            else
                Try.Failure(Exception("Location was unknown"))
        }
        catch (e: Exception) {
            Try.Failure(e)
        }

        client.close()

        Pair(maybeLocation, maybeImage)
    }
}


@KtorExperimentalAPI
fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(CIO, port, watchPaths = listOf("build"), module = Application::module).start(true)
}
