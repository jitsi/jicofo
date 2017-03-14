#!/bin/sh -e

error_exit() {
  echo "$1" >&2
  exit 1
}

usage() {
  error_exit "Usage: $0 [-p JICOFO_PORT] [-r] [-f JICOFO_MVN_POM_FILE] [-d JICOFO_HOSTNAME] [-h JICOFO_HOST] [-s JICOFO_SECRET]"
}

JICOFO_LOGGING_CONFIG_FILE="${PREFIX}/etc/jitsi/jicofo/logging.properties"
JICOFO_CONFIG_FILE="${PREFIX}/etc/jitsi/jicofo/config"
JICOFO_HOME_DIR_NAME="jicofo"
JICOFO_HOME_DIR_LOCATION="${PREFIX}/etc/jitsi"
JICOFO_MVN_REPO_LOCAL="${PREFIX}/share/jicofo/m2"
JICOFO_LOG_DIR_LOCATION="${PREFIX}/var/log/jitsi"
JICOFO_HOSTNAME=
JICOFO_HOST=
JICOFO_PORT=
JICOFO_SECRET=
JICOFO_AUTH_DOMAIN=
JICOFO_AUTH_USER=
JICOFO_AUTH_PASSWORD=
JICOFO_EXTRA_JVM_PARAMS=
JICOFO_MVN_POM_FILE=
JICOFO_JAVA_PREFER_IPV4=false

# Source the JVB configuration file.
if [ -f "${JICOFO_CONFIG_FILE}" ]; then
  . "${JICOFO_CONFIG_FILE}"
fi

# Overide/complete with cmdline params.
while getopts ":d:h:s:f:p:r4" o; do
  case "${o}" in
    d)
      JICOFO_HOSTNAME="${OPTARG}"
      ;;
    p)
      JICOFO_PORT="${OPTARG}"
      ;;
    s)
      JICOFO_SECRET="${OPTARG}"
      ;;
    f)
      JICOFO_MVN_POM_FILE="${OPTARG}"
      ;;
    r)
      JICOFO_MVN_REBUILD=true
      ;;
    h)
      JICOFO_HOST="${OPTARG}"
      ;;
    4)
      JICOFO_JAVA_PREFER_IPV4=true
      ;;
    *)
      usage
      ;;
  esac
done

# Cmdline params validation and guessing.
if [ "${JICOFO_HOSTNAME}" = "" ]; then
  usage
fi

if [ "${JICOFO_SECRET}" = "" ]; then
  usage
fi

if [ "${JICOFO_PORT}" = "" ]; then
  # Guess the XMPP port to use.
  JICOFO_PORT=5347
fi

if [ ! -e "${JICOFO_MVN_POM_FILE}" ]; then
  # Guess the location of the pom file.
  JICOFO_MVN_POM_FILE="$(pwd)/jicofo/pom.xml"
fi

if [ ! -e "${JICOFO_MVN_POM_FILE}" ]; then
  # Guess the location of the pom file.
  JICOFO_MVN_POM_FILE="$(pwd)/pom.xml"
fi

if [ ! -e "${JICOFO_MVN_POM_FILE}" ]; then
  error_exit "The maven pom file was not found."
fi

if [ ! -d "${JICOFO_LOG_DIR_LOCATION}" ] ; then
  mkdir "${JICOFO_LOG_DIR_LOCATION}"
fi

# Rebuild.
if [ ! ${JICOFO_MVN_REBUILD} = "" ]; then
  mvn -f "${JICOFO_MVN_POM_FILE}" clean compile -Dmaven.repo.local="${JICOFO_MVN_REPO_LOCAL}"
fi

# Execute.
exec mvn -f "${JICOFO_MVN_POM_FILE}" exec:exec -Dmaven.repo.local="${JICOFO_MVN_REPO_LOCAL}" -Dexec.executable=java -Dexec.args="-cp %classpath ${JICOFO_EXTRA_JVM_PARAMS} -Djava.util.logging.config.file=\"${JICOFO_LOGGING_CONFIG_FILE}\" -Dnet.java.sip.communicator.SC_HOME_DIR_NAME=\"${JICOFO_HOME_DIR_NAME}\" -Dnet.java.sip.communicator.SC_HOME_DIR_LOCATION=\"${JICOFO_HOME_DIR_LOCATION}\" -Dnet.java.sip.communicator.SC_LOG_DIR_LOCATION=\"${JICOFO_LOG_DIR_LOCATION}\" -Djna.nosys=true -Djava.net.preferIPv4Stack=\"${JICOFO_JAVA_PREFER_IPV4}\" org.jitsi.jicofo.Main --domain=\"${JICOFO_HOSTNAME}\" --host=\"${JICOFO_HOST}\" --port=\"${JICOFO_PORT}\" --secret=\"${JICOFO_SECRET}\" --user_domain=\"${JICOFO_AUTH_DOMAIN}\" --user_name=\"${JICOFO_AUTH_USER}\" --user_password=\"${JICOFO_AUTH_PASSWORD}\""
