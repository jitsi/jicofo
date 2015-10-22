#!/bin/bash

# script that creates an archive in current folder
# containing the heap and thread dump and the current log file

PID=$(cat /var/run/jicofo.pid)
if [ $PID ]; then
    PROC_PID=$(pgrep -P $PID)
    echo "Jicofo pid $PROC_PID"
    STAMP=`date +%Y-%m-%d-%H%M`
    THREADS_FILE="/tmp/stack-${STAMP}-${PROC_PID}.threads"
    HEAP_FILE="/tmp/heap-${STAMP}-${PROC_PID}.bin"
    sudo -u jicofo jstack ${PROC_PID} > ${THREADS_FILE}
    sudo -u jicofo jmap -dump:live,format=b,file=${HEAP_FILE} ${PROC_PID}
    tar zcvf jicofo-dumps-${STAMP}-${PROC_PID}.tgz ${THREADS_FILE} ${HEAP_FILE} /var/log/jitsi/jicofo.log
    rm ${HEAP_FILE} ${THREADS_FILE}
else
    echo "Jicofo not running"
fi
