#!/usr/bin/env bats

setup() {
  REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd -P)"
  SCRIPT="$REPO_ROOT/speech/sherpa/native/build-android-runtime.sh"
  TEST_ROOT="$(mktemp -d "${BATS_TEST_TMPDIR}/native.XXXXXX")"
  mkdir -p "$TEST_ROOT/source" "$TEST_ROOT/onnx" "$TEST_ROOT/ndk" \
    "$TEST_ROOT/output/speech/sherpa/build/generated/sherpa/runtime" \
    "$TEST_ROOT/output/speech/sherpa/build/tmp/case"
  touch "$TEST_ROOT/metadata.patch" "$TEST_ROOT/onnx-license" "$TEST_ROOT/onnx-notices"
}

base_arguments() {
  printf '%s\n' \
    --source-dir "$TEST_ROOT/source" \
    --onnx-dir "$TEST_ROOT/onnx" \
    --metadata-patch "$TEST_ROOT/metadata.patch" \
    --onnx-license "$TEST_ROOT/onnx-license" \
    --onnx-notices "$TEST_ROOT/onnx-notices" \
    --ndk-dir "$TEST_ROOT/ndk" \
    --cmake "${CMAKE_COMMAND:-cmake}" \
    --ninja "${NINJA_COMMAND:-ninja}" \
    --jni-output "$TEST_ROOT/output/speech/sherpa/build/generated/sherpa/runtime/jni" \
    --resources-output "$TEST_ROOT/output/speech/sherpa/build/generated/sherpa/runtime/resources" \
    --work-dir "$TEST_ROOT/output/speech/sherpa/build/tmp/case" \
    --sherpa-version 1.2.3 \
    --sherpa-revision 1111111111111111111111111111111111111111 \
    --sherpa-archive-sha256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    --source-date-epoch 1751887294 \
    --onnx-version 1.2.3 \
    --onnx-source-revision 2222222222222222222222222222222222222222 \
    --onnx-binary-revision 3333333333333333333333333333333333333333 \
    --onnx-archive-sha256 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
    --ndk-version 28.2.13676358 \
    --cmake-version 3.28.3 \
    --ninja-version 1.11.1
}

prepare_fake_build() {
  local tool
  mkdir -p \
    "$TEST_ROOT/source/cmake" \
    "$TEST_ROOT/fake-bin" \
    "$TEST_ROOT/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin" \
    "$TEST_ROOT/output/speech/sherpa/build/generated/sherpa/runtime/jni" \
    "$TEST_ROOT/output/speech/sherpa/build/generated/sherpa/runtime/resources" \
    "$TEST_ROOT/output/speech/sherpa/build/tmp/case/runtime-build"
  printf '%s\n' 'if(NOT DEFINED SHERPA_ONNX_GIT_SHA1)' > "$TEST_ROOT/source/cmake/show-info.cmake"
  printf '%s\n' 'test license' > "$TEST_ROOT/source/LICENSE"
  printf '%s\n' 'Pkg.Revision = 28.2.13676358' > "$TEST_ROOT/ndk/source.properties"
  printf '%s\n' 'old JNI output' > \
    "$TEST_ROOT/output/speech/sherpa/build/generated/sherpa/runtime/jni/sentinel"
  printf '%s\n' 'old resources output' > \
    "$TEST_ROOT/output/speech/sherpa/build/generated/sherpa/runtime/resources/sentinel"
  printf '%s\n' 'stale work content' > \
    "$TEST_ROOT/output/speech/sherpa/build/tmp/case/runtime-build/stale"

  cat > "$TEST_ROOT/fake-bin/fake-cmake" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == --version ]]; then
  printf 'cmake version 3.28.3\n'
  exit 0
fi
if [[ "${1:-}" == --build ]]; then
  [[ "${FAKE_CMAKE_MODE:-}" != build-fail ]] || exit 48
  exit 0
fi
while (($# > 0)); do
  if [[ "$1" == -B ]]; then
    mkdir -p -- "$2"
    break
  fi
  shift
done
[[ "${FAKE_CMAKE_MODE:-}" != configure-fail ]] || exit 47
EOF
  cat > "$TEST_ROOT/fake-bin/fake-ninja" <<'EOF'
#!/usr/bin/env bash
printf '1.11.1\n'
EOF
  cat > "$TEST_ROOT/fake-bin/patch" <<'EOF'
#!/usr/bin/env bash
cat > /dev/null
EOF
  cat > "$TEST_ROOT/fake-bin/sha256sum" <<'EOF'
#!/usr/bin/env bash
case "${1:-}" in
  */onnx-license)
    printf '2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c  %s\n' "$1"
    ;;
  */onnx-notices)
    printf '0e07b95f3a8d6230037707c5c4a2b554d12c4cb67369669ac255635528ffcee2  %s\n' "$1"
    ;;
  */LICENSE)
    printf 'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30  %s\n' "$1"
    ;;
  *) exec /usr/bin/sha256sum "$@" ;;
esac
EOF
  chmod +x "$TEST_ROOT/fake-bin/"*
  for tool in llvm-readelf llvm-nm llvm-strings llvm-strip; do
    printf '#!/usr/bin/env bash\nexit 0\n' > \
      "$TEST_ROOT/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/$tool"
    chmod +x "$TEST_ROOT/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/$tool"
  done
  CMAKE_COMMAND=fake-cmake
  NINJA_COMMAND=fake-ninja
}

assert_published_outputs_unchanged() {
  run cat "$TEST_ROOT/output/speech/sherpa/build/generated/sherpa/runtime/jni/sentinel"
  [ "$status" -eq 0 ]
  [ "$output" = "old JNI output" ]
  run cat "$TEST_ROOT/output/speech/sherpa/build/generated/sherpa/runtime/resources/sentinel"
  [ "$status" -eq 0 ]
  [ "$output" = "old resources output" ]
  [ ! -e "$TEST_ROOT/output/speech/sherpa/build/generated/sherpa/runtime/jni.next" ]
  [ ! -e "$TEST_ROOT/output/speech/sherpa/build/generated/sherpa/runtime/resources.next" ]
  [ ! -e "$TEST_ROOT/output/speech/sherpa/build/tmp/case/runtime-build/stale" ]
}

@test "native runtime rejects a dangling option" {
  run "$SCRIPT" --source-dir
  [ "$status" -eq 1 ]
  [[ "$output" == *"missing value for --source-dir"* ]]
}

@test "native runtime rejects an unknown option" {
  run "$SCRIPT" --hostile value
  [ "$status" -eq 1 ]
  [[ "$output" == *"unknown argument: --hostile"* ]]
}

@test "native runtime rejects malformed immutable provenance" {
  mapfile -t arguments < <(base_arguments)
  arguments[23]="not-a-version"
  run "$SCRIPT" "${arguments[@]}"
  [ "$status" -eq 1 ]
  [[ "$output" == *"invalid sherpa version"* ]]
}

@test "native runtime rejects symlinked source input" {
  mapfile -t arguments < <(base_arguments)
  mkdir "$TEST_ROOT/real-source"
  rm -rf "$TEST_ROOT/source"
  ln -s "$TEST_ROOT/real-source" "$TEST_ROOT/source"
  run "$SCRIPT" "${arguments[@]}"
  [ "$status" -eq 1 ]
  [[ "$output" == *"invalid sherpa source directory"* ]]
}

@test "native runtime rejects a symlinked source with a dot suffix" {
  mapfile -t arguments < <(base_arguments)
  mkdir "$TEST_ROOT/real-source"
  rm -rf "$TEST_ROOT/source"
  ln -s "$TEST_ROOT/real-source" "$TEST_ROOT/source"
  arguments[1]="$TEST_ROOT/source/."
  run "$SCRIPT" "${arguments[@]}"
  [ "$status" -eq 1 ]
  [[ "$output" == *"invalid sherpa source directory"* ]]
}

@test "native runtime preserves published output when configure fails" {
  prepare_fake_build
  mapfile -t arguments < <(base_arguments)
  run env PATH="$TEST_ROOT/fake-bin:$PATH" FAKE_CMAKE_MODE=configure-fail \
    "$SCRIPT" "${arguments[@]}"
  [ "$status" -eq 47 ]
  [[ "$output" == *"Building TTS-free sherpa Android runtime for arm64-v8a"* ]]
  assert_published_outputs_unchanged
}

@test "native runtime preserves published output when the build fails" {
  prepare_fake_build
  mapfile -t arguments < <(base_arguments)
  run env PATH="$TEST_ROOT/fake-bin:$PATH" FAKE_CMAKE_MODE=build-fail \
    "$SCRIPT" "${arguments[@]}"
  [ "$status" -eq 48 ]
  [[ "$output" == *"Building TTS-free sherpa Android runtime for arm64-v8a"* ]]
  assert_published_outputs_unchanged
}

@test "native runtime rejects a successful build with no JNI artifact" {
  prepare_fake_build
  mapfile -t arguments < <(base_arguments)
  run env PATH="$TEST_ROOT/fake-bin:$PATH" FAKE_CMAKE_MODE=missing-artifact \
    "$SCRIPT" "${arguments[@]}"
  [ "$status" -eq 1 ]
  [[ "$output" == *"sherpa JNI output was missing for arm64-v8a"* ]]
  assert_published_outputs_unchanged
}
