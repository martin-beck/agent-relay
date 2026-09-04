package dev.agentrelay.lint;

import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.client.api.Vendor;
import com.android.tools.lint.detector.api.Issue;
import java.util.List;

public final class AgentRelayIssueRegistry extends IssueRegistry {
    @Override
    public Vendor getVendor() {
        return new Vendor("Agent Relay");
    }

  @Override
  public List<Issue> getIssues() {
    return List.of(UnsafeBackupDetector.ISSUE);
  }

  @Override
  public int getApi() {
    return com.android.tools.lint.detector.api.ApiKt.CURRENT_API;
  }
}
