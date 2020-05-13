/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.osgi.*;
import org.jitsi.utils.logging.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.xmpp.extensions.rayo.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import org.jitsi.jicofo.*;
import org.jitsi.protocol.xmpp.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

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
     * A single-threaded {@link ExecutorService} to offload inviting the
     * Transcriber from the smack thread updating presence.
     */
    private ExecutorService executorService;

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
        if(executorService != null)
        {
            executorService.shutdown();
            executorService = null;
        }

        executorService = Executors.newSingleThreadExecutor();
        chatRoom.addMemberPropertyChangeListener(this);

        logger.debug("initialised transcriber manager");
    }

    /**
     * Stop the manager by stopping to listen for
     * {@link ChatRoomMemberPropertyChangeEvent}s.
     */
    public void dispose()
    {
        executorService.shutdown();
        executorService = null;
        chatRoom.removeMemberPropertyChangeListener(this);

        logger.debug("disposed transcriber manager");
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

        TranscriptionStatusExtension transcriptionStatusExtension
            = getTranscriptionStatus(presence);
        if(transcriptionStatusExtension != null
            && TranscriptionStatusExtension.Status.OFF.equals(
                    transcriptionStatusExtension.getStatus()))
        {
            // puts the stopping in the single threaded executor
            // so we can order the events and avoid indicating active = false
            // while we are starting due to concurrent presences processed
            executorService.submit(this::stopTranscribing);
        }
        if(isRequestingTranscriber(presence) && !active)
        {
            executorService.submit(
                () -> this.startTranscribing(getBridgeRegions()));
        }
    }

    /**
     * Returns a list of regions of the bridges that are currently used
     * in the conference, empty list if nothing found or an error occurs.
     * @return a list of used bridge regions.
     */
    private Collection<String> getBridgeRegions()
    {
        FocusManager focusManager =
            ServiceUtils2.getService(
                FocusBundleActivator.bundleContext,
                FocusManager.class);

        try
        {
            JitsiMeetConferenceImpl conference =
                focusManager.getConference(
                    JidCreate.entityBareFrom(chatRoom.getIdentifier()));

            if (conference == null)
            {
                logger.debug("Cannot find conference for "
                    + chatRoom.getIdentifier());
            }
            else
            {
                return conference.getBridges().keySet().stream()
                    .map(b -> b.getRegion())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            }
        }
        catch (XmppStringprepException e)
        {
            logger.error("Error finding room for " + chatRoom.getIdentifier());
        }

        return new ArrayList<>();
    }

    /**
     * Returns the {@link TranscriptionStatusExtension} if any from
     * the given {@link Presence}.
     *
     * @param p the given {@link Presence} to check
     * @return Returns the {@link TranscriptionStatusExtension} if any.
     */
    private TranscriptionStatusExtension getTranscriptionStatus(Presence p)
    {
        return p.getExtension(
            TranscriptionStatusExtension.ELEMENT_NAME,
            TranscriptionStatusExtension.NAMESPACE
        );
    }

    /**
     * Method which is able to invite the transcriber by dialing Jigasi
     * @param preferredRegions a list of preferred regions.
     */
    private void startTranscribing(Collection<String> preferredRegions)
    {
        if(active)
        {
            return;
        }

        selectTranscriber(2, null, preferredRegions);
    }

    /**
     * Sends the dial iq to the selected jigasi (from brewery muc).
     * @param retryCount the number of attempts to be made for sending this iq,
     * if no reply is received from the remote side.
     * @param exclude <tt>null</tt> or a list of jigasi Jids which
     * we already tried sending in attempt to retry.
     * @param preferredRegions a list of preferred regions.
     */
    private void selectTranscriber(
        int retryCount, List<Jid> exclude, Collection<String> preferredRegions)
    {
        logger.info("Attempting to invite transcriber");

        Jid jigasiJid
            = jigasiDetector.selectTranscriber(exclude, preferredRegions);

        if(jigasiJid == null)
        {
            logger.warn("Unable to invite transcriber due to no " +
                "Jigasi instances being available");
            return;
        }

        RayoIqProvider.DialIq dialIq = new RayoIqProvider.DialIq();
        dialIq.setDestination("jitsi_meet_transcribe");
        dialIq.setTo(jigasiJid);
        dialIq.setType(IQ.Type.set);
        dialIq.setHeader("JvbRoomName", chatRoom.getName());

        try
        {
            IQ response = this.connection.sendPacketAndGetReply(dialIq);

            boolean retry = false;
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
                    retry = true;
                }
            }
            else
            {
                logger.warn("failed to invite transcriber; lack of response" +
                    " from XmmpConnection");
                retry = true;
            }

            if (retry && retryCount > 0)
            {
                if (exclude == null)
                {
                    exclude = new ArrayList<>();
                }
                exclude.add(jigasiJid);

                selectTranscriber(retryCount - 1, exclude, preferredRegions);
            }
        }
        catch (OperationFailedException e)
        {
            logger.error("Failed sending dialIq to transcriber", e);
        }
    }

    /**
     * Indicate transcription has stopped and sets {@link this#active} to false.
     */
    private void stopTranscribing()
    {
        active = false;
        logger.info("detected transcription status being turned off.");
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

        TranscriptionRequestExtension ext =  presence.getExtension(
            TranscriptionRequestExtension.ELEMENT_NAME,
            TranscriptionRequestExtension.NAMESPACE);

        if(ext == null)
        {
            return false;
        }

        return Boolean.valueOf(ext.getText());
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
