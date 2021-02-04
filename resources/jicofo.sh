#!/bin/bash

if [[ "$1" == "--help"  || $# -lt 1 ]]; then
    echo -e "Usage:"
    echo -e "$0 [OPTIONS], where options can be:"
    echo -e "\t--host=HOST\t sets the hostname of the XMPP server (default: domain, if domain is set, localhost otherwise)"
    echo -e "\t--domain=DOMAIN\t sets the XMPP domain"
    echo -e "\t--user_domain=DOMAIN\t specifies the name of XMPP domain used by the focus user to login."
    echo -e "\t--user_name=USERNAME\t specifies the username used by the focus XMPP user to login. (default: focus@user_domain)"
    echo -e "\t--user_password=PASSWORD\t specifies the password used by focus XMPP user to login. If not provided then focus user will use anonymous authentication method."
    echo
    echo -e "\tPASSWORD can alternatively be set via the environment variable JICOFO_AUTH_PASSWORD."
    echo
    echo -e "\tAll of the options can also be specified in the config file (which is the preferred way)."
    echo
    exit 1
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
