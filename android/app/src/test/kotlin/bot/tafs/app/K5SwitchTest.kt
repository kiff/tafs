package bot.tafs.app

import bot.tafs.app.model.ONBOARDING_ON
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K5 (docs/onboarding.md): the onboarding and the sign-in inside tafs are on in every build, debug and release alike, since
 * the owner's yes on 22 Nov (CI: "A release build has K5").
 */
class K5SwitchTest {
  @Test fun onInEveryBuild() {
    assertTrue("K5 is off in a build", BuildConfig.K5)
    assertTrue(ONBOARDING_ON)
  }
}
