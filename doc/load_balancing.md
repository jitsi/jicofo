# Load balancing

## Overview

Load balancing is done by the jicofo component by selecting the least loaded
videobridge instance from those detected during startup. Videobridges are
constantly publishing statistics to PubSub XMPP service and Jicofo has to be
subscribed to those stats.

Current algorithm is based on number of conferences only, but all [stats]
published by the bridge are available and this can be improved easily.

[stats]: https://github.com/jitsi/jitsi-videobridge/blob/master/src/main/java/org/jitsi/videobridge/stats/VideobridgeStatistics.java

# Configuration

In order to have load balancing working we have to make all bridges publish
theirs stats to shared PubSub node and configure Jicofo to listen to them.

There are following entities mentioned in the configuration:

**XMPP PubSub service** - this is XMPP server endpoint that manages PubSub
nodes,
 subscriptions and notifications, usually subdomain of the server. In Prosody
 this is VirtualHost with "pubsub" module enabled.

**PubSub node** - this is PubSub endpoint created by videobridge instance on
PubSub service.

Now some names which will be mentioned in the config:

**example.com** - our VirtualHost name configured in Prosody with "pubsub"
 module enabled. This is also our "XMPP PubSub service".

**jvb1.example.com** - first videobridge component

**jvb2.example.com** - second videobridge component

**sharedStatsNode** - this is the name of PubSub node to which first and second bridge will
publish their stats

### Step 1
Enable PubSub statistics in the videobridge. Assuming that Jvb has been
installed using Debian package then edit **/etc/jitsi/videobridge/sip-communicator.properties**
file and add following lines(sample for the first bridge):

```
org.jitsi.videobridge.ENABLE_STATISTICS=true
org.jitsi.videobridge.STATISTICS_TRANSPORT=pubsub
org.jitsi.videobridge.PUBSUB_SERVICE=example.com
org.jitsi.videobridge.PUBSUB_NODE=sharedStatsNode
```

### Step 2
Configure second videobridge instance just like the first one.

### Step 3
Videobridge JIDs have to be added to "admins" of the server, so that they
will have permissions for creating PubSub nodes:

```
admins = {
 jvb1.example.com,
 jvb2.example.com,
 ...
}
```

### Step 4

Edit **/etc/jitsi/jicofo/sip-communicator.properties**:

```
org.jitsi.focus.pubsub.ADDRESS=example.com
org.jitsi.jicofo.STATS_PUBSUB_NODE=sharedStatsNode
```

If the bridges were configured properly they should startup normally and print
information about published stats to the logs. Jicofo will output number of conferences held by each of the bridges on startup and on every change later on. It should also load balance between the bridges correctly.
