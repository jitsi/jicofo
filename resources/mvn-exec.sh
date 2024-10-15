#!/bin/bash -e

if [[ ! "$JAVA_SYS_PROPS" == *"-Dconfig.file="* ]]; then
    echo
    echo "To run jicofo you need a configuration file. Use environment variable JAVA_SYS_PROPS."
    echo "e.g. export JAVA_SYS_PROPS=\"-Dconfig.file=/etc/jitsi/jicofo/jicofo.conf\""
    echo
    exit 2
fi

exec mvn ${JAVA_SYS_PROPS} compile exec:java -pl jicofo -Dexec.mainClass=org.jitsi.jicofo.Main
