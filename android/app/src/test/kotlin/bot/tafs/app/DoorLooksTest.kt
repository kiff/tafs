package bot.tafs.app

import bot.tafs.app.app.TafDoors
import bot.tafs.app.kiff.Door
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.Knock
import bot.tafs.app.kiff.KnobWire
import bot.tafs.app.model.DoorLook
import bot.tafs.app.views.doorLooks
import org.junit.Assert.assertEquals
import org.junit.Test

/** A door's look on its When stop comes from the taf's doors: open, closed, resting, a knock waiting, a message just in. */
class DoorLooksTest {
  private val wire = KiffClient.json.decodeFromString<KnobWire>(
    """{"wakes":[{"id":"w_mail","kind":"door","door":"d_mail","label":"Email"},{"id":"w_slack","kind":"door","door":"d_slack","label":"Slack"},
                 {"id":"w_quiet","kind":"door","door":"d_quiet","label":"Form"},{"id":"w_week","kind":"schedule","label":"Weekdays"},
                 {"id":"w_gone","kind":"door","door":"d_gone","label":"Old"}],"rim":{}}""",
  )
  private fun knock(door: String, state: String) =
    KiffClient.json.decodeFromString<Knock>("""{"id":"k_$door","door":"$door","app":"email","from":"a@example.com","state":"$state","at":"2026-11-03T06:00:00Z"}""")

  private val doors = TafDoors(
    doors = listOf(
      Door(id = "d_mail", app = "email", kind = "email", label = "Email", open = true),
      Door(id = "d_slack", app = "slack", kind = "slack", label = "Slack", open = false),
      Door(id = "d_quiet", app = "form", kind = "form", label = "Form", open = true, resting = true),
    ),
    knocks = listOf(knock("d_mail", "open"), knock("d_slack", "done")),
    loaded = true,
  )

  @Test fun eachDoorStopLooksAsItsDoorIs() {
    val looks = doorLooks(wire, doors)
    assertEquals(
      mapOf(
        "w_mail" to DoorLook(open = true, knocking = true),
        "w_slack" to DoorLook(open = false),
        "w_quiet" to DoorLook(open = true, resting = true),
      ),
      looks,
    )
  }

  @Test fun aMessageJustInLightsOnlyItsDoor() {
    val looks = doorLooks(wire, doors, arrived = setOf("d_slack"))
    assertEquals(true, looks["w_slack"]!!.arrived)
    assertEquals(false, looks["w_mail"]!!.arrived)
  }
}
