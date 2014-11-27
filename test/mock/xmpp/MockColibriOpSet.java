/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package mock.xmpp;

import mock.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.service.protocol.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.*;
import org.jitsi.protocol.*;
import org.jitsi.protocol.xmpp.*;

import java.util.*;

/**
 *
 * @author Pawel Domas
 */
public class MockColibriOpSet
    implements OperationSetColibriConference
{
    private final MockProtocolProvider protocolProvider;

    private OperationSetColibriConferenceImpl colibriImpl;

    public MockColibriOpSet(MockProtocolProvider protocolProvider)
    {
        this.protocolProvider = protocolProvider;

        colibriImpl = new OperationSetColibriConferenceImpl();

        colibriImpl.initialize(protocolProvider.getMockXmppConnection());
    }

    @Override
    public void setJitsiVideobridge(String videobridgeJid)
    {
        colibriImpl.setJitsiVideobridge(videobridgeJid);
    }

    @Override
    public String getJitsiVideobridge()
    {
        return colibriImpl.getJitsiVideobridge();
    }

    @Override
    public void setJitsiMeetConfig(JitsiMeetConfig config)
    {
        colibriImpl.setJitsiMeetConfig(config);
    }

    @Override
    public String getConferenceId()
    {
        return colibriImpl.getConferenceId();
    }

    @Override
    public ColibriConferenceIQ createColibriChannels(boolean useBundle,
                                                     String endpointName,
                                                     boolean peerIsInitiator,
                                                     List<ContentPacketExtension> contents)
        throws OperationFailedException
    {
        return colibriImpl.createColibriChannels(
            useBundle, endpointName, peerIsInitiator, contents);
    }

    @Override
    public void updateTransportInfo(
        boolean initiator,
        Map<String, IceUdpTransportPacketExtension> map,
        ColibriConferenceIQ localChannelsInfo)
    {
        colibriImpl.updateTransportInfo(initiator, map, localChannelsInfo);
    }

    @Override
    public void updateSsrcGroupsInfo(MediaSSRCGroupMap ssrcGroups,
                                     ColibriConferenceIQ localChannelsInfo)
    {
        colibriImpl.updateSsrcGroupsInfo(ssrcGroups, localChannelsInfo);
    }

    @Override
    public void updateBundleTransportInfo(boolean initiator,
                                          IceUdpTransportPacketExtension transport,
                                          ColibriConferenceIQ localChannelsInfo)
    {
        colibriImpl.updateBundleTransportInfo(
            initiator, transport, localChannelsInfo);
    }

    @Override
    public void expireChannels(ColibriConferenceIQ channelInfo)
    {
        colibriImpl.expireChannels(channelInfo);
    }

    @Override
    public void expireConference()
    {
        colibriImpl.expireConference();
    }

    @Override
    public boolean muteParticipant(ColibriConferenceIQ channelsInfo,
                                   boolean mute)
    {
        return colibriImpl.muteParticipant(channelsInfo, mute);
    }
}
