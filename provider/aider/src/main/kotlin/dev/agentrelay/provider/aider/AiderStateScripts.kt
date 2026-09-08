/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.aider

internal val AIDER_DISCOVER_SCRIPT =
    """
    import json
    import pathlib
    import re
    import sys

    root = pathlib.Path(sys.argv[1]).resolve()
    rows = []
    if root.is_dir():
        for metadata_path in root.glob("*/session.json"):
            try:
                metadata_path = metadata_path.resolve()
                state_directory = metadata_path.parent
                if state_directory.parent != root:
                    continue
                if not re.fullmatch(r"[0-9a-fA-F-]{36}", state_directory.name):
                    continue
                if metadata_path.stat().st_size > 1024 * 1024:
                    continue
                with metadata_path.open(encoding="utf-8") as stream:
                    row = json.load(stream)
                if not isinstance(row, dict) or row.get("id") != state_directory.name:
                    continue
                row["stateDirectory"] = str(state_directory)
                row["chatHistoryPath"] = str(state_directory / "chat-history.md")
                rows.append(row)
            except (OSError, ValueError, json.JSONDecodeError):
                continue
    rows.sort(key=lambda item: item.get("updatedAt", 0), reverse=True)
    print(json.dumps(rows, ensure_ascii=False, separators=(",", ":")))
    """.trimIndent()

internal val AIDER_READ_TRANSCRIPT_SCRIPT =
    """
    import json
    import pathlib
    import sys

    root = pathlib.Path(sys.argv[1]).resolve()
    path = pathlib.Path(sys.argv[2]).resolve()
    if path.name != "chat-history.md" or path.parent.parent != root:
        raise SystemExit("Unsafe Agent Relay Aider transcript path")
    if not path.exists():
        print("[]")
        raise SystemExit(0)
    if path.stat().st_size > 8 * 1024 * 1024:
        raise SystemExit("Aider transcript exceeds the 8 MiB safety limit")

    text = path.read_text(encoding="utf-8", errors="replace")
    messages = []
    user = []
    assistant = []
    tool = []

    def append_message(role, lines):
        content = "".join(lines).strip()
        if content:
            messages.append({"role": role, "text": content})

    for line in text.splitlines(keepends=True):
        if line.startswith("# "):
            continue
        if line.startswith("> "):
            append_message("assistant", assistant)
            assistant = []
            append_message("user", user)
            user = []
            tool.append(line[2:])
            continue
        if line.startswith("#### "):
            append_message("assistant", assistant)
            assistant = []
            append_message("tool", tool)
            tool = []
            user.append(line[5:])
            continue
        append_message("user", user)
        user = []
        append_message("tool", tool)
        tool = []
        assistant.append(line)

    append_message("assistant", assistant)
    append_message("user", user)
    for index, message in enumerate(messages[-1000:]):
        message["id"] = "aider-history-" + str(index)
    print(json.dumps(messages[-1000:], ensure_ascii=False, separators=(",", ":")))
    """.trimIndent()
