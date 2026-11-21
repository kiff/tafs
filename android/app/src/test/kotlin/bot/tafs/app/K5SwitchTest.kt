package bot.tafs.app

import bot.tafs.app.model.ONBOARDING_ON
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * K5 (docs/onboarding.md): the onboarding and the sign-in inside tafs are on only in a debug build made with `-Pk5` or
 * `TAFS_K5=1`. The build every test runs on has neither, so it's off, as it ships; a release build has it off whatever is
 * passed (CI: "A release build never has K5").
 */
class K5SwitchTest {
  @Test fun offUnlessAskedFor() {
    assertFalse("K5 is on in a build that didn't ask for it", BuildConfig.K5)
    assertFalse(ONBOARDING_ON)
  }
}
