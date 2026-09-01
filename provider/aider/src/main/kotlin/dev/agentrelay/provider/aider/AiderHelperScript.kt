package dev.agentrelay.provider.aider

internal val AIDER_HELPER_SCRIPT =
    """
    import io
    import json
    import os
    import pathlib
    import re
    import subprocess
    import sys
    import threading
    import time
    import webbrowser

    protocol_in = sys.stdin
    protocol_out = sys.stdout
    emit_lock = threading.Lock()
    diagnostics = []

    def emit(record):
        with emit_lock:
            protocol_out.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
            protocol_out.flush()

    class Sink:
        encoding = "utf-8"

        def write(self, value):
            if value and value.strip():
                diagnostics.append(value.strip())
                del diagnostics[:-40]
            return len(value or "")

        def flush(self):
            return None

        def isatty(self):
            return False

    def message(error):
        detail = str(error).strip() or error.__class__.__name__
        if diagnostics:
            detail = detail + ": " + diagnostics[-1][:800]
        return detail[:1600]

    def safe_path(value, base):
        candidate = pathlib.Path(value)
        if not candidate.is_absolute():
            candidate = base / candidate
        candidate = candidate.resolve()
        if candidate != base and base not in candidate.parents:
            raise ValueError("Aider file is outside the session workspace")
        return candidate

    session_id, state_root_raw, cwd_raw, requested_model, config_path, files_raw, resume_raw = (
        sys.argv[1:8]
    )
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", session_id):
        raise SystemExit("Invalid Agent Relay Aider session id")

    state_root = pathlib.Path(state_root_raw).resolve()
    working_directory = pathlib.Path(cwd_raw or os.getcwd()).resolve()
    state_directory = (state_root / session_id).resolve()
    if state_directory.parent != state_root:
        raise SystemExit("Unsafe Agent Relay Aider state path")
    state_directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(state_directory, 0o700)

    chat_history = state_directory / "chat-history.md"
    input_history = state_directory / "input-history"
    metadata_path = state_directory / "session.json"
    files = json.loads(files_raw)
    if not isinstance(files, list) or not all(isinstance(item, str) for item in files):
        raise SystemExit("Aider files must be a JSON string array")
    files = [str(safe_path(item, working_directory)) for item in files]

    def read_metadata():
        try:
            with metadata_path.open(encoding="utf-8") as stream:
                value = json.load(stream)
                return value if isinstance(value, dict) else {}
        except (FileNotFoundError, json.JSONDecodeError, OSError):
            return {}

    def write_metadata(changes=None, preview=None, model=None):
        previous = read_metadata()
        now = int(time.time())
        changed_files = previous.get("changedFiles")
        if not isinstance(changed_files, dict):
            changed_files = {}
        if changes:
            changed_files.update(changes)
        document = {
            "id": session_id,
            "title": previous.get("title") or working_directory.name or "Aider session",
            "preview": preview if preview is not None else previous.get("preview", ""),
            "workingDirectory": str(working_directory),
            "model": model or previous.get("model") or requested_model or None,
            "createdAt": previous.get("createdAt") or now,
            "updatedAt": now,
            "stateDirectory": str(state_directory),
            "chatHistoryPath": str(chat_history),
            "files": json.dumps(files, separators=(",", ":")),
            "config": config_path or previous.get("config") or None,
            "changedFiles": changed_files,
        }
        temporary = metadata_path.with_suffix(".tmp")
        with temporary.open("w", encoding="utf-8") as stream:
            json.dump(document, stream, ensure_ascii=False, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, metadata_path)

    sink = Sink()
    sys.stdout = sink
    sys.stderr = sink
    webbrowser.open = lambda *args, **kwargs: False

    try:
        from aider.main import main

        arguments = [
            "--chat-history-file", str(chat_history),
            "--input-history-file", str(input_history),
            "--llm-history-file", os.devnull,
            "--no-pretty",
            "--no-stream",
            "--no-auto-commits",
            "--no-dirty-commits",
            "--no-gitignore",
            "--no-add-gitignore-files",
            "--no-auto-lint",
            "--no-auto-test",
            "--no-watch-files",
            "--no-analytics",
            "--no-check-update",
            "--no-show-release-notes",
            "--no-show-model-warnings",
            "--no-suggest-shell-commands",
            "--no-fancy-input",
            "--no-notifications",
            "--no-detect-urls",
            "--disable-playwright",
            "--no-gui",
            "--no-copy-paste",
        ]
        if requested_model:
            arguments += ["--model", requested_model]
        if config_path:
            arguments += ["--config", config_path]
        if resume_raw == "true" and chat_history.exists():
            arguments.append("--restore-chat-history")
        git_check = subprocess.run(
            ["git", "-C", str(working_directory), "rev-parse", "--is-inside-work-tree"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if git_check.returncode != 0:
            arguments.append("--no-git")
        arguments += files

        coder = main(
            argv=arguments,
            input=io.StringIO(""),
            output=sink,
            return_coder=True,
        )
        if not hasattr(coder, "run") or not hasattr(coder, "io"):
            raise RuntimeError("Installed Aider API did not return a coder")
        coder.io.yes = False
        coder.io.notifications = False
        current_model = getattr(getattr(coder, "main_model", None), "name", None)
        write_metadata(model=current_model)
        emit({"type": "ready", "model": current_model})
    except BaseException as error:
        emit({"type": "error", "message": message(error)})
        raise SystemExit(1)

    for line in protocol_in:
        request_id = None
        try:
            request = json.loads(line)
            request_id = request.get("id")
            if request.get("method") != "prompt":
                raise ValueError("Unsupported Aider helper method")
            prompt = request.get("text")
            if not isinstance(prompt, str) or not prompt.strip():
                raise ValueError("Aider prompt must not be blank")

            before = {}
            for item in coder.get_inchat_relative_files():
                path = safe_path(item, working_directory)
                before[str(path)] = path.exists()
            response = coder.run(with_message=prompt) or ""
            changes = {}
            result_files = []
            for item in sorted(getattr(coder, "aider_edited_files", set()) or set()):
                path = safe_path(str(item), working_directory)
                key = str(path)
                kind = "deleted" if not path.exists() else (
                    "modified" if before.get(key, False) else "added"
                )
                changes[key] = kind
                result_files.append({"path": key, "kind": kind})
            preview = next((part.strip() for part in prompt.splitlines() if part.strip()), "")[:160]
            write_metadata(changes=changes, preview=preview, model=current_model)
            emit({"id": request_id, "type": "result", "text": response, "files": result_files})
        except BaseException as error:
            emit({"id": request_id, "type": "error", "message": message(error)})
    """.trimIndent()
