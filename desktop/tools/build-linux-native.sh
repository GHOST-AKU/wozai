#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
if [ "$(uname -s)" != Linux ]; then echo 'This build requires Linux.' >&2; exit 1; fi
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
fi
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then echo 'Set JAVA_HOME to a Linux JDK 17 or newer.' >&2; exit 1; fi
# Overrides support local toolchains; regular builds use distribution pkg-config metadata.
if [ -z "${GIO_CFLAGS:-}" ]; then GIO_CFLAGS=$(pkg-config --cflags gio-unix-2.0); fi
if [ -z "${GIO_LIBS:-}" ]; then GIO_LIBS=$(pkg-config --libs gio-unix-2.0); fi
mkdir -p build/native build/lib
# pkg-config emits a list of compiler arguments. Keep JNI paths separately quoted.
${CXX:-c++} -std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror -pthread -fPIC -shared \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    $GIO_CFLAGS native/linux_bluetooth.cpp $GIO_LIBS -o build/lib/libwozai_bluetooth.so
${CXX:-c++} -std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror -pthread \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    $GIO_CFLAGS native/linux_bluetooth_tests.cpp $GIO_LIBS -o build/native/linux_bluetooth_tests
if [ "${1:-}" != --compile-only ]; then
    dbus-run-session -- build/native/linux_bluetooth_tests "$@"
fi
