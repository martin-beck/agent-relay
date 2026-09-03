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
    --cmake cmake \
    --ninja ninja \
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
