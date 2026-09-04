package dev.agentrelay.lint;

import com.android.tools.lint.checks.infrastructure.LintDetectorTest;
import com.android.tools.lint.checks.infrastructure.TestFile;
import org.junit.Assert;

public final class UnsafeBackupDetectorTest extends LintDetectorTest {
  @Override
  protected com.android.tools.lint.detector.api.Detector getDetector() {
    return new UnsafeBackupDetector();
  }

  @Override
  protected java.util.List<com.android.tools.lint.detector.api.Issue> getIssues() {
    return java.util.List.of(UnsafeBackupDetector.ISSUE);
  }

  @Override
  protected boolean allowMissingSdk() {
    return true;
  }

  public void testReportsTrueAndOffersExactFix() {
    TestFile manifest = manifest(""
        + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">"
        + "<application android:allowBackup=\"true\" />"
        + "</manifest>");
    Assert.assertNotNull(manifest);

    lint().allowMissingSdk().files(manifest).run()
        .expectContains("Error: Set android:allowBackup to false for sensitive Agent Relay state. [AgentRelayUnsafeBackup]")
        .expectFixDiffs("Fix for AndroidManifest.xml line 1: Replace with false:\n@@ -1 +1 @@\n-<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:allowBackup=\"true\" /></manifest>\n+<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:allowBackup=\"false\" /></manifest>");
  }

  public void testAcceptsFalse() {
    lint().allowMissingSdk().files(manifest(""
        + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">"
        + "<application android:allowBackup=\"false\" />"
        + "</manifest>")).run().expectClean();
  }
}
