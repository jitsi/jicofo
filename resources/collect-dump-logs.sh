#!/bin/bash

# script that creates an archive in current folder
# containing the heap and thread dump and the current log file

JAVA_HEAPDUMP_PATH="/tmp/java_*.hprof"
STAMP=`date +%Y-%m-%d-%H%M`
PID_PATH="/var/run/jicofo.pid"
JICOFO_UID=`id -u jicofo`
RUNNING=""
unset PID

#Find any crashes in /var/crash from our user in the past 20 minutes, if they exist
CRASH_FILES=$(find /var/crash -name '*.crash' -uid $JICOFO_UID -mmin -20 -type f)

[ -e $PID_PATH ] && PID=$(cat $PID_PATH)
if [ ! -z $PID ]; then
   ps -p $PID | grep -q java
   [ $? -eq 0 ] && RUNNING="true"
fi
if [ ! -z $RUNNING ]; then
    echo "Jicofo pid $PID"
    THREADS_FILE="/tmp/stack-${STAMP}-${PID}.threads"
    HEAP_FILE="/tmp/heap-${STAMP}-${PID}.bin"
    sudo -u jicofo jstack ${PID} > ${THREADS_FILE}
    sudo -u jicofo jmap -dump:live,format=b,file=${HEAP_FILE} ${PID}
    tar zcvf jicofo-dumps-${STAMP}-${PID}.tgz ${THREADS_FILE} ${HEAP_FILE} ${CRASH_FILES} /var/log/jitsi/jicofo* /tmp/hs_err_*
    rm ${HEAP_FILE} ${THREADS_FILE}
else
    ls $JAVA_HEAPDUMP_PATH >/dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo "Jicofo not running, but previous heap dump found."
        tar zcvf jicofo-dumps-${STAMP}-crash.tgz $JAVA_HEAPDUMP_PATH ${CRASH_FILES} /var/log/jitsi/jicofo* /tmp/hs_err_*
        rm ${JAVA_HEAPDUMP_PATH}
    else
        echo "Jicofo not running, no previous dump found. Including logs only."
        tar zcvf jicofo-dumps-${STAMP}-crash.tgz ${CRASH_FILES} /var/log/jitsi/jicofo* /tmp/hs_err_*
    fi
fi
