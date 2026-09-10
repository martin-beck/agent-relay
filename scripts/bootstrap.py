#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Install and validate the repository-local Agent Relay build toolchain."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import IO, Any, cast

TARGETS = ("build", "device", "quality")
MARKER = ".agent-relay-artifact.json"
DOWNLOAD_CHUNK = 1024 * 1024
MAX_MEMBERS = 250_000


class BootstrapError(RuntimeError):
    """A controlled bootstrap failure with an actionable message."""


@dataclass(frozen=True)
class Host:
    system: str
    architecture: str
    supported: bool
    reason: str


@dataclass(frozen=True)
class Check:
    name: str
    status: str
    expected: str
    actual: str | None
    path: str | None
    reason: str


@dataclass(frozen=True)
class Layout:
    repo: Path
    state: Path
    toolchain: Path
    cache: Path
    staging: Path

    @classmethod
    def create(cls, repo: Path) -> Layout:
        state = repo / ".agent-relay"
        return cls(repo, state, state / "toolchain", state / "cache", state / "staging")


@dataclass(frozen=True)
class Report:
    schema_version: int
    manifest_revision: str
    target: str
    host: Host
    checks: tuple[Check, ...]
    ready: bool
    install_requested: bool
    install_performed: bool
    offline: bool


def detect_host() -> Host:
    system = platform.system()
    architecture = platform.machine().lower()
    supported = system == "Linux" and architecture in {"x86_64", "amd64"}
    reason = (
        "supported host"
        if supported
        else "this manifest supports only Linux x86_64; use a reviewed host manifest"
    )
    return Host(system, architecture, supported, reason)


def redact_path(path: Path | None, layout: Layout) -> str | None:
    if path is None:
        return None
    absolute = path.resolve(strict=False)
    for prefix, label in ((layout.repo, "$REPO"), (Path.home(), "$HOME")):
        try:
            relative = absolute.relative_to(prefix)
        except ValueError:
            continue
        suffix = relative.as_posix()
        return label + (f"/{suffix}" if suffix != "." else "")
    return "$EXTERNAL/" + absolute.name


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BootstrapError(f"cannot read manifest: {error}") from error
    if data.get("schema_version") != 1 or not isinstance(data.get("artifacts"), list):
        raise BootstrapError("unsupported or incomplete toolchain manifest")
    for artifact in data["artifacts"]:
        required = {
            "id",
            "version",
            "url",
            "filename",
            "size",
            "sha256",
            "format",
            "destination",
            "targets",
            "checks",
        }
        if not isinstance(artifact, dict) or not required.issubset(artifact):
            raise BootstrapError("artifact entry is incomplete")
        if not str(artifact["url"]).startswith("https://"):
            raise BootstrapError(f"artifact {artifact['id']} does not use HTTPS")
        sha = str(artifact["sha256"])
        if len(sha) != 64 or any(char not in "0123456789abcdef" for char in sha):
            raise BootstrapError(f"artifact {artifact['id']} has an invalid SHA-256")
    return cast(dict[str, Any], data)


def selected_artifacts(manifest: dict[str, Any], target: str) -> list[dict[str, Any]]:
    return [item for item in manifest["artifacts"] if target in item["targets"]]


def wrapper_hash(url: str) -> str:
    """Return Gradle Wrapper's base-36 MD5 distribution identifier."""
    number = int.from_bytes(hashlib.md5(url.encode(), usedforsecurity=False).digest())
    alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
    encoded = ""
    while number:
        number, remainder = divmod(number, 36)
        encoded = alphabet[remainder] + encoded
    return encoded or "0"


def artifact_destination(artifact: dict[str, Any], layout: Layout) -> Path:
    destination = str(artifact["destination"])
    if destination == "@gradle-wrapper":
        name = Path(str(artifact["filename"])).name.removesuffix(".zip")
        return (
            layout.toolchain
            / "gradle-home/wrapper/dists"
            / name
            / wrapper_hash(str(artifact["url"]))
            / f"gradle-{artifact['version']}"
        )
    relative = PurePosixPath(destination)
    if relative.is_absolute() or ".." in relative.parts:
        raise BootstrapError(f"unsafe destination for {artifact['id']}")
    return layout.toolchain.joinpath(*relative.parts)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(DOWNLOAD_CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_download(path: Path, artifact: dict[str, Any]) -> None:
    actual_size = path.stat().st_size
    if actual_size != int(artifact["size"]):
        raise BootstrapError(f"cached {artifact['id']} size mismatch")
    if sha256_file(path) != artifact["sha256"]:
        raise BootstrapError(f"cached {artifact['id']} SHA-256 mismatch")


def download_artifact(  # noqa: C901 - download integrity failures need explicit cleanup.
    artifact: dict[str, Any], layout: Layout, *, offline: bool, repair: bool
) -> Path:
    cache_path = layout.cache / str(artifact["filename"])
    if cache_path.exists():
        try:
            validate_download(cache_path, artifact)
            return cache_path
        except (OSError, BootstrapError):
            if not repair:
                raise BootstrapError(
                    f"cache entry for {artifact['id']} is corrupt; rerun with --repair"
                ) from None
            cache_path.unlink()
    if offline:
        raise BootstrapError(f"offline cache miss for {artifact['id']}")
    layout.cache.mkdir(parents=True, exist_ok=True)
    partial = cache_path.with_suffix(cache_path.suffix + ".partial")
    partial.unlink(missing_ok=True)
    expected_size = int(artifact["size"])
    digest = hashlib.sha256()
    written = 0
    try:
        request = urllib.request.Request(  # noqa: S310 - manifest validation requires HTTPS.
            str(artifact["url"]), headers={"User-Agent": "agent-relay-bootstrap/1"}
        )
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("xb") as output:  # noqa: S310
            if not response.geturl().startswith("https://"):
                raise BootstrapError(f"insecure redirect for {artifact['id']}")
            length = response.headers.get("Content-Length")
            if length is not None and int(length) != expected_size:
                raise BootstrapError(f"server size mismatch for {artifact['id']}")
            while chunk := response.read(DOWNLOAD_CHUNK):
                written += len(chunk)
                if written > expected_size:
                    raise BootstrapError(f"download exceeds reviewed size for {artifact['id']}")
                digest.update(chunk)
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        if written != expected_size or digest.hexdigest() != artifact["sha256"]:
            raise BootstrapError(f"download integrity check failed for {artifact['id']}")
        os.replace(partial, cache_path)
        return cache_path
    except Exception:
        partial.unlink(missing_ok=True)
        raise


def safe_relative(name: str, strip_components: int) -> PurePosixPath | None:
    raw = PurePosixPath(name)
    if raw.is_absolute() or "\\" in name or ".." in raw.parts:
        raise BootstrapError(f"unsafe archive member: {name}")
    parts = tuple(part for part in raw.parts if part not in {"", "."})
    if len(parts) <= strip_components:
        return None
    return PurePosixPath(*parts[strip_components:])


def ensure_parent(path: Path, root: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    current = path.parent
    while current != root:
        if current.is_symlink():
            raise BootstrapError("archive attempts to write through a symbolic link")
        current = current.parent


def copy_stream(source: IO[bytes], destination: Path, mode: int) -> None:
    with destination.open("xb") as output:
        shutil.copyfileobj(source, output, DOWNLOAD_CHUNK)
    destination.chmod(mode & 0o777 or 0o644)


def materialize_links(root: Path, links: list[tuple[Path, str]]) -> None:
    pending = links[:]
    for _ in range(len(links) + 1):
        if not pending:
            return
        remaining: list[tuple[Path, str]] = []
        progress = False
        for destination, raw_target in pending:
            target = PurePosixPath(raw_target)
            if target.is_absolute() or "\\" in raw_target:
                raise BootstrapError("archive contains an absolute link")
            candidate = (destination.parent / Path(*target.parts)).resolve(strict=False)
            try:
                candidate.relative_to(root.resolve())
            except ValueError as error:
                raise BootstrapError("archive link escapes the staging directory") from error
            if not candidate.exists() or candidate.is_symlink():
                remaining.append((destination, raw_target))
                continue
            ensure_parent(destination, root)
            if candidate.is_dir():
                shutil.copytree(candidate, destination, symlinks=False)
            else:
                shutil.copy2(candidate, destination)
            progress = True
        if not progress:
            raise BootstrapError("archive contains a missing or cyclic link")
        pending = remaining


def extract_zip(archive: Path, root: Path, strip_components: int, limit: int) -> None:
    with zipfile.ZipFile(archive) as source:
        members = source.infolist()
        if len(members) > MAX_MEMBERS or sum(item.file_size for item in members) > limit:
            raise BootstrapError("archive exceeds reviewed extraction limits")
        seen: set[PurePosixPath] = set()
        links: list[tuple[Path, str]] = []
        for item in members:
            relative = safe_relative(item.filename, strip_components)
            if relative is None:
                continue
            if relative in seen:
                raise BootstrapError(f"duplicate archive member: {relative}")
            seen.add(relative)
            destination = root.joinpath(*relative.parts)
            mode = (item.external_attr >> 16) & 0xFFFF
            if item.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
            elif stat.S_ISLNK(mode):
                links.append((destination, source.read(item).decode("utf-8")))
            elif stat.S_IFMT(mode) not in {0, stat.S_IFREG}:
                raise BootstrapError(f"unsupported archive member: {relative}")
            else:
                ensure_parent(destination, root)
                with source.open(item) as member:
                    copy_stream(member, destination, mode)
        materialize_links(root, links)


def extract_tar(  # noqa: C901 - member types are intentionally handled explicitly.
    archive: Path, root: Path, strip_components: int, limit: int
) -> None:
    with tarfile.open(archive, "r:*") as source:
        members = source.getmembers()
        if len(members) > MAX_MEMBERS or sum(item.size for item in members) > limit:
            raise BootstrapError("archive exceeds reviewed extraction limits")
        seen: set[PurePosixPath] = set()
        links: list[tuple[Path, str]] = []
        for item in members:
            relative = safe_relative(item.name, strip_components)
            if relative is None:
                continue
            if relative in seen:
                raise BootstrapError(f"duplicate archive member: {relative}")
            seen.add(relative)
            destination = root.joinpath(*relative.parts)
            if item.isdir():
                destination.mkdir(parents=True, exist_ok=True)
            elif item.issym():
                links.append((destination, item.linkname))
            elif item.islnk():
                linked = safe_relative(item.linkname, strip_components)
                if linked is None:
                    raise BootstrapError("archive hard link points outside selected root")
                target = root.joinpath(*linked.parts)
                links.append((destination, os.path.relpath(target, destination.parent)))
            elif item.isfile():
                extracted = source.extractfile(item)
                if extracted is None:
                    raise BootstrapError(f"cannot extract archive member: {relative}")
                ensure_parent(destination, root)
                with extracted:
                    copy_stream(extracted, destination, item.mode)
            else:
                raise BootstrapError(f"unsupported archive member: {relative}")
        materialize_links(root, links)


def extract_artifact(archive: Path, root: Path, artifact: dict[str, Any]) -> None:
    root.mkdir(parents=True)
    archive_format = artifact["format"]
    strip_components = int(artifact.get("strip_components", 0))
    limit = int(artifact["max_expanded_bytes"])
    if archive_format == "zip":
        extract_zip(archive, root, strip_components, limit)
    elif archive_format in {"tar.gz", "tar.xz"}:
        extract_tar(archive, root, strip_components, limit)
    elif archive_format == "file":
        if archive.stat().st_size > limit:
            raise BootstrapError("artifact exceeds reviewed extraction limit")
        destination = root / str(artifact["raw_name"])
        shutil.copy2(archive, destination)
        destination.chmod(0o755)
    else:
        raise BootstrapError(f"unsupported archive format: {archive_format}")


def local_environment(layout: Layout) -> dict[str, str]:
    toolchain = layout.toolchain
    environment = os.environ.copy()
    environment.pop("ANDROID_PREFS_ROOT", None)
    paths = [
        toolchain / "components/jdk/bin",
        toolchain / "components/cmake/bin",
        toolchain / "components/ninja",
        toolchain / "components/uv",
        toolchain / "android-sdk/cmdline-tools/latest/bin",
        toolchain / "android-sdk/platform-tools",
        toolchain / "android-sdk/build-tools/36.0.0",
    ]
    environment.update(
        {
            "JAVA_HOME": str(toolchain / "components/jdk"),
            "ANDROID_SDK_ROOT": str(toolchain / "android-sdk"),
            "ANDROID_HOME": str(toolchain / "android-sdk"),
            "ANDROID_USER_HOME": str(toolchain / "android-home"),
            "GRADLE_USER_HOME": str(toolchain / "gradle-home"),
            "UV_CACHE_DIR": str(toolchain / "uv-cache"),
            "UV_PROJECT_ENVIRONMENT": str(toolchain / "quality/venv"),
            "PRE_COMMIT_HOME": str(toolchain / "pre-commit"),
            "ADB_VENDOR_KEYS": str(toolchain / "adb"),
            "PATH": os.pathsep.join(str(path) for path in paths)
            + os.pathsep
            + environment.get("PATH", ""),
        }
    )
    return environment


def check_installed_file(
    artifact: dict[str, Any],
    specification: dict[str, Any],
    metadata: dict[str, Any],
    layout: Layout,
) -> Check | None:
    destination = artifact_destination(artifact, layout)
    checked = destination / str(specification["path"])
    kind = specification["kind"]
    if kind == "file" and not checked.is_file():
        return failed_artifact(artifact, checked, layout, "required file is missing")
    if kind == "executable" and (not checked.is_file() or not os.access(checked, os.X_OK)):
        return failed_artifact(artifact, checked, layout, "required executable is missing")
    expected_file = metadata["files"].get(str(specification["path"]))
    if checked.is_file() and (
        not isinstance(expected_file, dict)
        or expected_file.get("size") != checked.stat().st_size
        or expected_file.get("sha256") != sha256_file(checked)
    ):
        return failed_artifact(artifact, checked, layout, "installed file integrity differs")
    if kind == "file-contains":
        try:
            contents = checked.read_text(encoding="utf-8", errors="replace")
        except OSError:
            contents = ""
        if str(specification["contains"]) not in contents:
            return failed_artifact(artifact, checked, layout, "version metadata differs")
    if kind == "command" and not installed_command_matches(specification, checked, layout):
        return failed_artifact(artifact, checked, layout, "version command failed or differed")
    return None


def installed_command_matches(specification: dict[str, Any], checked: Path, layout: Layout) -> bool:
    try:
        result = subprocess.run(  # noqa: S603 - executable is a manifest-pinned local path.
            [checked, *specification.get("args", [])],
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
            env=local_environment(layout),
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return (
        result.returncode == 0 and str(specification["contains"]) in result.stdout + result.stderr
    )


def check_artifact(artifact: dict[str, Any], layout: Layout) -> Check:
    destination = artifact_destination(artifact, layout)
    marker = destination / MARKER
    try:
        metadata = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        metadata = {}
    expected_marker = {
        "id": artifact["id"],
        "version": artifact["version"],
        "sha256": artifact["sha256"],
    }
    if any(metadata.get(key) != value for key, value in expected_marker.items()) or not isinstance(
        metadata.get("files"), dict
    ):
        return Check(
            str(artifact["id"]),
            "fail",
            str(artifact["version"]),
            None,
            redact_path(destination, layout),
            "not installed or installation marker differs",
        )
    for specification in artifact["checks"]:
        failure = check_installed_file(artifact, specification, metadata, layout)
        if failure is not None:
            return failure
    return Check(
        str(artifact["id"]),
        "pass",
        str(artifact["version"]),
        str(artifact["version"]),
        redact_path(destination, layout),
        "pinned artifact verified",
    )


def failed_artifact(artifact: dict[str, Any], path: Path, layout: Layout, reason: str) -> Check:
    return Check(
        str(artifact["id"]),
        "fail",
        str(artifact["version"]),
        None,
        redact_path(path, layout),
        reason,
    )


def artifact_marker(artifact: dict[str, Any], destination: Path) -> dict[str, Any]:
    files: dict[str, dict[str, str | int]] = {}
    for specification in artifact["checks"]:
        relative = str(specification["path"])
        path = destination / relative
        if path.is_file():
            files[relative] = {"size": path.stat().st_size, "sha256": sha256_file(path)}
    return {
        "id": artifact["id"],
        "version": artifact["version"],
        "sha256": artifact["sha256"],
        "files": files,
    }


def publish_artifact(  # noqa: C901 - atomic repair has explicit rollback branches.
    artifact: dict[str, Any], archive: Path, layout: Layout, repair: bool
) -> None:
    destination = artifact_destination(artifact, layout)
    if check_artifact(artifact, layout).status == "pass":
        return
    if destination.exists() and not repair:
        raise BootstrapError(f"invalid {artifact['id']} installation; rerun with --repair")
    layout.staging.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=f"{artifact['id']}.", dir=layout.staging))
    payload = stage / "payload"
    backup = destination.with_name(destination.name + ".repair-backup")
    backed_up = False
    published = False
    try:
        extract_artifact(archive, payload, artifact)
        marker = artifact_marker(artifact, payload)
        (payload / MARKER).write_text(json.dumps(marker, sort_keys=True) + "\n", encoding="utf-8")
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            if backup.exists():
                shutil.rmtree(backup)
            os.replace(destination, backup)
            backed_up = True
        os.replace(payload, destination)
        published = True
        if check_artifact(artifact, layout).status != "pass":
            raise BootstrapError(f"installed {artifact['id']} did not pass validation")
        if artifact["destination"] == "@gradle-wrapper":
            (destination.parent / f"{artifact['filename']}.ok").touch()
        if backup.exists():
            shutil.rmtree(backup)
    except Exception:
        if published and destination.exists():
            shutil.rmtree(destination)
        if backed_up and backup.exists():
            os.replace(backup, destination)
        raise
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def atomic_write(path: Path, contents: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(contents, encoding="utf-8")
    os.replace(temporary, path)


def configure_project(layout: Layout, license_data: dict[str, Any]) -> None:
    jdk = layout.toolchain / "components/jdk"
    sdk = layout.toolchain / "android-sdk"
    atomic_write(layout.repo / "local.properties", f"sdk.dir={sdk}\n")
    atomic_write(
        layout.toolchain / "gradle-home/gradle.properties",
        "\n".join(
            (
                f"org.gradle.java.home={jdk}",
                "org.gradle.java.installations.auto-detect=false",
                "org.gradle.java.installations.auto-download=false",
                f"org.gradle.java.installations.paths={jdk}",
                "",
            )
        ),
    )
    for directory in (
        sdk / "licenses",
        layout.toolchain / "android-home",
        layout.toolchain / "adb",
    ):
        directory.mkdir(parents=True, exist_ok=True)
    atomic_write(
        sdk / "licenses/agent-relay-acceptance.json",
        json.dumps({**license_data, "accepted": True}, sort_keys=True) + "\n",
    )
    atomic_write(
        sdk / "licenses/android-sdk-license",
        "\n".join(str(value) for value in license_data["accepted_hashes"]) + "\n",
    )


def sync_quality_environment(layout: Layout, offline: bool) -> None:
    command: list[str | Path] = [
        layout.toolchain / "components/uv/uv",
        "sync",
        "--locked",
        "--only-group",
        "quality",
        "--only-group",
        "docs",
    ]
    if offline:
        command.append("--offline")
    result = subprocess.run(  # noqa: S603 - uv is a manifest-pinned local executable.
        command,
        cwd=layout.repo,
        env=local_environment(layout),
        check=False,
    )
    if result.returncode != 0:
        raise BootstrapError("repository-local uv dependency synchronization failed")


def install_target(
    manifest: dict[str, Any], target: str, layout: Layout, *, offline: bool, repair: bool
) -> None:
    required = int(manifest["minimum_free_bytes"])
    if shutil.disk_usage(layout.repo).free < required:
        raise BootstrapError(f"insufficient free space: need at least {required} bytes")
    for artifact in selected_artifacts(manifest, target):
        archive = download_artifact(artifact, layout, offline=offline, repair=repair)
        publish_artifact(artifact, archive, layout, repair)


def license_check(manifest: dict[str, Any], layout: Layout) -> Check:
    path = layout.toolchain / "android-sdk/licenses/agent-relay-acceptance.json"
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        value = {}
    expected = manifest["android_license"]
    passed = value.get("accepted") is True and value.get("revision") == expected["revision"]
    reason = (
        "explicit acceptance recorded"
        if passed
        else "rerun installation with --accept-android-sdk-license after reviewing the terms"
    )
    return Check(
        "android-sdk-license",
        "pass" if passed else "fail",
        str(expected["revision"]),
        str(value.get("revision")) if value else None,
        redact_path(path, layout),
        reason,
    )


def device_check(layout: Layout) -> Check:
    adb = layout.toolchain / "android-sdk/platform-tools/adb"
    try:
        result = subprocess.run(  # noqa: S603 - executable is a manifest-pinned local path.
            [adb, "devices"],
            check=False,
            capture_output=True,
            text=True,
            timeout=20,
            env=local_environment(layout),
        )
    except (OSError, subprocess.TimeoutExpired):
        result = None
    states = (
        []
        if result is None
        else [
            line.split("\t", 1)[1].strip()
            for line in result.stdout.splitlines()[1:]
            if "\t" in line
        ]
    )
    authorized = sum(state == "device" for state in states)
    passed = result is not None and result.returncode == 0 and len(states) == 1 and authorized == 1
    return Check(
        "android-device",
        "pass" if passed else "fail",
        "exactly one authorized device",
        f"{len(states)} connected, {authorized} authorized",
        redact_path(adb, layout),
        "device selection is unambiguous" if passed else "connect exactly one authorized device",
    )


def build_report(
    manifest: dict[str, Any],
    target: str,
    layout: Layout,
    *,
    install_requested: bool,
    install_performed: bool,
    offline: bool,
) -> Report:
    host = detect_host()
    checks = [check_artifact(item, layout) for item in selected_artifacts(manifest, target)]
    checks.append(license_check(manifest, layout))
    launcher = layout.repo / "scripts/with-toolchain"
    launcher_ok = launcher.is_file() and os.access(launcher, os.X_OK)
    checks.append(
        Check(
            "launcher",
            "pass" if launcher_ok else "fail",
            "repository executable",
            None,
            redact_path(launcher, layout),
            "launcher is executable" if launcher_ok else "launcher is missing or not executable",
        )
    )
    if target == "device":
        checks.append(device_check(layout))
    return Report(
        2,
        str(manifest["revision"]),
        target,
        host,
        tuple(checks),
        host.supported and all(check.status == "pass" for check in checks),
        install_requested,
        install_performed,
        offline,
    )


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--target", choices=TARGETS, default="build")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--yes", action="store_true")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--repair", action="store_true")
    parser.add_argument("--clean", action="store_true")
    parser.add_argument("--accept-android-sdk-license", action="store_true")
    parser.add_argument("--launcher-check", action="store_true", help=argparse.SUPPRESS)
    return parser.parse_args(argv)


def render(report: Report, json_output: bool) -> None:
    if json_output:
        print(json.dumps(asdict(report), indent=2, sort_keys=True))
        return
    print(f"Host: {report.host.system} {report.host.architecture} ({report.host.reason})")
    for check in report.checks:
        print(f"[{check.status.upper():4}] {check.name}: {check.reason}")
    print(f"Target {report.target}: {'ready' if report.ready else 'not ready'}")


def clean(layout: Layout) -> None:
    if layout.state.exists():
        shutil.rmtree(layout.state)
    (layout.repo / "local.properties").unlink(missing_ok=True)


def main(  # noqa: C901 - CLI safety combinations are intentionally explicit.
    argv: Sequence[str] | None = None,
) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    repo = args.repo_root.resolve()
    layout = Layout.create(repo)
    manifest_path = (args.manifest or repo / "config/bootstrap-toolchain.json").resolve()
    try:
        manifest = load_manifest(manifest_path)
        host = detect_host()
        if (
            not host.supported
            or f"{host.system}:{host.architecture}" not in manifest["supported_hosts"]
        ):
            raise BootstrapError(host.reason)
        if args.clean:
            if not args.yes:
                raise BootstrapError("refusing cleanup without --yes")
            clean(layout)
            return 0
        if args.repair and not args.install:
            raise BootstrapError("--repair requires --install")
        if args.install and not args.yes:
            raise BootstrapError("refusing installation without --yes")
        if args.install and not args.accept_android_sdk_license:
            terms = manifest["android_license"]["review_url"]
            raise BootstrapError(f"review {terms} and pass --accept-android-sdk-license")
        performed = False
        if args.install:
            install_target(manifest, args.target, layout, offline=args.offline, repair=args.repair)
            configure_project(layout, manifest["android_license"])
            if args.target == "quality":
                sync_quality_environment(layout, args.offline)
            performed = True
        report = build_report(
            manifest,
            args.target,
            layout,
            install_requested=args.install,
            install_performed=performed,
            offline=args.offline,
        )
        render(report, args.json and not args.launcher_check)
        return 0 if report.ready else 1
    except (BootstrapError, OSError, ValueError) as error:
        if not args.launcher_check:
            print(f"bootstrap: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
