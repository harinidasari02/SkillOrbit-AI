#!/bin/sh
# Runs the dataset tools with plain JDK 17+ (no Maven, no Spring, no database).
#   scripts/synthetic.sh generate --out synthetic-output/starter --starter
#   scripts/synthetic.sh generate --out synthetic-output/full
#   scripts/synthetic.sh validate --out synthetic-output/starter
#   scripts/synthetic.sh repro --out synthetic-output/repro --starter
#   scripts/synthetic.sh selftest
set -e
cd "$(dirname "$0")/.."
mkdir -p target/synthetic-classes
javac --release 17 -d target/synthetic-classes src/main/java/com/skillorbit/skillorbit_ai/synthetic/*.java
exec java -cp target/synthetic-classes com.skillorbit.skillorbit_ai.synthetic.SyntheticCli "$@"
