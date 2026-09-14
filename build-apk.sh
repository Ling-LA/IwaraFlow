#!/usr/bin/env bash
# IwaraFlow 一键构建（Linux / macOS）。和 build-apk.bat 一个思路：
# 有 ./gradlew 就用它，系统装了 gradle 就用它，都没有就按
# gradle/wrapper/gradle-wrapper.properties 里写的版本下载一份放到 ~/.gradle/iwaraflow-dist。
set -euo pipefail

cd "$(dirname "$0")"
task="${1:-assembleDebug}"

command -v java >/dev/null 2>&1 || { echo "[ERROR] 没找到 Java，请先安装 JDK 17 或更新版本"; exit 1; }

if [ -x ./gradlew ]; then
  exec ./gradlew "$task"
fi

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$task"
fi

props="gradle/wrapper/gradle-wrapper.properties"
[ -f "$props" ] || { echo "[ERROR] 找不到 $props，无法确定 Gradle 版本"; exit 1; }

dist_url="$(sed -n 's/^distributionUrl=//p' "$props" | sed 's/\\:/:/g')"
dist_zip="$(basename "$dist_url")"
dist_name="${dist_zip%-bin.zip}"
home_dir="$HOME/.gradle/iwaraflow-dist"
gradle_bin="$home_dir/$dist_name/bin/gradle"

if [ ! -x "$gradle_bin" ]; then
  echo "[INFO] 正在下载 $dist_zip ……（只需一次，约 100 MB）"
  mkdir -p "$home_dir"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$dist_url" -o "$home_dir/$dist_zip"
  else
    wget -q "$dist_url" -O "$home_dir/$dist_zip"
  fi
  unzip -q "$home_dir/$dist_zip" -d "$home_dir"
  rm -f "$home_dir/$dist_zip"
fi

exec "$gradle_bin" "$task"
