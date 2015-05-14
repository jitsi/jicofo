#Load balancing overview

Load balancing is done by the jicofo component by selecting the least loaded
videobridge instance from those detected during startup. Videobridges are
constantly publishing statistics to PubSub XMPP service and Jicofo has to be
subscribed to those stats.

Current algorithm is based on number of conferences only, but all [stats]
published by the bridge are available and this can be improved easily.

[stats]: https://github.com/jitsi/jitsi-videobridge/blob/master/src/org/jitsi/videobridge/stats/VideobridgeStatistics.java

#Load balancing configuration

In order to have load balancing working we have to make bridges publish
theirs stats to some PubSub node and configure Jicofo to listen to them.

There are following entities mentioned in the configuration:

<b>XMPP PubSub service</b> - this is XMPP server endpoint that manages PubSub
nodes,
 subscriptions and notifications, usually subdomain of the server. In Prosody
 this is VirtualHost with "pubsub" module enabled.

<b>PubSub node</b> - this is PubSub endpoint created by videobridge instance on
PubSub service. Each videobridge must have it's own node and we'll have to
tell Jicofo which node belongs to which bridge, so that it can identify
the stats.

Now some names which will be mentioned in the config:

<b>example.com</b> - our VirtualHost name configured in Prosody with "pubsub"
 module enabled. This is also our "XMPP PubSub service".

<b>jvb1.example.com</b> - first videobridge component

<b>jvb2.example.com</b> - second videobridge component

<b>jvb1node</b> - this is the name of PubSub node to which first bridge will
publish it's stats

<b>jvb2node</b> - name of PubSub node for second videobridge instance

###Step 1
Enable PubSub statistics in the videobridge. Assuming that Jvb has been
installed using Debian package then edit <b>/usr/share/jitsi-videobridge/.sip-communicator/sip
.communicator.properties</b> file and add following lines(sample for the first
bridge):

```
org.jitsi.videobridge.ENABLE_STATISTICS=true
org.jitsi.videobridge.STATISTICS_TRANSPORT=pubsub
org.jitsi.videobridge.PUBSUB_SERVICE=example.com
org.jitsi.videobridge.PUBSUB_NODE=jvb1node
```

###Step 2
Configure second videobridge instance similiar to the first one.

###Step 3
Videobridge JIDs have to be added to "admins" of the server, so that they
will have permissions for creating PubSub nodes:

```
admins = {
 jvb1.example.com,
 jvb2.example.com,
 ...
}
```

###Step 4
Focus on startup does item discovery on XMPP domain and detects
registered videobridge components [1]. Next bridge selector module
tries to bind to mapped PubSub nodes and then listen for bridge
stats [2]. We have not implemented auto-binding for PubSub nodes yet
and mapping has to be done manually through config properties.

[1]: https://github.com/jitsi/jicofo/blob/master/src/org/jitsi/jicofo/JitsiMeetServices.java#L170
[2]: https://github.com/jitsi/jicofo/blob/master/src/org/jitsi/jicofo/BridgeSelector.java#L118

```
org.jitsi.focus.pubsub.ADDRESS=example.com
# Bridge name to pubsub node name mapping {jvb-address:pubsub_node},
separated with ";"
org.jitsi.focus.BRIDGE_PUBSUB_MAPPING=jvb1.example.com:jvb1node;jvb2.example
.com:jvb2node
```

If the bridges were configured properly they should startup normally and print
information about published stats to the logs. Jicofo will output number of conferences held by each of the bridges on startup and on every change later on. It should also load balance between the bridges correctly.
