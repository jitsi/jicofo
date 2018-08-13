/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.jicofo.jigasi;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jitsimeet.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.rayo.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import org.jitsi.jicofo.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.util.*;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Presence;
import org.jxmpp.jid.*;

/**
 * The {@link TranscriberManager} class is responsible for listening to
 * {@link ChatRoomMemberPropertyChangeEvent}s to see whether a
 * {@link ChatRoomMember} is requesting a transcriber by adding a
 * {@link TranscriptionLanguageExtension} to their {@link Presence}.
 *
 * @author Nik Vaessen
 */
public class TranscriberManager
    implements ChatRoomMemberPropertyChangeListener
{
    /**
     * The logger of this class.
     */
    private final static Logger logger
        = Logger.getLogger(TranscriberManager.class);

    /**
     * The {@link ChatRoom} of the conference this class is managing
     */
    private ChatRoom chatRoom;

    /**
     * The {@link JigasiDetector} responsible for determining which Jigasi
     * to dial to when inviting the transcriber.
     */
    private JigasiDetector jigasiDetector;

    /**
     * The {@link XmppConnection} used to Dial Jigasi.
     */
    private XmppConnection connection;

    /**
     * The transcription status; either active or inactive based on this boolean
     */
    private volatile boolean active;

    /**
     * Create a {@link TranscriberManager} responsible for inviting Jigasi as
     * a transcriber when this is desired.
     *
     * @param protocolProviderHandler the handler giving access a XmppConnection
     * @param chatRoom the room of the conference being managed
     * @param jigasiDetector detector for Jigasi instances which can be dialed
     * to invite a transcriber
     */
    public TranscriberManager(ProtocolProviderHandler protocolProviderHandler,
                              ChatRoom chatRoom,
                              JigasiDetector jigasiDetector)
    {
        this.connection
            = protocolProviderHandler.getOperationSet(
            OperationSetDirectSmackXmpp.class).getXmppConnection();

        this.chatRoom = chatRoom;
        this.jigasiDetector = jigasiDetector;
    }

    /**
     * Initialise the manager by starting to listen for
     * {@link ChatRoomMemberPropertyChangeEvent}s.
     */
    public void init()
    {
        chatRoom.addMemberPropertyChangeListener(this);

        logger.info("initialised transcriber manager");
    }

    /**
     * Stop the manager by stopping to listen for
     * {@link ChatRoomMemberPropertyChangeEvent}s.
     */
    public void dispose()
    {
        chatRoom.removeMemberPropertyChangeListener(this);

        logger.info("disposed transcriber manager");
    }

    /**
     * Listener method for each {@link ChatRoomMemberPropertyChangeEvent}, which
     * can possibly indicate the transcriber needs to be invited.
     *
     * {@inheritDoc}
     */
    @Override
    public void chatRoomPropertyChanged(ChatRoomMemberPropertyChangeEvent event)
    {
        if(event.getNewValue() instanceof Presence)
        {
            onNewPresence(event);
        }
    }

    /**
     * Check whether a {@link ChatRoomPropertyChangeEvent} is due to a new
     * {@link Presence}, and when it is, deal with the information is has
     * provided.
     *
     * @param event the event to check the {@link Presence} off
     */
    private void onNewPresence(ChatRoomMemberPropertyChangeEvent event)
    {
        Presence presence = getPresenceOrNull(event);

        if(presence == null)
        {
            return;
        }

        if(containsTranscriptionStatus(presence))
        {
            maybeStoppedTranscribing(presence);
        }
        else if(isRequestingTranscriber(presence) && !active)
        {
            startTranscribing();
        }
    }

    /**
     * Check whether the given {@link Presence} contains a
     * {@link TranscriptionStatusExtension}
     *
     * @param p the given {@link Presence} to check
     * @return true when it contains the extension, false otherwise
     */
    private boolean containsTranscriptionStatus(Presence p)
    {
        return p.hasExtension(
            TranscriptionStatusExtension.ELEMENT_NAME,
            TranscriptionStatusExtension.NAMESPACE
        );
    }

    /**
     * Method which is able to invite the transcriber by dialing Jigasi
     */
    private synchronized void startTranscribing()
    {
        logger.info("Attempting to invite transcriber");

        Jid jigasiJid = jigasiDetector.selectJigasi();

        RayoIqProvider.DialIq dialIq = new RayoIqProvider.DialIq();
        dialIq.setDestination("jitsi_meet_transcribe");
        dialIq.setTo(jigasiJid);
        dialIq.setType(IQ.Type.set);
        dialIq.setHeader("JvbRoomName", chatRoom.getName());

        try
        {
            IQ response = this.connection.sendPacketAndGetReply(dialIq);

            if (response != null)
            {
                if (response.getError() == null)
                {
                    active = true;
                    logger.info("transcriber was successfully invited");
                }
                else
                {
                    logger.warn("failed to invite transcriber. Got error: " +
                        response.getError().getErrorGenerator());
                }
            }
            else
            {
                logger.warn("failed to invite transcriber; lack of response" +
                    " from XmmpConnection");
            }
        }
        catch (OperationFailedException e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Checks whether the given {@link Presence} and
     * {@link TranscriptionStatusExtension} indicate transcription has stopped,
     * and when it has, sets {@link this#active} to false.
     *
     * @param p the {@link Presence}
     */
    private void maybeStoppedTranscribing(Presence p)
    {
        TranscriptionStatusExtension ext = p.getExtension(
            TranscriptionStatusExtension.ELEMENT_NAME,
            TranscriptionStatusExtension.NAMESPACE
        );

        if(ext == null)
        {
            return;
        }

        if(TranscriptionStatusExtension.Status.OFF.equals(ext.getStatus()))
        {
            active = false;
            logger.info("detected transcription status being turned off.");
        }
    }


    /**
     * Checks whether the given {@link Presence} indicates a conference
     * participant is requesting transcription
     *
     * @param presence the presence to check
     * @return true when the participant of the {@link Presence} is requesting
     * transcription, false otherwise
     */
    private boolean isRequestingTranscriber(Presence presence)
    {
        if(presence == null)
        {
            return false;
        }

        TranscriptionLanguageExtension ext =  presence.getExtension(
            TranscriptionLanguageExtension.ELEMENT_NAME,
            TranscriptionLanguageExtension.NAMESPACE);

        if(ext == null)
        {
            return false;
        }

        String lang = ext.getTranscriptionLanguage();

        if(lang == null)
        {
            return false;
        }
        else
        {
            return !lang.isEmpty();
        }
    }

    /**
     * Extract the presence from the {@link ChatRoomPropertyChangeEvent}.
     *
     * @param event the {@link ChatRoomPropertyChangeEvent} to extract from
     * @return the {@link Presence}, or null when not available.
     */
    private Presence getPresenceOrNull(ChatRoomMemberPropertyChangeEvent event)
    {
        if(event.getNewValue() instanceof Presence)
        {
            return ((Presence) event.getNewValue());
        }

        return null;
    }

}
