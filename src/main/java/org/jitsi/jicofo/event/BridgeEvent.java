package org.jitsi.jicofo.event;

import org.jitsi.eventadmin.*;

import java.util.*;

/**
 *
 */
public class BridgeEvent
    extends Event
{
    public static final String BRIDGE_UP = "org/jitsi/jicofo/JVB/UP";

    public static final String BRIDGE_DOWN = "org/jitsi/jicofo/JVB/DOWN";

    public static final String CONFERENCE_ALLOCATED
        = "org/jitsi/jicofo/JVB/CONFERENCE_ALLOCATED";

    public static final String CONFERENCE_EXPIRED
        = "org/jitsi/jicofo/JVB/CONFERENCE_EXPIRED";

    public static final String CHANNELS_ALLOCATED
        = "org/jitsi/jicofo/JVB/CHANNELS_ALLOCATED";

    public static final String CHANNELS_EXPIRED
        = "org/jitsi/jicofo/JVB/CHANNELS_EXPIRED";

    private final static String JVB_JID_KEY = "bridge.jid";

    private final static String CHANNELS_AUDIO_KEY = "bridge.channels.audio";

    private final static String CHANNELS_VIDEO_KEY = "bridge.channels.video";

    private BridgeEvent(String topic, String bridgeJid)
    {
        super(topic, initDictionary(bridgeJid));
    }

    private BridgeEvent(String topic, String bridgeJid,
                        int audioCount, int videoCount)
    {
        super(topic, initDictionary(bridgeJid, audioCount, videoCount));
    }

    public String getBridgeJid()
    {
        return (String) getProperty(JVB_JID_KEY);
    }

    static private Dictionary<String, Object> initDictionary(String bridgeJid)
    {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put(JVB_JID_KEY, bridgeJid);
        return props;
    }

    static private Dictionary<String, Object> initDictionary(
        String bridgeJid, int audioCount, int videoCount)
    {
        Dictionary<String, Object> props = initDictionary(bridgeJid);
        props.put(CHANNELS_AUDIO_KEY, audioCount);
        props.put(CHANNELS_VIDEO_KEY, videoCount);
        return props;
    }

    static public BridgeEvent createBridgeUp(String bridgeJid)
    {
        return new BridgeEvent(BRIDGE_UP, bridgeJid);
    }

    static public BridgeEvent createBridgeDown(String bridgeJid)
    {
        return new BridgeEvent(BRIDGE_DOWN, bridgeJid);
    }

    static public BridgeEvent createConfAllocated(String bridgeJid)
    {
        return new BridgeEvent(CONFERENCE_ALLOCATED, bridgeJid);
    }

    static public BridgeEvent createConfExpired(String bridgeJid)
    {
        return new BridgeEvent(CONFERENCE_EXPIRED, bridgeJid);
    }

    static public BridgeEvent createChannelsAlloc(String bridgeJid,
                                                  int    audioCount,
                                                  int    videoCount)
    {
        return new BridgeEvent(
            CHANNELS_ALLOCATED, bridgeJid, audioCount, videoCount);
    }

    static public BridgeEvent createChannelsExpired(String bridgeJid,
                                                    int    audioCount,
                                                    int    videoCount)
    {
        return new BridgeEvent(
            CHANNELS_EXPIRED, bridgeJid, audioCount, videoCount);
    }

    public static boolean isBridgeEvent(Event event)
    {
        switch (event.getTopic())
        {
            case BRIDGE_DOWN:
            case BRIDGE_UP:
            case CONFERENCE_ALLOCATED:
            case CONFERENCE_EXPIRED:
                return true;
            default:
                return false;
        }
    }
}
