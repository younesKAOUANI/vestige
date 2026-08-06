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

# Entries may be a package directory (every *.java under it) or a single file - the latter for
# packages that mix a few dependency-free value types/enums in with JPA entities (gate/domain,
# ingestion/domain), the same way the main source tree itself mixes them: naming is "this is the
# domain model", not "this has no framework dependency", so this script has to be more precise
# than a directory for those.
MAIN_ENTRIES=(
  common/domain
  common/hash
  common/util
  gate/domain/ConditionOutcome.java
  gate/domain/ConditionType.java
  gate/domain/GateCondition.java
  gate/domain/GateInput.java
  gate/domain/GateOutcome.java
  gate/domain/GateStatus.java
  gate/domain/QualityGateDefinition.java
  gate/service
  github/service/NoopScmRenameResolver.java
  github/service/ScmRenameResolver.java
  github/service/WebhookSignatureVerifier.java
  ingestion/domain/RunStatus.java
  ingestion/sarif
  ingestion/worker
  matching
)
TEST_ENTRIES=(
  common
  gate
  github/WebhookSignatureVerifierTest.java
  ingestion/sarif
  ingestion/worker
  matching
)

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/test-classes" "$OUT/reports"

# Resolves each entry to its *.java files (recursively for a directory, itself for a file) and
# prints one path per line.
resolve() {
  local base=$1
  shift
  for entry in "$@"; do
    local path="$base/$entry"
    if [ -d "$path" ]; then
      find "$path" -name '*.java'
    elif [ -f "$path" ]; then
      echo "$path"
    else
      echo "expected a file or directory at $path (entry: $entry)" >&2
      exit 1
    fi
  done
  return 0
}

echo "== verifying package boundaries (no Spring/JPA/servlet imports in anything this script compiles)"
MAIN_SRC=$(resolve "$ROOT/src/main/java/dev/youneskaouani/vestige" "${MAIN_ENTRIES[@]}")
if echo "$MAIN_SRC" | xargs grep -l 'org\.springframework\|jakarta\.\(persistence\|servlet\|validation\)' 2>/dev/null; then
  echo "one or more MAIN_ENTRIES files above import Spring/JPA/servlet - fix the import, or if the" >&2
  echo "file genuinely needs the framework, remove it from MAIN_ENTRIES in this script" >&2
  exit 1
fi

TEST_SRC=$(resolve "$ROOT/src/test/java/dev/youneskaouani/vestige" "${TEST_ENTRIES[@]}")

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
