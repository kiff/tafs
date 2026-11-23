package bot.tafs.app

import bot.tafs.app.app.endpointOn
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.views.apiExample
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An API door's endpoint and example against the apps' shared vector (vectors/api-door-endpoint.json), so Android writes
 * them byte for byte as iOS and the desktops do: every `exact` address gives its endpoint, every `hostile` one stays https on
 * tafs cloud's host with a plain path, and the example quotes the endpoint between the vector's prefix and suffix.
 */
class ApiDoorVectorTest {
  private val vector: JsonObject = KiffClient.json.parseToJsonElement(File("../../vectors/api-door-endpoint.json").readText()).jsonObject
  private fun text(key: String) = vector.getValue(key).jsonPrimitive.content
  private val base = text("base")
  private val plain = Regex(vector.getValue("rules").jsonObject.getValue("path_pattern").jsonPrimitive.content)

  @Test fun everyExactAddressGivesItsEndpoint() {
    for (case in vector.getValue("exact").jsonArray) {
      val address = case.jsonObject.getValue("address").jsonPrimitive.content
      assertEquals(address, case.jsonObject.getValue("endpoint").jsonPrimitive.content, endpointOn(base, address))
    }
  }

  @Test fun everyHostileAddressStaysOnTafsCloudWithAPlainPath() {
    val host = vector.getValue("rules").jsonObject.getValue("host").jsonPrimitive.content
    for (address in vector.getValue("hostile").jsonArray.map { it.jsonPrimitive.content }) {
      val e = endpointOn(base, address)
      val u = java.net.URI(e)
      assertEquals(address, "https", u.scheme)
      assertEquals(address, host, u.host)
      assertEquals(address, -1, u.port)
      assertEquals(address, null, u.rawUserInfo)
      assertEquals(address, null, u.rawQuery)
      assertEquals(address, null, u.rawFragment)
      assertTrue("$address → $e", plain.matches(u.rawPath))
    }
  }

  @Test fun theExampleQuotesTheEndpointBetweenTheVectorsPrefixAndSuffix() {
    val endpoint = endpointOn(base, "/v1/doors/api/door_ord7/requests")
    assertEquals(text("example_prefix") + endpoint + text("example_suffix"), apiExample(endpoint))
    assertEquals(text("alone"), endpointOn(base, "/v1/x;id"))
  }
}
