#!/bin/bash
# 构建脚本：需要 JDK 17 + Android SDK + Gradle 8.7
set -e
cd "$(dirname "$0")"

# 自动探测 JDK 17
for c in "$HOME/android-tools/jdk17/Contents/Home" "$HOME/android-tools/jdk-17/Contents/Home" \
         /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; do
  if [ -x "$c/bin/javac" ]; then export JAVA_HOME="$c"; break; fi
done
if [ -z "$JAVA_HOME" ]; then
  d="$(ls -d "$HOME"/android-tools/jdk* 2>/dev/null | head -1)"
  [ -x "$d/Contents/Home/bin/javac" ] && export JAVA_HOME="$d/Contents/Home"
fi
[ -x "$JAVA_HOME/bin/javac" ] || { echo "找不到 JDK 17"; exit 1; }

export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

"$HOME/android-tools/gradle-8.7/bin/gradle" --no-daemon \
  -Dorg.gradle.java.home="$JAVA_HOME" clean assembleRelease
