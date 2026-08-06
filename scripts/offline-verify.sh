#!/usr/bin/env bash
# Compiles and runs the dependency-free core of Vestige without Maven or network access.
#
# This exists because the sandbox this project was originally built in has Maven Central blocked
# by egress policy, so `mvn test` cannot resolve Spring Boot at all (see README "A note on how
# this was built"). It is not a replacement for `mvn test` - it is a narrower, honest thing: proof
# that the packages with no framework dependency (the fingerprint-ladder matcher, the SARIF
# streaming reader, the hash chain, the quality gate evaluator, the webhook verifier, and the
# matcher-corpus harness) compile clean and pass, using only the JDK plus JUnit 5 / AssertJ /
# Jackson - all available as plain Debian/Ubuntu packages, with no Maven Central round trip.
#
# Everything that depends on Spring, JPA or the servlet API (controllers, entities, RLS wiring,
# the outbox worker) is out of scope for this script by construction: there is no offline JDK-only
# path to verify those, and this script does not pretend otherwise. Run it with:
#
#   scripts/offline-verify.sh
#
# Requires: a JDK, and JUnit 5 / AssertJ / Jackson on the system (this repo was verified against
# the `junit5`, `libassertj-core-java` and `libjackson2-*-java` packages on Debian/Ubuntu, found
# under /usr/share/java). Override JARS= to point at a different install.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT=${OUT:-"$ROOT/build-offline"}
JARS=${JARS:-/usr/share/java}

CP="$JARS/junit-platform-console-standalone.jar:$JARS/assertj-core.jar:$JARS/jackson-databind.jar:$JARS/jackson-core.jar:$JARS/jackson-annotations.jar"

for jar in junit-platform-console-standalone assertj-core jackson-databind jackson-core jackson-annotations; do
  if [ ! -f "$JARS/$jar.jar" ]; then
    echo "missing $JARS/$jar.jar - see this script's header comment for what it expects" >&2
    exit 1
  fi
done

# Every package here is verified below (see "verifying package boundaries") to contain no import
# of org.springframework.*, jakarta.persistence.* or jakarta.servlet.*/jakarta.validation.*.
# Sibling packages under the same parent (common/config, common/error, ingestion/domain, most of
# tenancy) do depend on Spring/JPA and are deliberately not listed.
MAIN_PACKAGES=(
  common/domain
  common/hash
  common/util
  gate/domain
  gate/service
  github/service
  ingestion/sarif
  ingestion/worker
  matching
)
TEST_PACKAGES=(
  common
  gate
  github
  ingestion/sarif
  ingestion/worker
  matching
)

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/test-classes" "$OUT/reports"

collect() {
  local base=$1
  shift
  for pkg in "$@"; do
    [ -d "$base/$pkg" ] && find "$base/$pkg" -name '*.java'
  done
  return 0
}

echo "== verifying package boundaries (no Spring/JPA/servlet imports under the packages this script compiles)"
for pkg in "${MAIN_PACKAGES[@]}"; do
  if grep -rl 'org\.springframework\|jakarta\.\(persistence\|servlet\|validation\)' \
      "$ROOT/src/main/java/dev/youneskaouani/vestige/$pkg" 2>/dev/null; then
    echo "$pkg contains a Spring/JPA/servlet import - remove it from MAIN_PACKAGES or fix the import" >&2
    exit 1
  fi
done

MAIN_SRC=$(collect "$ROOT/src/main/java/dev/youneskaouani/vestige" "${MAIN_PACKAGES[@]}")
TEST_SRC=$(collect "$ROOT/src/test/java/dev/youneskaouani/vestige" "${TEST_PACKAGES[@]}")

echo "== compiling main ($(echo "$MAIN_SRC" | wc -l) files)"
# shellcheck disable=SC2086
javac -Xlint:all,-serial,-processing -Werror --release 21 -d "$OUT/classes" -cp "$CP" $MAIN_SRC

if [ -n "$TEST_SRC" ]; then
  echo "== compiling tests ($(echo "$TEST_SRC" | wc -l) files)"
  # shellcheck disable=SC2086
  javac --release 21 -d "$OUT/test-classes" -cp "$CP:$OUT/classes" $TEST_SRC

  echo "== running tests"
  java -jar "$JARS/junit-platform-console-standalone.jar" execute \
    --class-path "$OUT/classes:$OUT/test-classes:$JARS/assertj-core.jar:$JARS/jackson-databind.jar:$JARS/jackson-core.jar:$JARS/jackson-annotations.jar" \
    --scan-class-path "$OUT/test-classes" \
    --details=summary \
    --disable-banner \
    --reports-dir "$OUT/reports"
fi
