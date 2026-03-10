#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DEFAULT_VARIANTS=("minimal" "medium" "full")
VARIANTS=()
INCLUDE_DESKTOP="${SQLCOPILOT_INCLUDE_DESKTOP:-1}"
EXPORT_BACKEND="${SQLCOPILOT_EXPORT_BACKEND:-0}"
DESKTOP_BACKEND_STAGE_DIR="apps/desktop/resources/backend"
HAS_NATIVE_IMAGE=0
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/native-image" ]]; then
  HAS_NATIVE_IMAGE=1
elif command -v native-image >/dev/null 2>&1; then
  HAS_NATIVE_IMAGE=1
fi

normalize_variant() {
  local raw="$1"
  local lowered
  lowered="$(echo "$raw" | tr '[:upper:]' '[:lower:]' | xargs)"
  case "$lowered" in
    minimal|medium|full)
      echo "$lowered"
      ;;
    *)
      echo ""
      ;;
  esac
}

if [[ "$#" -gt 0 ]]; then
  for arg in "$@"; do
    normalized="$(normalize_variant "$arg")"
    if [[ -z "$normalized" ]]; then
      echo "Invalid variant: $arg. Allowed values: minimal|medium|full" >&2
      exit 1
    fi
    VARIANTS+=("$normalized")
  done
elif [[ -n "${SQLCOPILOT_VARIANTS:-}" ]]; then
  IFS=',' read -r -a input_variants <<< "${SQLCOPILOT_VARIANTS}"
  for item in "${input_variants[@]}"; do
    normalized="$(normalize_variant "$item")"
    if [[ -z "$normalized" ]]; then
      echo "Invalid variant in SQLCOPILOT_VARIANTS: $item. Allowed values: minimal|medium|full" >&2
      exit 1
    fi
    VARIANTS+=("$normalized")
  done
else
  VARIANTS=("${DEFAULT_VARIANTS[@]}")
fi

cleanup_stage_dir() {
  rm -rf "${DESKTOP_BACKEND_STAGE_DIR}"
  mkdir -p "${DESKTOP_BACKEND_STAGE_DIR}"
  touch "${DESKTOP_BACKEND_STAGE_DIR}/.gitkeep"
}

resolve_native_image_xmx() {
  local variant="$1"
  if [[ -n "${SQLCOPILOT_NATIVE_IMAGE_XMX:-}" ]]; then
    echo "${SQLCOPILOT_NATIVE_IMAGE_XMX}"
    return
  fi
  case "$variant" in
    minimal)
      echo "4g"
      ;;
    medium)
      echo "5g"
      ;;
    full)
      echo "6g"
      ;;
    *)
      echo "4g"
      ;;
  esac
}

resolve_native_image_threads() {
  local variant="$1"
  if [[ -n "${SQLCOPILOT_NATIVE_IMAGE_PARALLELISM:-}" ]]; then
    echo "${SQLCOPILOT_NATIVE_IMAGE_PARALLELISM}"
    return
  fi
  if [[ -n "${SQLCOPILOT_NATIVE_IMAGE_THREADS:-}" ]]; then
    echo "${SQLCOPILOT_NATIVE_IMAGE_THREADS}"
    return
  fi
  case "$variant" in
    full)
      echo "4"
      ;;
    *)
      echo "3"
      ;;
  esac
}

prepare_backend_runtime() {
  local target_dir="$1"
  local variant="$2"
  local native_copied=0

  rm -rf "${target_dir}"
  mkdir -p "${target_dir}"

  if [[ -x "apps/server/target/sql-copilot-server" ]]; then
    cp "apps/server/target/sql-copilot-server" "${target_dir}/"
    native_copied=1
  elif [[ -f "apps/server/target/sql-copilot-server.exe" ]]; then
    cp "apps/server/target/sql-copilot-server.exe" "${target_dir}/"
    native_copied=1
  fi

  # Native artifact exists: do not bundle fallback jars to avoid bloating desktop packages.
  if [[ "${native_copied}" -eq 0 ]] && compgen -G "apps/server/target/*.jar" >/dev/null; then
    cp apps/server/target/*.jar "${target_dir}/"
  fi
  cp apps/server/src/main/resources/application.yml "${target_dir}/"
  cp "apps/server/src/main/resources/application-${variant}.yml" "${target_dir}/"

  cat > "${target_dir}/run.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${BASE_DIR}"
if [[ -x "${BASE_DIR}/sql-copilot-server" ]]; then
  exec "${BASE_DIR}/sql-copilot-server" --spring.profiles.active="${1:-__VARIANT__}"
fi
if compgen -G "${BASE_DIR}/*.jar" > /dev/null; then
  JAR_FILE="$(ls "${BASE_DIR}"/*.jar | head -n 1)"
  exec java -jar "${JAR_FILE}" --spring.profiles.active="${1:-__VARIANT__}"
fi
echo "No runnable backend artifact found in ${BASE_DIR}" >&2
exit 1
EOF
  sed -i.bak "s/__VARIANT__/${variant}/g" "${target_dir}/run.sh"
  rm -f "${target_dir}/run.sh.bak"
  chmod +x "${target_dir}/run.sh"

  cat > "${target_dir}/run.cmd" <<'EOF'
@echo off
setlocal enabledelayedexpansion
set "BASE_DIR=%~dp0"
set "PROFILE=%~1"
if "%PROFILE%"=="" set "PROFILE=__VARIANT__"
if exist "%BASE_DIR%sql-copilot-server.exe" (
  "%BASE_DIR%sql-copilot-server.exe" --spring.profiles.active=%PROFILE%
  exit /b %ERRORLEVEL%
)
for %%f in ("%BASE_DIR%*.jar") do (
  java -jar "%%f" --spring.profiles.active=%PROFILE%
  exit /b %ERRORLEVEL%
)
echo No runnable backend artifact found in %BASE_DIR%
exit /b 1
EOF
  sed -i.bak "s/__VARIANT__/${variant}/g" "${target_dir}/run.cmd"
  rm -f "${target_dir}/run.cmd.bak"

  echo "${variant}" > "${target_dir}/variant"

  if [[ "${variant}" == "full" ]]; then
    rm -rf "${target_dir}/models"
    cp -R apps/server/models "${target_dir}/models"
  fi
}

trap cleanup_stage_dir EXIT
cleanup_stage_dir

for variant in "${VARIANTS[@]}"; do
  backend_release_dir="release/${variant}/backend"
  if [[ "${HAS_NATIVE_IMAGE}" -eq 1 ]]; then
    native_image_xms="${SQLCOPILOT_NATIVE_IMAGE_XMS:-1g}"
    native_image_xmx="$(resolve_native_image_xmx "${variant}")"
    native_image_threads="$(resolve_native_image_threads "${variant}")"
    echo "==> [${variant}] backend native build"
    echo "    native-image args: xms=${native_image_xms}, xmx=${native_image_xmx}, threads=${native_image_threads}"
    mvn -f apps/server/pom.xml -Pnative,pack-"${variant}" clean native:compile -DskipTests \
      -Dsqlcopilot.native.image.jvm.xms="${native_image_xms}" \
      -Dsqlcopilot.native.image.jvm.xmx="${native_image_xmx}" \
      -Dsqlcopilot.native.image.threads="${native_image_threads}"
  else
    echo "==> [${variant}] backend package build (native-image not found, fallback to jar)"
    mvn -f apps/server/pom.xml -Ppack-"${variant}" clean package -DskipTests
  fi

  if [[ "${INCLUDE_DESKTOP}" == "1" ]]; then
    prepare_backend_runtime "${DESKTOP_BACKEND_STAGE_DIR}" "${variant}"
  else
    cleanup_stage_dir
  fi

  if [[ "${EXPORT_BACKEND}" == "1" ]]; then
    prepare_backend_runtime "${backend_release_dir}" "${variant}"
  else
    rm -rf "${backend_release_dir}"
  fi

  if [[ "${INCLUDE_DESKTOP}" == "1" ]]; then
    echo "==> [${variant}] desktop type-check + dist"
    npm run -w @sqlcopilot/desktop type-check
    npm run -w @sqlcopilot/desktop build:"${variant}"
    (
      cd apps/desktop
      if [[ "${SQLCOPILOT_MAC_SIGN:-0}" != "1" ]]; then
        export CSC_IDENTITY_AUTO_DISCOVERY=false
      fi
      if [[ -n "${SQLCOPILOT_ELECTRON_DIST:-}" ]]; then
        SQLCOPILOT_PACKAGE_VARIANT="${variant}" npx electron-builder --config.electronDist="${SQLCOPILOT_ELECTRON_DIST}"
      else
        SQLCOPILOT_PACKAGE_VARIANT="${variant}" npx electron-builder
      fi
    )
  fi
done

if [[ "${INCLUDE_DESKTOP}" == "1" && "${EXPORT_BACKEND}" == "1" ]]; then
  echo "All variants packaged under release/{minimal,medium,full}/{backend,desktop}"
elif [[ "${INCLUDE_DESKTOP}" == "1" ]]; then
  echo "Desktop variants packaged under release/{minimal,medium,full}/desktop (backend packaged into app resources)"
elif [[ "${EXPORT_BACKEND}" == "1" ]]; then
  echo "Backend variants packaged under release/{minimal,medium,full}/backend"
else
  echo "No release artifacts exported (backend build executed as intermediate only)"
fi
