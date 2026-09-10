/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.lint;

import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.detector.api.XmlContext;
import com.android.tools.lint.detector.api.XmlScanner;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.LintFix;
import org.w3c.dom.Attr;

import java.util.List;

public final class UnsafeBackupDetector extends Detector implements XmlScanner {
  @Override
  public java.util.Collection<String> getApplicableAttributes() {
    return List.of("allowBackup");
  }

  static final Issue ISSUE = Issue.create(
      "AgentRelayUnsafeBackup",
      "Disallow unrestricted Android backups",
      "Agent Relay stores sensitive connection and session material; allowing unrestricted backup "
          + "can copy that material outside the app's intended protection boundary.",
      Category.SECURITY,
      8,
      Severity.ERROR,
      new Implementation(UnsafeBackupDetector.class, Scope.MANIFEST_SCOPE));

  @Override
  public void visitAttribute(XmlContext context, Attr attribute) {
    if (!"allowBackup".equals(attribute.getLocalName())
        || !"android".equals(attribute.getPrefix())
        || !"true".equalsIgnoreCase(attribute.getValue())) {
      return;
    }
    context.report(
        ISSUE,
        attribute,
        context.getLocation(attribute),
        "Set android:allowBackup to false for sensitive Agent Relay state.",
        LintFix.create().replace().text(attribute.getValue()).with("false").build());
  }
}
