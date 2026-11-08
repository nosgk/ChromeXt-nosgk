package org.matrix.chromext.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

  @Test
  fun comparesNumericSegments() {
    assertTrue(UpdateChecker.compareVersions("1.10.0", "1.9.9") > 0)
    assertTrue(UpdateChecker.compareVersions("2.0", "10.0") < 0)
    assertTrue(UpdateChecker.compareVersions("2024.05.01", "2024.04.30") > 0)
  }

  @Test
  fun ignoresTheVersionPrefixAndTrailingZeroes() {
    assertEquals(0, UpdateChecker.compareVersions("v1.0", "1.0.0"))
    assertEquals(0, UpdateChecker.compareVersions("V2.3", "2.3"))
  }

  @Test
  fun releaseBeatsPrerelease() {
    assertTrue(UpdateChecker.compareVersions("1.0", "1.0-beta") > 0)
    assertTrue(UpdateChecker.compareVersions("1.0-beta", "1.0") < 0)
  }

  @Test
  fun extraNumericRevisionIsNewer() {
    assertTrue(UpdateChecker.compareVersions("1.0.1", "1.0") > 0)
    assertTrue(UpdateChecker.compareVersions("1.0", "1.0.1") < 0)
  }

  @Test
  fun emptyCurrentVersionCountsAsOlder() {
    assertTrue(UpdateChecker.compareVersions("1.0", "") > 0)
    assertEquals(0, UpdateChecker.compareVersions("", ""))
  }
}
