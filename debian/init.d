#! /bin/sh
#
# INIT script for Jitsi Conference Focus
# Version: 1.0  4-Dec-2014  pawel.domas@jitsi.org
#
### BEGIN INIT INFO
# Provides:          jicofo
# Required-Start:    $local_fs $remote_fs
# Required-Stop:     $local_fs $remote_fs
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Short-Description: Jitsi conference Focus
# Description:       Conference focus for Jitsi Meet application.
### END INIT INFO

. /lib/lsb/init-functions

# Include jicofo defaults if available
if [ -f /etc/jitsi/jicofo/config ]; then
    . /etc/jitsi/jicofo/config
fi

PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin
DAEMON=/usr/share/jicofo/jicofo.sh
DAEMON_DIR=/usr/share/jicofo/
NAME=jicofo
USER=jicofo
PIDFILE=/var/run/jicofo.pid
LOGFILE=/var/log/jitsi/jicofo.log
DESC=jicofo


if [ ! -x $DAEMON ] ;then
  echo "Daemon not executable: $DAEMON"
  exit 1
fi

set -e

stop() {
    if [ -f $PIDFILE ]; then
        PID=$(cat $PIDFILE)
    fi
    echo -n "Stopping $NAME: "
    if [ $PID ]; then
        kill $PID || true
        rm $PIDFILE || true
        echo "$NAME stopped."
    else
        echo "$NAME doesn't seem to be running."
    fi
}

start() {
    if [ -f $PIDFILE ]; then
        echo "$NAME seems to be already running, we found pidfile $PIDFILE."
        exit 1
    fi
    echo -n "Starting $NAME: "
    export JICOFO_AUTH_PASSWORD JICOFO_MAX_MEMORY
    start-stop-daemon --start --quiet --background --chuid $USER --make-pidfile --pidfile $PIDFILE \
        --exec /bin/bash -- -c "cd $DAEMON_DIR; JAVA_SYS_PROPS=\"$JAVA_SYS_PROPS\" exec $DAEMON $JICOFO_OPTS < /dev/null >> $LOGFILE 2>&1"
    echo "$NAME started."
}

reload() {
    echo 'Not yet implemented.'
}

status() {
    status_of_proc -p $PIDFILE java "$NAME" && exit 0 || exit $?
}

case "$1" in
  start)
    start
    ;;
  stop)
    stop
    ;;
  restart)
    stop
    start
    ;;
  reload)
    reload
    ;;
  force-reload)
    reload
    ;;
  status)
    status
    ;;
  *)
    N=/etc/init.d/$NAME
    echo "Usage: $N {start|stop|restart|reload|status}" >&2
    exit 1
    ;;
esac

exit 0
