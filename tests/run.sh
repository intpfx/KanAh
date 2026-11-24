#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/policy-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d build/policy-tests app/src/main/java/com/live/tv/Channels.java app/src/main/java/com/live/tv/PlaybackGate.java app/src/main/java/com/live/tv/Channel.java app/src/main/java/com/live/tv/M3u.java tests/ChannelsTest.java tests/PlaybackGateTest.java tests/M3uTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp build/policy-tests ChannelsTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp build/policy-tests PlaybackGateTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Djava.awt.headless=true -cp build/policy-tests M3uTest

node --test tests/*.test.mjs
