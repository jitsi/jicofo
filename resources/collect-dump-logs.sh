#!/bin/bash

# script that creates an archive in current folder
# containing the heap and thread dump and the current log file

JAVA_HEAPDUMP_PATH="/tmp/java_*.hprof"
STAMP=`date +%Y-%m-%d-%H%M`
PID_PATH="/var/run/jicofo.pid"
RUNNING=""
unset PID

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
    tar zcvf jicofo-dumps-${STAMP}-${PID}.tgz ${THREADS_FILE} ${HEAP_FILE} /var/log/jitsi/jicofo.log
    rm ${HEAP_FILE} ${THREADS_FILE}
else
    ls $JAVA_HEAPDUMP_PATH >/dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo "Jicofo not running, but previous heap dump found."
        tar zcvf jicofo-dumps-${STAMP}-crash.tgz $JAVA_HEAPDUMP_PATH /var/log/jitsi/jicofo.log
        rm ${JAVA_HEAPDUMP_PATH}
    else
        echo "Jicofo not running."
    fi
fi
