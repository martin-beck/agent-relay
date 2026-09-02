#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
umask 022
export LC_ALL=C
export TZ=UTC

fail() {
  printf 'sherpa Android runtime: %s\n' "$*" >&2
  exit 1
}

source_dir=
onnx_dir=
metadata_patch=
onnx_license=
onnx_notices=
ndk_dir=
cmake_command=
ninja_command=
jni_output=
resources_output=
work_dir=
sherpa_version=
sherpa_revision=
sherpa_archive_sha256=
source_date_epoch=
onnx_version=
onnx_source_revision=
onnx_binary_revision=
onnx_archive_sha256=
ndk_version=
cmake_version=
ninja_version=

while (( $# > 0 )); do
  (( $# >= 2 )) || fail "missing value for $1"
  case "$1" in
    --source-dir) source_dir=$2 ;;
    --onnx-dir) onnx_dir=$2 ;;
    --metadata-patch) metadata_patch=$2 ;;
    --onnx-license) onnx_license=$2 ;;
    --onnx-notices) onnx_notices=$2 ;;
    --ndk-dir) ndk_dir=$2 ;;
    --cmake) cmake_command=$2 ;;
    --ninja) ninja_command=$2 ;;
    --jni-output) jni_output=$2 ;;
    --resources-output) resources_output=$2 ;;
    --work-dir) work_dir=$2 ;;
    --sherpa-version) sherpa_version=$2 ;;
    --sherpa-revision) sherpa_revision=$2 ;;
    --sherpa-archive-sha256) sherpa_archive_sha256=$2 ;;
    --source-date-epoch) source_date_epoch=$2 ;;
    --onnx-version) onnx_version=$2 ;;
    --onnx-source-revision) onnx_source_revision=$2 ;;
    --onnx-binary-revision) onnx_binary_revision=$2 ;;
    --onnx-archive-sha256) onnx_archive_sha256=$2 ;;
    --ndk-version) ndk_version=$2 ;;
    --cmake-version) cmake_version=$2 ;;
    --ninja-version) ninja_version=$2 ;;
    *) fail "unknown argument: $1" ;;
  esac
  shift 2
done

required_values=(
  "$source_dir"
  "$onnx_dir"
  "$metadata_patch"
  "$onnx_license"
  "$onnx_notices"
  "$ndk_dir"
  "$cmake_command"
  "$ninja_command"
  "$jni_output"
  "$resources_output"
  "$work_dir"
  "$sherpa_version"
  "$sherpa_revision"
  "$sherpa_archive_sha256"
  "$source_date_epoch"
  "$onnx_version"
  "$onnx_source_revision"
  "$onnx_binary_revision"
  "$onnx_archive_sha256"
  "$ndk_version"
  "$cmake_version"
  "$ninja_version"
)
for value in "${required_values[@]}"; do
  [[ -n "$value" ]] || fail "a required argument was empty"
done

[[ "$sherpa_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "invalid sherpa version"
[[ "$onnx_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "invalid ONNX Runtime version"
[[ "$sherpa_revision" =~ ^[0-9a-f]{40}$ ]] || fail "invalid sherpa revision"
[[ "$onnx_source_revision" =~ ^[0-9a-f]{40}$ ]] || fail "invalid ONNX source revision"
[[ "$onnx_binary_revision" =~ ^[0-9a-f]{40}$ ]] || fail "invalid ONNX binary revision"
[[ "$sherpa_archive_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "invalid sherpa archive digest"
[[ "$onnx_archive_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "invalid ONNX archive digest"
[[ "$source_date_epoch" =~ ^[0-9]{10}$ ]] || fail "invalid source date epoch"

source_dir=$(realpath -e -- "$source_dir")
onnx_dir=$(realpath -e -- "$onnx_dir")
metadata_patch=$(realpath -e -- "$metadata_patch")
onnx_license=$(realpath -e -- "$onnx_license")
onnx_notices=$(realpath -e -- "$onnx_notices")
ndk_dir=$(realpath -e -- "$ndk_dir")
[[ -d "$source_dir" && ! -L "$source_dir" ]] || fail "invalid sherpa source directory"
[[ -d "$onnx_dir" && ! -L "$onnx_dir" ]] || fail "invalid ONNX Runtime directory"
for input_file in "$metadata_patch" "$onnx_license" "$onnx_notices"; do
  [[ -f "$input_file" && ! -L "$input_file" ]] || fail "invalid input file"
done

prepare_output_path() {
  local requested=$1
  local expected_name=$2
  local parent
  mkdir -p -- "$(dirname -- "$requested")"
  parent=$(realpath -e -- "$(dirname -- "$requested")")
  requested="$parent/$(basename -- "$requested")"
  [[ "$(basename -- "$requested")" == "$expected_name" ]] ||
    fail "unexpected output directory name"
  case "$requested" in
    */speech/sherpa/build/generated/sherpa/runtime/"$expected_name") ;;
    *) fail "output directory escaped the sherpa module build tree" ;;
  esac
  printf '%s\n' "$requested"
}

jni_output=$(prepare_output_path "$jni_output" jni)
resources_output=$(prepare_output_path "$resources_output" resources)
mkdir -p -- "$work_dir"
work_dir=$(realpath -e -- "$work_dir")
case "$work_dir" in
  */speech/sherpa/build/tmp/*) ;;
  *) fail "work directory escaped the sherpa module build tree" ;;
esac

cmake=$(command -v "$cmake_command") || fail "cmake was not found"
ninja=$(command -v "$ninja_command") || fail "ninja was not found"
[[ "$("$cmake" --version | sed -n '1s/^cmake version //p')" == "$cmake_version" ]] ||
  fail "cmake version differed from the pinned version"
[[ "$("$ninja" --version)" == "$ninja_version" ]] ||
  fail "ninja version differed from the pinned version"
grep -Fxq "Pkg.Revision = $ndk_version" "$ndk_dir/source.properties" ||
  fail "Android NDK version differed from the pinned version"

toolchain_bin="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin"
readelf="$toolchain_bin/llvm-readelf"
nm="$toolchain_bin/llvm-nm"
strings="$toolchain_bin/llvm-strings"
strip="$toolchain_bin/llvm-strip"
for tool in "$readelf" "$nm" "$strings" "$strip"; do
  [[ -x "$tool" ]] || fail "required NDK validation tool was missing"
done
for tool in patch sha256sum grep sed awk find sort cp mv touch; do
  command -v "$tool" >/dev/null || fail "required host tool was missing: $tool"
done

verify_sha256() {
  local file=$1
  local expected=$2
  local actual
  actual=$(sha256sum "$file" | awk '{print $1}')
  [[ "$actual" == "$expected" ]] || fail "checksum differed for $(basename -- "$file")"
}

verify_sha256 "$onnx_license" \
  2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c
verify_sha256 "$onnx_notices" \
  0e07b95f3a8d6230037707c5c4a2b554d12c4cb67369669ac255635528ffcee2
verify_sha256 "$source_dir/LICENSE" \
  cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30

runtime_root="$work_dir/runtime-build"
case "$runtime_root" in
  */speech/sherpa/build/tmp/*/runtime-build) ;;
  *) fail "unsafe native build work directory" ;;
esac
rm -rf -- "$runtime_root"
mkdir -p -- "$runtime_root/source" "$runtime_root/jni" "$runtime_root/resources"
patched_source="$runtime_root/source"
staged_jni="$runtime_root/jni"
staged_resources="$runtime_root/resources"
cp -a -- "$source_dir/." "$patched_source/"
patch --directory="$patched_source" -p1 --batch --forward < "$metadata_patch"
grep -Fq 'if(NOT DEFINED SHERPA_ONNX_GIT_SHA1)' \
  "$patched_source/cmake/show-info.cmake" ||
  fail "reproducible metadata patch was not applied"

export SOURCE_DATE_EPOCH="$source_date_epoch"
jobs=$(getconf _NPROCESSORS_ONLN 2>/dev/null || printf '2\n')
(( jobs > 4 )) && jobs=4
(( jobs > 0 )) || jobs=1

abis=(arm64-v8a armeabi-v7a x86 x86_64)
declare -A expected_onnx_sha=(
  [arm64-v8a]=994848008526a934dfb579ac773b00e5867929234852b061005d45aacaee9533
  [armeabi-v7a]=d3699d357f763d31829e4b4bbb4fcb70ee4893f798e5b7f6070fb6ca9543eda9
  [x86]=eb674341f058a40b99173c32af13811c2d7cb212a33fa29cb24b2fe7217b02e6
  [x86_64]=7144a2015ce495677420b287cef908eb3d379e09913b5e715aa48826b2080f2d
)
declare -A jni_sha
declare -A onnx_sha

validate_elf() {
  local kind=$1
  local abi=$2
  local library=$3
  local elf_class
  local machine
  local dependency
  local align
  local required_class
  local required_machine

  elf_class=$("$readelf" -h "$library" | awk -F: '/Class:/{gsub(/[[:space:]]/, "", $2); print $2}')
  machine=$("$readelf" -h "$library" | awk -F: '/Machine:/{sub(/^[[:space:]]+/, "", $2); print $2}')
  case "$abi" in
    arm64-v8a)
      required_class=ELF64
      required_machine=AArch64
      ;;
    armeabi-v7a)
      required_class=ELF32
      required_machine=ARM
      ;;
    x86)
      required_class=ELF32
      required_machine="Intel 80386"
      ;;
    x86_64)
      required_class=ELF64
      required_machine="Advanced Micro Devices X86-64"
      ;;
    *) fail "unexpected ABI during ELF validation" ;;
  esac
  [[ "$elf_class" == "$required_class" ]] || fail "$kind had the wrong ELF class for $abi"
  [[ "$machine" == "$required_machine" ]] || fail "$kind had the wrong ELF machine for $abi"

  while IFS= read -r dependency; do
    case "$kind:$dependency" in
      jni:libandroid.so|jni:libc.so|jni:libdl.so|jni:liblog.so|jni:libm.so|jni:libonnxruntime.so) ;;
      onnx:libc.so|onnx:libdl.so|onnx:liblog.so|onnx:libm.so) ;;
      *) fail "$kind had an unexpected dynamic dependency for $abi: $dependency" ;;
    esac
  done < <(
    "$readelf" -dW "$library" |
      sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' |
      sort -u
  )
  if [[ "$kind" == jni ]]; then
    "$readelf" -dW "$library" | grep -F '[libonnxruntime.so]' >/dev/null ||
      fail "sherpa JNI did not depend on ONNX Runtime for $abi"
  fi

  "$readelf" -lW "$library" | grep -F GNU_RELRO >/dev/null ||
    fail "$kind lacked GNU_RELRO for $abi"
  "$readelf" -dW "$library" | grep -E 'BIND_NOW|FLAGS.*NOW' >/dev/null ||
    fail "$kind lacked immediate binding for $abi"
  if "$readelf" -dW "$library" | grep -F TEXTREL >/dev/null; then
    fail "$kind contained text relocations for $abi"
  fi
  if "$readelf" -lW "$library" | grep -E 'GNU_STACK.*RWE' >/dev/null; then
    fail "$kind requested an executable stack for $abi"
  fi

  while IFS= read -r align; do
    (( align >= 0x4000 )) || fail "$kind had sub-16-KiB LOAD alignment for $abi"
  done < <("$readelf" -lW "$library" | awk '$1 == "LOAD" {print $NF}')

  if [[ "$kind" == jni ]]; then
    local online_symbols
    online_symbols=$(
      "$nm" -D --defined-only "$library" |
        grep -Ec 'Java_com_k2fsa_sherpa_onnx_Online(Recognizer|Stream)'
    )
    (( online_symbols >= 10 )) || fail "sherpa JNI online API was incomplete for $abi"
    if "$strings" -a "$library" |
      grep -Ei 'OfflineTts|PiperPhonem|piper[-_]phonem|(^|[^[:alnum:]])espeak[-_]|libespeak|OfflineTtsVits|VitsModel' >/dev/null; then
      fail "sherpa JNI contained a forbidden TTS marker for $abi"
    fi
    if "$strings" -a "$library" | grep -F "$source_dir" >/dev/null; then
      fail "sherpa JNI leaked the source build path for $abi"
    fi
    if "$strings" -a "$library" | grep -F "$runtime_root" >/dev/null; then
      fail "sherpa JNI leaked the temporary build path for $abi"
    fi
  fi
}

for abi in "${abis[@]}"; do
  printf 'Building TTS-free sherpa Android runtime for %s\n' "$abi"
  build_dir="$runtime_root/build-$abi"
  source_map="-ffile-prefix-map=$patched_source=/usr/src/sherpa-onnx"
  debug_source_map="-fdebug-prefix-map=$patched_source=/usr/src/sherpa-onnx"
  build_map="-ffile-prefix-map=$build_dir=/usr/src/sherpa-onnx-build"
  debug_build_map="-fdebug-prefix-map=$build_dir=/usr/src/sherpa-onnx-build"
  hardening_flags="-fstack-protector-strong -D_FORTIFY_SOURCE=2 -fno-strict-overflow"
  prefix_flags="$source_map $debug_source_map $build_map $debug_build_map"
  linker_flags="-Wl,-z,relro,-z,now -Wl,--build-id=sha1 -Wl,-z,max-page-size=16384"

  export SHERPA_ONNXRUNTIME_LIB_DIR="$onnx_dir/jni/$abi"
  export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$onnx_dir/headers"
  "$cmake" \
    -S "$patched_source" \
    -B "$build_dir" \
    -G Ninja \
    "-DCMAKE_MAKE_PROGRAM=$ninja" \
    "-DCMAKE_TOOLCHAIN_FILE=$ndk_dir/build/cmake/android.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DANDROID_PLATFORM=android-28 \
    "-DANDROID_ABI=$abi" \
    -DANDROID_STL=c++_static \
    "-DCMAKE_C_FLAGS=$hardening_flags $prefix_flags" \
    "-DCMAKE_CXX_FLAGS=$hardening_flags $prefix_flags" \
    "-DCMAKE_SHARED_LINKER_FLAGS=$linker_flags" \
    "-DSHERPA_ONNX_GIT_SHA1=$sherpa_revision" \
    -DSHERPA_ONNX_GIT_DATE=2026-07-07T11:21:34Z \
    -DSHERPA_ONNX_ENABLE_TTS=OFF \
    -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
    -DSHERPA_ONNX_ENABLE_BINARY=OFF \
    -DSHERPA_ONNX_ENABLE_PYTHON=OFF \
    -DSHERPA_ONNX_ENABLE_TESTS=OFF \
    -DSHERPA_ONNX_ENABLE_CHECK=OFF \
    -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
    -DSHERPA_ONNX_ENABLE_JNI=ON \
    -DSHERPA_ONNX_ENABLE_C_API=OFF \
    -DSHERPA_ONNX_BUILD_C_API_EXAMPLES=OFF \
    -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF \
    -DSHERPA_ONNX_ENABLE_RKNN=OFF \
    -DSHERPA_ONNX_ENABLE_QNN=OFF \
    -DSHERPA_ONNX_ENABLE_AXERA=OFF \
    -DSHERPA_ONNX_ENABLE_AXCL=OFF \
    -DSHERPA_ONNX_ENABLE_ASCEND_NPU=OFF \
    -DSHERPA_ONNX_ENABLE_SPACEMIT=OFF \
    -DSHERPA_ONNX_ENABLE_GPU=OFF \
    -DSHERPA_ONNX_ENABLE_DIRECTML=OFF

  "$cmake" --build "$build_dir" --target sherpa-onnx-jni --parallel "$jobs"

  for forbidden_dependency in asio-src websocketpp-src piper_phonemize-src espeak_ng-src; do
    [[ ! -e "$build_dir/_deps/$forbidden_dependency" ]] ||
      fail "disabled dependency was fetched: $forbidden_dependency"
  done

  built_jni="$build_dir/lib/libsherpa-onnx-jni.so"
  source_onnx="$onnx_dir/jni/$abi/libonnxruntime.so"
  [[ -f "$built_jni" && ! -L "$built_jni" ]] || fail "sherpa JNI output was missing for $abi"
  [[ -f "$source_onnx" && ! -L "$source_onnx" ]] || fail "ONNX Runtime output was missing for $abi"
  verify_sha256 "$source_onnx" "${expected_onnx_sha[$abi]}"

  mkdir -p -- "$staged_jni/$abi"
  "$strip" --strip-unneeded "$built_jni" -o "$staged_jni/$abi/libsherpa-onnx-jni.so"
  cp -- "$source_onnx" "$staged_jni/$abi/libonnxruntime.so"
  chmod 0644 "$staged_jni/$abi/"*.so

  jni_size=$(stat -c %s "$staged_jni/$abi/libsherpa-onnx-jni.so")
  onnx_size=$(stat -c %s "$staged_jni/$abi/libonnxruntime.so")
  (( jni_size >= 1000000 && jni_size <= 15000000 )) ||
    fail "sherpa JNI size was outside its safety bounds for $abi"
  (( onnx_size >= 10000000 && onnx_size <= 40000000 )) ||
    fail "ONNX Runtime size was outside its safety bounds for $abi"

  validate_elf jni "$abi" "$staged_jni/$abi/libsherpa-onnx-jni.so"
  validate_elf onnx "$abi" "$staged_jni/$abi/libonnxruntime.so"
  jni_sha[$abi]=$(sha256sum "$staged_jni/$abi/libsherpa-onnx-jni.so" | awk '{print $1}')
  onnx_sha[$abi]=${expected_onnx_sha[$abi]}
done

license_root="$staged_resources/META-INF/licenses"
mkdir -p \
  "$license_root/sherpa-onnx" \
  "$license_root/onnxruntime" \
  "$license_root/kaldi-native-fbank" \
  "$license_root/kaldi-decoder" \
  "$license_root/kaldifst" \
  "$license_root/kissfft" \
  "$license_root/openfst" \
  "$license_root/nlohmann-json" \
  "$license_root/eigen" \
  "$license_root/simple-sentencepiece"
cp -- "$source_dir/LICENSE" "$license_root/sherpa-onnx/LICENSE"
cp -- "$onnx_license" "$license_root/onnxruntime/LICENSE"
cp -- "$onnx_notices" "$license_root/onnxruntime/ThirdPartyNotices.txt"

arm64_deps="$runtime_root/build-arm64-v8a/_deps"
license_specs=(
  "kaldi_native_fbank-src/LICENSE|cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30|kaldi-native-fbank/LICENSE"
  "kaldi_decoder-src/LICENSE|cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30|kaldi-decoder/LICENSE"
  "kaldifst-src/LICENSE|a682d6efd1ee5dee08a8e405c233c2c198ea70ae0718129daa83ab58cfe31c5d|kaldifst/LICENSE"
  "kissfft-src/COPYING|a2840585f8411be8e6826a31ef15ae65c950bd74a2437a73b013398a934ad0c6|kissfft/COPYING"
  "openfst-src/COPYING|4300529197035fd3452350718a0b8cee984e9412c9932d7f35fcde849fc97a4b|openfst/COPYING"
  "json-src/LICENSE.MIT|46a65cffd1ea955132d95a8dd921640714a8d6b537d2e4e482d31145ae95b603|nlohmann-json/LICENSE.MIT"
  "eigen-src/LICENSE|1f256ecad192880510e84ad60474eab7589218784b9a50bc7ceee34c2b91f1d5|eigen/LICENSE"
  "eigen-src/COPYING.APACHE|03379001a7b12a2ec997a25554247d985270b353c10d5bafee9ac8d6519820b7|eigen/COPYING.APACHE"
  "eigen-src/COPYING.BSD|51928dce36213c5333ba3172e847d735d4c6e9b7ff2722a326c49067155b82eb|eigen/COPYING.BSD"
  "eigen-src/COPYING.MINPACK|c87b7f8ee88f6195e91743820c00354833583aef091b72e2d4a49c8e28e798a0|eigen/COPYING.MINPACK"
  "eigen-src/COPYING.MPL2|66a3107d5ad6a058aab753eaac2047ccb2ed0e39465dd0fe5844da3e300d5172|eigen/COPYING.MPL2"
  "eigen-src/COPYING.README|db640ff2bd90c6abd6a4d3fbb351e0ee4d555417cf840492054d1cbb2ea85644|eigen/COPYING.README"
  "simple-sentencepiece-src/LICENSE|c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4|simple-sentencepiece/LICENSE"
)
for spec in "${license_specs[@]}"; do
  IFS='|' read -r relative expected destination <<< "$spec"
  dependency_license="$arm64_deps/$relative"
  [[ -f "$dependency_license" && ! -L "$dependency_license" ]] ||
    fail "expected dependency license was missing: $relative"
  verify_sha256 "$dependency_license" "$expected"
  cp -- "$dependency_license" "$license_root/$destination"
done

mkdir -p "$staged_resources/META-INF/sherpa-onnx"
{
  printf '%s\n' '# TTS-free sherpa-onnx Android runtime notices'
  printf '\n'
  printf '%s\n' 'This runtime contains the Apache-2.0 sherpa-onnx JNI implementation and'
  printf '%s\n' 'the dependencies whose complete license texts are adjacent to this file.'
  printf '%s\n' 'Text-to-speech, eSpeak NG, Piper, speaker diarization, WebSocket, and C API'
  printf '%s\n' 'features are disabled at build time and are not packaged.'
  printf '\n'
  printf '%s\n' 'ONNX Runtime is built from the Microsoft ONNX Runtime source by the pinned'
  printf '%s\n' 'onnxruntime-libs workflow revision. Its full ThirdPartyNotices.txt is included.'
} > "$staged_resources/META-INF/sherpa-onnx/THIRD_PARTY_NOTICES.md"

provenance="$staged_resources/META-INF/sherpa-onnx/runtime-provenance.json"
{
  printf '{\n'
  printf '  "schemaVersion": 1,\n'
  printf '  "sherpaOnnx": {\n'
  printf '    "version": "%s",\n' "$sherpa_version"
  printf '    "sourceRevision": "%s",\n' "$sherpa_revision"
  printf '    "sourceArchiveSha256": "%s"\n' "$sherpa_archive_sha256"
  printf '  },\n'
  printf '  "onnxRuntime": {\n'
  printf '    "version": "%s",\n' "$onnx_version"
  printf '    "sourceRevision": "%s",\n' "$onnx_source_revision"
  printf '    "binaryWorkflowRevision": "%s",\n' "$onnx_binary_revision"
  printf '    "archiveSha256": "%s"\n' "$onnx_archive_sha256"
  printf '  },\n'
  printf '  "toolchain": {\n'
  printf '    "androidNdk": "%s",\n' "$ndk_version"
  printf '    "cmake": "%s",\n' "$cmake_version"
  printf '    "ninja": "%s",\n' "$ninja_version"
  printf '    "sourceDateEpoch": %s\n' "$source_date_epoch"
  printf '  },\n'
  printf '  "features": {\n'
  printf '    "jni": true,\n'
  printf '    "tts": false,\n'
  printf '    "speakerDiarization": false,\n'
  printf '    "websocket": false,\n'
  printf '    "cApi": false\n'
  printf '  },\n'
  printf '  "libraries": {\n'
  for index in "${!abis[@]}"; do
    abi=${abis[$index]}
    comma=,
    (( index == ${#abis[@]} - 1 )) && comma=
    printf '    "%s": {"libsherpa-onnx-jni.so": "%s", "libonnxruntime.so": "%s"}%s\n' \
      "$abi" "${jni_sha[$abi]}" "${onnx_sha[$abi]}" "$comma"
  done
  printf '  }\n'
  printf '}\n'
} > "$provenance"

(
  cd "$staged_resources"
  find META-INF/licenses -type f -print0 |
    sort -z |
    xargs -0 sha256sum
) > "$staged_resources/META-INF/sherpa-onnx/license-sha256.txt"

find "$staged_jni" "$staged_resources" -exec touch -h -d "@$source_date_epoch" {} +
actual_libraries=$(find "$staged_jni" -type f -name '*.so' | wc -l)
(( actual_libraries == 8 )) || fail "native runtime contained an unexpected library count"
if find "$staged_jni" -type f ! -name '*.so' -print -quit | grep . >/dev/null; then
  fail "native runtime contained an unexpected file"
fi

jni_next="$jni_output.next"
resources_next="$resources_output.next"
rm -rf -- "$jni_next" "$resources_next"
mv -- "$staged_jni" "$jni_next"
mv -- "$staged_resources" "$resources_next"
rm -rf -- "$jni_output" "$resources_output"
mv -- "$jni_next" "$jni_output"
mv -- "$resources_next" "$resources_output"
rm -rf -- "$runtime_root"

printf 'Built and validated TTS-free sherpa Android runtime for 4 ABIs.\n'
