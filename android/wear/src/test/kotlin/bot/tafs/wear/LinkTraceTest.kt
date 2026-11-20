package bot.tafs.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkTraceTest {
  @Test fun anAnsweredAskReadsLookupThenSendThenSize() =
    assertEquals("tafs 0.4s + 1.9s · 3.2 kB", LinkTrace.Trace("tafs", 400, 1_900, 3_277, LinkTrace.OK).line)

  @Test fun aTimeoutWaitingOnThePhoneKeepsTheLookupApart() =
    assertEquals("needs-you ✕ timeout (0.2s + 9.8s)", LinkTrace.Trace("needs-you", 200, 9_800, null, LinkTrace.TIMEOUT).line)

  @Test fun aTimeoutStillLookingForThePhoneIsAllLookup() =
    assertEquals("needs-you ✕ timeout (10.0s)", LinkTrace.Trace("needs-you", 10_000, null, null, LinkTrace.TIMEOUT).line)

  @Test fun aCancelledAskIsNotATimeout() =
    assertEquals("brief ✕ cancelled (0.1s + 0.6s)", LinkTrace.Trace("brief", 100, 600, null, LinkTrace.CANCELLED).line)

  @Test fun aRequestIsNamedByItsPathsLastPart() = assertEquals("needs-you", LinkTrace.nameOf("/tafs/watch/v1/needs-you"))
}
