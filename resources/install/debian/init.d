#! /bin/sh
#
# INIT script for Jitsi Conference Focus
# Version: 1.0  28-Nov-2014  yasen@bluejimp.com
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
DEAMON_DIR=/usr/share/jicofo/
NAME=jicofo
USER=jicofo
PIDFILE=/var/run/jicofo.pid
LOGFILE=/var/log/jitsi/jicofo/jicofo.log
DESC=jicofo
DAEMON_OPTS=" --host=localhost --domain=$JICOFO_HOSTNAME --port=$JICOFO_PORT --secret=$JICOFO_SECRET --user_domain=$JICOFO_AUTH_DOMAIN --user_password=$JICOFO_AUTH_PASSWORD $JICOFO_OPTS"

test -x $DAEMON || exit 0

set -e

killParentPid() {
    PPID=$(ps -o pid --no-headers --ppid $1 || true)
    if [ $PPID ]; then
        kill $PPID
    fi
}

stop() {
    if [ -f $PIDFILE ]; then
        PID=$(cat $PIDFILE)
    fi
    echo -n "Stopping $DESC: "
    if [ $PID ]; then
        killParentPid $PID
        rm $PIDFILE || true
        echo "$NAME stopped."
    elif [ $(ps -C jicofo.sh --no-headers -o pid) ]; then
        kill $(ps -o pid --no-headers --ppid $(ps -C jicofo.sh --no-headers -o pid))
        rm $PIDFILE || true
        echo "$NAME stopped."
    else
        echo "$NAME doesn't seem to be running."
    fi
}

start() {
    if [ -f $PIDFILE ]; then
        echo "$DESC seems to be already running, we found pidfile $PIDFILE."
        exit 1
    fi
    echo -n "Starting $DESC: "
    start-stop-daemon --start --quiet --background --chuid $USER --make-pidfile --pidfile $PIDFILE \
        --exec /bin/bash -- -c "cd $DEAMON_DIR; exec $DAEMON $DAEMON_OPTS < /dev/null >> $LOGFILE 2>&1"
    echo "$NAME started."
}

reload() {
    echo 'Not yet implemented.'
}

status() {
    echo 'Not yet implemented.'
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
