#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

VARIANTS=("minimal" "medium" "full")
INCLUDE_DESKTOP="${SQLCOPILOT_INCLUDE_DESKTOP:-1}"
EXPORT_BACKEND="${SQLCOPILOT_EXPORT_BACKEND:-0}"
DESKTOP_BACKEND_STAGE_DIR="apps/desktop/resources/backend"
HAS_NATIVE_IMAGE=0
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/native-image" ]]; then
  HAS_NATIVE_IMAGE=1
elif command -v native-image >/dev/null 2>&1; then
  HAS_NATIVE_IMAGE=1
fi

cleanup_stage_dir() {
  rm -rf "${DESKTOP_BACKEND_STAGE_DIR}"
  mkdir -p "${DESKTOP_BACKEND_STAGE_DIR}"
  touch "${DESKTOP_BACKEND_STAGE_DIR}/.gitkeep"
}

prepare_backend_runtime() {
  local target_dir="$1"
  local variant="$2"

  rm -rf "${target_dir}"
  mkdir -p "${target_dir}"

  if compgen -G "apps/server/target/sql-copilot-server*" >/dev/null; then
    cp apps/server/target/sql-copilot-server* "${target_dir}/"
  fi
  if compgen -G "apps/server/target/*.jar" >/dev/null; then
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
    echo "==> [${variant}] backend native build"
    mvn -f apps/server/pom.xml -Pnative,pack-"${variant}" clean native:compile -DskipTests
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
    if [[ -n "${SQLCOPILOT_ELECTRON_DIST:-}" ]]; then
      npm run -w @sqlcopilot/desktop build:"${variant}"
      (
        cd apps/desktop
        SQLCOPILOT_PACKAGE_VARIANT="${variant}" npx electron-builder --config.electronDist="${SQLCOPILOT_ELECTRON_DIST}"
      )
    else
      npm run -w @sqlcopilot/desktop dist:"${variant}"
    fi
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
