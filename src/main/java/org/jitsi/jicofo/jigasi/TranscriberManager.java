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

import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.xmpp.extensions.rayo.*;
import org.jitsi.jicofo.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * The {@link TranscriberManager} class is responsible for listening to presence updates to see whether a
 * {@link ChatRoomMember} is requesting a transcriber by adding a
 * {@link TranscriptionLanguageExtension} to their {@link Presence}.
 *
 * @author Nik Vaessen
 */
public class TranscriberManager
    implements ChatRoomMemberPresenceListener
{
    /**
     * The logger of this class.
     */
    private final Logger logger;

    /**
     * The {@link ChatRoom} of the conference this class is managing
     */
    private final ChatRoom chatRoom;
    private final JitsiMeetConferenceImpl conference;

    /**
     * The {@link JigasiDetector} responsible for determining which Jigasi
     * to dial to when inviting the transcriber.
     */
    private final JigasiDetector jigasiDetector;

    /**
     * The {@link XMPPConnection} used to Dial Jigasi.
     */
    private final AbstractXMPPConnection connection;

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
     * @param jigasiDetector detector for Jigasi instances which can be dialed
     * to invite a transcriber
     */
    public TranscriberManager(XmppProvider xmppProvider,
                              JitsiMeetConferenceImpl conference,
                              JigasiDetector jigasiDetector,
                              Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(getClass().getName());
        // TODO: handle the connection changing (reconnect)
        this.connection = xmppProvider.getXmppConnection();

        this.conference = conference;
        this.chatRoom = conference.getChatRoom();
        this.jigasiDetector = jigasiDetector;
    }

    public void init()
    {
        if (executorService != null)
        {
            executorService.shutdown();
            executorService = null;
        }

        executorService = Executors.newSingleThreadExecutor();
        chatRoom.addMemberPresenceListener(this);

        logger.debug("initialised transcriber manager");
    }

    public void dispose()
    {
        executorService.shutdown();
        executorService = null;
        chatRoom.removeMemberPresenceListener(this);

        logger.debug("disposed transcriber manager");
    }

    @Override
    public void memberPresenceChanged(@Nullable ChatRoomMemberPresenceChangeEvent evt)
    {
        if (evt instanceof ChatRoomMemberPresenceChangeEvent.PresenceUpdated)
        {
            Presence presence = evt.getChatRoomMember().getPresence();

            if (presence == null)
            {
                return;
            }

            TranscriptionStatusExtension transcriptionStatusExtension = getTranscriptionStatus(presence);
            if (transcriptionStatusExtension != null
                    && TranscriptionStatusExtension.Status.OFF.equals(transcriptionStatusExtension.getStatus()))
            {
                // puts the stopping in the single threaded executor
                // so we can order the events and avoid indicating active = false
                // while we are starting due to concurrent presences processed
                executorService.submit(this::stopTranscribing);
            }
            if (isRequestingTranscriber(presence) && !active)
            {
                executorService.submit(() -> this.startTranscribing(getBridgeRegions()));
            }
        }
    }

    /**
     * Returns a list of regions of the bridges that are currently used
     * in the conference, empty list if nothing found or an error occurs.
     * @return a list of used bridge regions.
     */
    @NotNull
    private Collection<String> getBridgeRegions()
    {
        return conference.getBridges().keySet().stream()
                    .map(Bridge::getRegion)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
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
    private void startTranscribing(@NotNull Collection<String> preferredRegions)
    {
        if (active)
        {
            return;
        }

        // We need a modifiable list for the "exclude" parameter.
        selectTranscriber(2, new ArrayList<>(), preferredRegions);
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
            int retryCount,
            @NotNull List<Jid> exclude,
            @NotNull Collection<String> preferredRegions)
    {
        logger.info("Attempting to invite transcriber");

        Jid jigasiJid = jigasiDetector.selectTranscriber(exclude, preferredRegions);

        if (jigasiJid == null)
        {
            logger.warn("Unable to invite transcriber due to no Jigasi instances being available");
            return;
        }

        RayoIqProvider.DialIq dialIq = new RayoIqProvider.DialIq();
        dialIq.setDestination("jitsi_meet_transcribe");
        dialIq.setTo(jigasiJid);
        dialIq.setType(IQ.Type.set);
        dialIq.setHeader("JvbRoomName", chatRoom.getName());

        try
        {
            IQ response = UtilKt.sendIqAndGetResponse(connection, dialIq);

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
                    logger.warn("failed to invite transcriber. Got error: " + response.getError().getErrorGenerator());
                    retry = true;
                }
            }
            else
            {
                logger.warn("failed to invite transcriber; lack of response from XmmpConnection");
                retry = true;
            }

            if (retry && retryCount > 0)
            {
                exclude.add(jigasiJid);

                selectTranscriber(retryCount - 1, exclude, preferredRegions);
            }
        }
        catch (SmackException.NotConnectedException e)
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
        if (presence == null)
        {
            return false;
        }

        TranscriptionRequestExtension ext =  presence.getExtension(
            TranscriptionRequestExtension.ELEMENT_NAME,
            TranscriptionRequestExtension.NAMESPACE);

        if (ext == null)
        {
            return false;
        }

        return Boolean.parseBoolean(ext.getText());
    }
}
