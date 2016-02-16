#!/bin/bash

# script that creates an archive in current folder
# containing the heap and thread dump and the current log file

JAVA_HEAPDUMP_PATH="/tmp/java_*.hprof"
STAMP=`date +%Y-%m-%d-%H%M`
PID_PATH="/var/run/jicofo.pid"

[ -e $PID_PATH ] && PID=$(cat $PID_PATH)
if [ $PID ]; then
    echo "Jicofo pid $PID"
    THREADS_FILE="/tmp/stack-${STAMP}-${PID}.threads"
    HEAP_FILE="/tmp/heap-${STAMP}-${PID}.bin"
    sudo -u jicofo jstack ${PID} > ${THREADS_FILE}
    sudo -u jicofo jmap -dump:live,format=b,file=${HEAP_FILE} ${PID}
    tar zcvf jicofo-dumps-${STAMP}-${PID}.tgz ${THREADS_FILE} ${HEAP_FILE} /var/log/jitsi/jicofo.log
    rm ${HEAP_FILE} ${THREADS_FILE}
else
    if [ -e $JAVA_HEAPDUMP_PATH ]; then
        echo "Jicofo not running, but previous heap dump found."
        tar zcvf jicofo-dumps-${STAMP}-crash.tgz $JAVA_HEAPDUMP_PATH /var/log/jitsi/jvb.log
        rm ${JAVA_HEAPDUMP_PATH}
    else
        echo "Jicofo not running."
    fi
fi
