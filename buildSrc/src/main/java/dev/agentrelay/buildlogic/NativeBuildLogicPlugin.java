package dev.agentrelay.buildlogic;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Registers native dependency tasks for validated reuse and functional testing. */
public final class NativeBuildLogicPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    project.getTasks().register("extractSherpaSource", SherpaSourceExtractTask.class);
  }
}
