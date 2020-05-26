#!/bin/sh -e

readonly CONFIG=$1
if [ -z ${CONFIG+x} -o ! -f $1 ]; then
   echo 'Config file missing.'
   exit 1
fi

. $CONFIG

export JICOFO_SECRET JICOFO_AUTH_PASSWORD

exec mvn compile exec:exec -Dexec.executable=java -Dexec.args="-cp %classpath ${JAVA_SYS_PROPS} org.jitsi.jicofo.Main --domain=\"${JICOFO_HOSTNAME}\" --host=\"${JICOFO_HOST}\" --port=\"${JICOFO_PORT}\" --user_domain=\"${JICOFO_AUTH_DOMAIN}\" --user_name=\"${JICOFO_AUTH_USER}\""
