/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

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
