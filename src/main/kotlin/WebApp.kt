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
import io.ktor.server.netty.Netty
import io.ktor.server.engine.embeddedServer
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun Application.module() {
    val ipInfoAsync = ipInfoAsync()

    install(DefaultHeaders)
    install(CallLogging)
    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(javaClass.classLoader, "templates")
    }
    install(Routing) {
        get("/") {
            val ipInfo = ipInfoAsync.await()
            if (ipInfo != null)
                call.respond(FreeMarkerContent("index.ftl", mapOf("ipInfo" to ipInfo)))
            else
                call.respondText("Could not get external ip")
        }
        static("assets") {
            resources("assets")
        }
    }
}

suspend fun gcpExternalIp(client: HttpClient): String {
    return client.get {
        url("http://metadata/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip")
        header("Metadata-Flavor", "Google")
    }
}

suspend fun ipifyIp(client: HttpClient): String {
    return client.get("https://api.ipify.org")
}

suspend fun externalIp(client: HttpClient): String? {
    return try {
        gcpExternalIp(client)
    }
    catch (e: Exception) {
        try {
            ipifyIp(client)
        }
        catch (e: Exception) {
            null
        }
    }
}

@Serializable
data class Geo(
        val city: String,
        val country: String,
        val countryCode: String,
        val lat: Double,
        val lon: Double,
        val region: String,
        val regionName: String
)

suspend fun geoIp(ip: String, client: HttpClient): Geo {
    return client.get("http://ip-api.com/json/$ip")
}

@Serializable
data class IpInfo(val externalIp: String, val geo: Geo)

fun ipInfoAsync(): Deferred<IpInfo?> {
    return GlobalScope.async {
        val client = HttpClient {
            install(JsonFeature) {
                serializer = KotlinxSerializer(Json.nonstrict)
            }
        }

        val externalIp = externalIp(client)
        val maybeIpInfo = if (externalIp != null)
            IpInfo(externalIp, geoIp(externalIp, client))
        else
            null

        client.close()

        maybeIpInfo
    }
}


@KtorExperimentalAPI
fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port, watchPaths = listOf("build"), module = Application::module).start(true)
}
