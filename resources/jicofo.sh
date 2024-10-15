#!/bin/bash

if [[ "$1" == "--help"  ]]; then
    echo -e "Usage: $0"
    echo
    echo -e "Supported environment variables: JICOFO_MAX_MEMORY, JAVA_SYS_PROPS."
    echo
    exit 1
fi

if [[ ! "$JAVA_SYS_PROPS" == *"-Dconfig.file="* ]]; then
    if [[ -f /etc/jitsi/jicofo/jicofo.conf ]]; then
        JAVA_SYS_PROPS="$JAVA_SYS_PROPS -Dconfig.file=/etc/jitsi/jicofo/jicofo.conf"
    else
        echo
        echo "To run jicofo you need a configuration file. Use environment variable JAVA_SYS_PROPS."
        echo "e.g. export JAVA_SYS_PROPS=\"-Dconfig.file=/etc/jitsi/jicofo/jicofo.conf\""
        echo
        exit 2
    fi
fi

SCRIPT_DIR="$(dirname "$(readlink -f "$0")")"

mainClass="org.jitsi.jicofo.Main"
cp=$(JARS=($SCRIPT_DIR/jicofo*.jar $SCRIPT_DIR/lib/*.jar); IFS=:; echo "${JARS[*]}")
logging_config="$SCRIPT_DIR/lib/logging.properties"

# if there is a logging config file in lib folder use it (running from source)
if [ -f $logging_config ]; then
    LOGGING_CONFIG_PARAM="-Djava.util.logging.config.file=$logging_config"
fi

if [ -z "$JICOFO_MAX_MEMORY" ]; then JICOFO_MAX_MEMORY=3072m; fi

exec java -Xmx$JICOFO_MAX_MEMORY -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -Djdk.tls.ephemeralDHKeySize=2048 $LOGGING_CONFIG_PARAM $JAVA_SYS_PROPS -cp $cp $mainClass $@
