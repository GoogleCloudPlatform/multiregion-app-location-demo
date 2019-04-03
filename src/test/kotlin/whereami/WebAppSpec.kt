package whereami

import io.micronaut.context.ApplicationContext
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import kotlin.test.assertNotNull

class WebAppSpec: Spek({

    describe("WebApp") {
        val applicationContext = ApplicationContext.run()

        it("IpifyService must work") {
            val ipifyService = applicationContext.getBean(IpifyService::class.java)
            assertNotNull(ipifyService.ip().blockingGet())
        }

        it("IpApiService must work") {
            val ipApiService = applicationContext.getBean(IpApiService::class.java)
            assertNotNull(ipApiService.geo("76.89.77.36").blockingGet())
        }

        it("GeoService must work") {
            val geoService = applicationContext.getBean(GeoService::class.java)
            assertNotNull(geoService.geo().blockingGet())
        }

        val imageServiceConfig = applicationContext.getBean(ImageServiceConfig::class.java)

        try {
            imageServiceConfig.cx.blockingGet()
            imageServiceConfig.key.blockingGet()

            it("ImageService must work when configured") {
                val imageService = applicationContext.getBean(ImageService::class.java)
                val geo = Geo("Crested Butte", "Colorado", "United States", "US")
                assertNotNull(imageService.fromGeo(geo).blockingGet())
            }
        }
        catch (e: Exception) {
            xit("ImageService not configured") {}
        }

        afterGroup {
            applicationContext.close()
        }
    }
})
