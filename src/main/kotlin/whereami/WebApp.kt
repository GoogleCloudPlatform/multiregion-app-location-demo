package whereami/*
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

import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.context.annotation.Requirements
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import io.micronaut.context.env.Environment
import io.micronaut.discovery.cloud.gcp.GoogleComputeInstanceMetadataResolver
import io.micronaut.discovery.cloud.gcp.GoogleComputeMetadataConfiguration
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
import io.micronaut.http.client.annotation.Client
import io.micronaut.runtime.Micronaut
import io.micronaut.views.View
import io.reactivex.Single
import org.slf4j.LoggerFactory
import java.net.URL
import java.util.*
import javax.inject.Singleton
import javax.validation.constraints.NotBlank

fun main() {
    Micronaut.build().packages("whereami").mainClass(WebApp::class.java).start()
}

@Controller
class WebApp(geoService: GeoService, imageService: ImageService) {

    private val LOG = LoggerFactory.getLogger(WebApp::class.java)

    data class Model(val geo: Geo, val maybeImage: URL?)

    val geoSingle = geoService.geo()
    val imageSingle = geoSingle.flatMap { geo ->
        imageService.fromGeo(geo).doOnError { t ->
            LOG.error("Could not get image for ${geo.searchString()}", t)
        }
    }

    val modelSingle = geoSingle.flatMap { geo ->
        imageSingle.map { Model(geo, it) }.onErrorReturn { Model(geo, null) }
    }

    @View("index")
    @Get("/")
    fun index(): Single<HttpResponse<Model>> {
        return modelSingle.map { HttpResponse.ok(it) }
    }

    @Get("/ping")
    fun ping(): String {
        return "pong"
    }

}

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

@Client("https://api.ipify.org")
interface IpifyService {
    @Get
    fun ip(): Single<String>
}

@Client("http://ip-api.com/json")
interface IpApiService {
    @Get("/{ip}")
    fun geo(ip: String): Single<Geo>
}

@Singleton
@Requires(notEnv = [Environment.GOOGLE_COMPUTE])
class NonGcpService(private val ipifyService: IpifyService,
                    private val ipApiService: IpApiService): GeoService {

    override fun geo(): Single<Geo> {
        return ipifyService.ip().flatMap { ipApiService.geo(it) }
    }

}

@Singleton
@Requires(env = [Environment.GOOGLE_COMPUTE])
class GcpService(private val googleComputeInstanceMetadataResolver: GoogleComputeInstanceMetadataResolver,
                 private val environment: Environment): GeoService {
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

    override fun geo(): Single<Geo> {
        val maybeGeo = googleComputeInstanceMetadataResolver.resolve(environment).flatMap { metadata ->
            Optional.ofNullable(zones[metadata.region])
        }

        return if (maybeGeo.isPresent)
            Single.just(maybeGeo.get())
        else
            Single.error(Exception("Could not determine region"))
    }
}

interface GeoService {
    fun geo(): Single<Geo>
}

@Singleton
@Requirements(Requires(property = "search.cx"), Requires(property = "search.key"))
class ImageServiceConfigProperty(@param:Value("\${search.cx}") override val cx: String,
                                 @param:Value("\${search.key}") override val key: String): ImageServiceConfig

@Singleton
@Requires(env = [Environment.GOOGLE_COMPUTE], missingBeans = [ImageServiceConfigProperty::class])
class CustomInstanceMetadataResolver(objectMapper: ObjectMapper,
                                     private val configuration: GoogleComputeMetadataConfiguration): GoogleComputeInstanceMetadataResolver(objectMapper, configuration) {
    fun getAttribute(key: String): String? {
        val connectionTimeoutMs = configuration.connectTimeout.toMillis().toInt()
        val readTimeoutMs = configuration.readTimeout.toMillis().toInt()
        val projectResultJson = readGcMetadataUrl(URL(configuration.metadataUrl + "?recursive=true"), connectionTimeoutMs, readTimeoutMs)

        return projectResultJson.findValue("attributes").findValue(key).asText()
    }
}

@Singleton
@Requires(env = [Environment.GOOGLE_COMPUTE])
class ImageServiceConfigCloud(customInstanceMetadataResolver: CustomInstanceMetadataResolver): ImageServiceConfig {
    override val cx: String? = customInstanceMetadataResolver.getAttribute("SEARCH_CX")
    override val key: String? = customInstanceMetadataResolver.getAttribute("SEARCH_KEY")
}

@Singleton
@Requires(missingBeans = [ImageServiceConfig::class])
class ImageServiceConfigMissing: ImageServiceConfig {
    override val cx: String? = null
    override val key: String? = null
}

interface ImageServiceConfig {
    val cx: String?
    val key: String?
}

data class Result(
        val link: String
)

data class Results(
        val items: Array<Result>
)

@Client("https://www.googleapis.com/customsearch/v1")
interface CustomSearchService {
    @Get
    fun search(@QueryValue("cx") @NotBlank cx: String,
               @QueryValue("key") @NotBlank key: String,
               @QueryValue("q") @NotBlank s: String,
               @QueryValue("num") @NotBlank num: Int = 1,
               @QueryValue("safe") @NotBlank safe: String = "active",
               @QueryValue("searchType") @NotBlank searchType: String = "image",
               @QueryValue("rights") @NotBlank rights: String = "cc_publicdomain"
    ): Single<Results>
}

@Singleton
class ImageService(private val config: ImageServiceConfig,
                   private val customSearch: CustomSearchService) {

    fun fromGeo(geo: Geo): Single<URL> {
        val cx = config.cx ?: return Single.error(Exception("Could not get search cx setting"))
        val key = config.key ?: return Single.error(Exception("Could not get search key setting"))

        return customSearch.search(cx, key, geo.searchString()).flatMap { results ->
            val url = try { URL(results.items.first().link) } catch (e: Exception) { null }
            if (url != null)
                Single.just(url)
            else
                Single.error(Exception("Image could not be found"))
        }
    }

}
