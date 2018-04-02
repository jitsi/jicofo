/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.impl.protocol.xmpp.colibri;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.event.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.xmpp.util.*;

import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.parts.*;

import java.util.*;

/**
 * Default implementation of {@link ColibriConference} that uses Smack for
 * handling XMPP connection. Handles conference state, allocates and expires
 * channels per single conference ID. Conference ID is stored after first
 * allocate channels request.
 *
 * @author Pawel Domas
 */
public class ColibriConferenceImpl
    implements ColibriConference
{
    private final static Logger logger
        = Logger.getLogger(ColibriConferenceImpl.class);

    /**
     * The instance of XMPP connection.
     */
    private final XmppConnection connection;

    /**
     * The {@link EventAdmin} instance used to emit video stream estimation
     * events.
     */
    private final EventAdmin eventAdmin;

    /**
     * XMPP address of videobridge component.
     */
    private Jid jitsiVideobridge;

    /**
     * The {@link ColibriConferenceIQ} that stores the state of whole conference
     */
    private ColibriConferenceIQ conferenceState = new ColibriConferenceIQ();

    /**
     * Lock used to synchronise access to the fields related with video channels
     * counting and video stream estimation events.
     */
    private final Object stateEstimationSync = new Object();

    /**
     * Synchronization root to sync access to {@link #colibriBuilder} and
     * {@link #conferenceState}.
     */
    private final Object syncRoot = new Object();

    /**
     * Custom type of semaphore that allows only 1 thread to send initial
     * Colibri IQ that creates the conference.
     * It means that if {@link #conferenceState} has no ID then only 1 thread
     * will be allowed to send allocate request to the bridge. Other threads
     * will be suspended until we have the response. Error response to create
     * request will cause <tt>OperationFailedException</tt> on waiting threads.
     *
     * By "create request" we mean a channel allocation Colibri IQ that has no
     * conference id specified.
     */
    private final ConferenceCreationSemaphore createConfSemaphore
        = new ConferenceCreationSemaphore();

    /**
     * The error code produced by the allocator thread which is to be passed to
     * the waiting threads, so that they will throw
     * {@link OperationFailedException} consistent with the allocator thread.
     *
     * Note: this and {@link #allocChannelsErrorMsg} are only used to modify
     * the message logged when an exception is thrown. They are NOT used to
     * decide whether to throw an exception or not.
     */
    private int allocChannelsErrorCode = -1;

    /**
     * The error message produced by the allocator thread which is to be passed
     * to the waiting threads, so that they will throw
     * {@link OperationFailedException} consistent with the allocator thread.
     */
    private String allocChannelsErrorMsg = null;

    /**
     * Utility used for building Colibri queries.
     */
    private final ColibriBuilder colibriBuilder
        = new ColibriBuilder(conferenceState);

    /**
     * Flag used to figure out if Colibri conference has been
     * allocated during last
     * {@link #createColibriChannels(boolean, String, String, boolean, List)}
     * call.
     */
    private boolean justAllocated = false;

    /**
     * Flag indicates that this instance has been disposed and should not be
     * used anymore.
     */
    private boolean disposed;

    /**
     * Counts how many video channels have been allocated in order to be able
     * to estimate video stream count changes.
     */
    private int videoChannels;

    /**
     * The global ID of the conference.
     */
    private String gid;

    /**
     * Creates new instance of <tt>ColibriConferenceImpl</tt>.
     * @param connection XMPP connection object that wil be used by the new
     *        instance to communicate.
     * @param eventAdmin {@link EventAdmin} instance which will be used to post
     *        {@link BridgeEvent#VIDEOSTREAMS_CHANGED}.
     */
    public ColibriConferenceImpl(XmppConnection    connection,
                                 EventAdmin        eventAdmin)
    {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.eventAdmin = Objects.requireNonNull(eventAdmin, "eventAdmin");
    }

    /**
     * Sets the "global" ID of the conference.
     * @param gid the value to set.
     */
    public void setGID(String gid)
    {
        this.gid = gid;
        conferenceState.setGID(gid);
    }

    /**
     * Checks if this instance has been disposed already and if so prints
     * a warning message. It will also cancel execution in case
     * {@link #jitsiVideobridge} is null or empty.
     *
     * @param operationName the name of the operation that will not happen and
     * should be mentioned in the warning message.
     *
     * @return <tt>true</tt> if this instance has been disposed already or
     * <tt>false</tt> otherwise.
     */
    private boolean checkIfDisposed(String operationName)
    {
        if (disposed)
        {
            logger.warn("Not doing " + operationName + " - instance disposed");
            return true;
        }
        if (jitsiVideobridge == null)
        {
            logger.error(
                "Not doing " + operationName + " - bridge not initialized");
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setJitsiVideobridge(Jid videobridgeJid)
    {
        if (!StringUtils.isNullOrEmpty(conferenceState.getID()))
        {
            throw new IllegalStateException(
                "Cannot change the bridge on active conference");
        }
        this.jitsiVideobridge = videobridgeJid;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Jid getJitsiVideobridge()
    {
        return this.jitsiVideobridge;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getConferenceId()
    {
        return conferenceState.getID();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setConfig(JitsiMeetConfig config)
    {
        synchronized (syncRoot)
        {
            colibriBuilder.setChannelLastN(config.getChannelLastN());
            colibriBuilder.setAudioPacketDelay(config.getAudioPacketDelay());
        }
    }

    /**
     * {@inheritDoc}
     * </p>
     * Blocks until a reply is received (and might also block waiting for
     * the conference to be allocated before sending the request).
     */
    @Override
    public ColibriConferenceIQ createColibriChannels(
            boolean useBundle,
            String endpointId,
            String statsId,
            boolean peerIsInitiator,
            List<ContentPacketExtension> contents,
            Map<String, List<SourcePacketExtension>> sourceMap,
            Map<String, List<SourceGroupPacketExtension>> sourceGroupsMap,
            List<String> octoRelayIds)
        throws OperationFailedException
    {
        ColibriConferenceIQ allocateRequest;
        // How many new video channels will be allocated
        final int newVideoChannelsCount
            = JingleOfferFactory.containsVideoContent(contents) ? 1 : 0;

        boolean conferenceExisted;
        try
        {
            synchronized (syncRoot)
            {
                // Only if not in 'disposed' state
                if (checkIfDisposed("createColibriChannels"))
                {
                    return null;
                }

                if (newVideoChannelsCount != 0)
                {
                    synchronized (stateEstimationSync)
                    {
                        trackVideoChannelsAddedRemoved(newVideoChannelsCount);
                    }
                }

                conferenceExisted
                    = !acquireCreateConferenceSemaphore(endpointId);

                colibriBuilder.reset();

                colibriBuilder.addAllocateChannelsReq(
                    useBundle,
                    endpointId,
                    statsId,
                    peerIsInitiator,
                    contents,
                    sourceMap,
                    sourceGroupsMap,
                    octoRelayIds);

                allocateRequest = colibriBuilder.getRequest(jitsiVideobridge);
            }

            if (logger.isDebugEnabled())
            {
                logger.debug(Thread.currentThread() + " sending alloc request");
            }

            logRequest("Channel allocate request", allocateRequest);

            // FIXME retry allocation on timeout ?
            Stanza response = sendAllocRequest(endpointId, allocateRequest);

            logResponse("Channel allocate response", response);

            // Verify the response and throw OperationFailedException
            // if it's not a success
            maybeThrowOperationFailed(response);

            /*
             * Update the complete ColibriConferenceIQ representation maintained by
             * this instance with the information given by the (current) response.
             */
            // FIXME: allocations!!! should be static method
            synchronized (syncRoot)
            {
                ColibriAnalyser analyser = new ColibriAnalyser(conferenceState);

                analyser.processChannelAllocResp((ColibriConferenceIQ) response);

                if (!conferenceExisted && getConferenceId() != null)
                {
                    justAllocated = true;
                }
            }

            /*
             * Formulate the result to be returned to the caller which is a subset
             * of the whole conference information kept by this CallJabberImpl and
             * includes the remote channels explicitly requested by the method
             * caller and their respective local channels.
             */
            return ColibriAnalyser.getResponseContents(
                        (ColibriConferenceIQ) response, contents);

        }
        catch (Exception e)
        {
            try
            {
                synchronized (syncRoot)
                {
                    // Emit channels expired
                    if (!checkIfDisposed("post channels expired on Exception"))
                    {
                        synchronized (stateEstimationSync)
                        {
                            trackVideoChannelsAddedRemoved(
                                -newVideoChannelsCount);
                        }
                    }
                }
            }
            catch (Exception innerException)
            {
                // Log the inner Exception
                logger.error(innerException.getMessage(), innerException);
            }

            throw e;
        }
        finally
        {
            releaseCreateConferenceSemaphore(endpointId);
        }
    }

    /**
     * Verifies the JVB's response to allocate channel request and sets
     * {@link #allocChannelsErrorCode} and {@link #allocChannelsErrorMsg}.
     *
     * @param response the packet received from JVB(null == timeout) as
     *                 a response to Colibri allocate channels request.
     *
     * @throws OperationFailedException with error code set to:
     * <li>{@link OperationFailedException#NETWORK_FAILURE}</li> in case of
     * a timeout.
     * <li>{@link OperationFailedException#ILLEGAL_ARGUMENT}</li> in case of
     * "bad request" response returned by the JVB
     * <li>{@link OperationFailedException#GENERAL_ERROR}</li> in case of other
     * error that may indicate that the JVB instance is faulty.
     *
     */
    private void maybeThrowOperationFailed(Stanza response)
        throws OperationFailedException
    {
        // This code block must be protected, because in the last "if" a
        // decision is made based on the value of allocChannelsErrorCode, which
        // could have been written by another thread.
        synchronized (syncRoot)
        {
            if (response == null)
            {
                allocChannelsErrorCode
                    = OperationFailedException.NETWORK_FAILURE;
                allocChannelsErrorMsg
                    = "Failed to allocate colibri channels: response is null."
                            + " Maybe the response timed out.";
            }
            else if (response.getError() != null)
            {
                XMPPError error = response.getError();
                if (XMPPError.Condition
                    .bad_request.equals(error.getCondition()))
                {
                    allocChannelsErrorCode
                        = OperationFailedException.ILLEGAL_ARGUMENT;
                    allocChannelsErrorMsg
                        = "Failed to allocate colibri channels - bad request: "
                                + response.toXML();
                }
                else
                {
                    allocChannelsErrorCode
                        = OperationFailedException.GENERAL_ERROR;
                    allocChannelsErrorMsg
                        = "Failed to allocate colibri channels: "
                                + response.toXML();
                }
            }
            else if (!(response instanceof ColibriConferenceIQ))
            {
                allocChannelsErrorCode = OperationFailedException.GENERAL_ERROR;
                allocChannelsErrorMsg
                    = "Failed to allocate colibri channels: response is not a"
                            + " colibri conference";
            }
            else
            {
                allocChannelsErrorCode = -1;
                allocChannelsErrorMsg = null;
            }

            if (allocChannelsErrorCode != -1)
            {
                throw new OperationFailedException(
                    allocChannelsErrorMsg, allocChannelsErrorCode,
                    response == null
                        ? null
                        : new Exception(response.toXML().toString()));
            }
        }
    }

    /**
     * Obtains create conference semaphore. If the conference does not exist yet
     * (ID == null) then only first thread will be allowed to obtain it and all
     * other threads will have to wait for it to process response packet.
     *
     * Methods exposed for unit test purpose.
     *
     * @param endpointId the ID of the Colibri endpoint (conference participant)
     *
     * @return <tt>true</tt> if current thread is conference creator.
     *
     * @throws OperationFailedException if conference creator thread has failed
     *         to allocate new conference and current thread has been waiting
     *         to acquire the semaphore.
     */
    protected boolean acquireCreateConferenceSemaphore(String endpointId)
        throws OperationFailedException
    {
        return createConfSemaphore.acquire();
    }

    /**
     * Releases "create conference semaphore". Must be called to release the
     * semaphore possibly in "finally" block.
     *
     * @param endpointId the ID of the colibri conference endpoint(participant)
     */
    protected void releaseCreateConferenceSemaphore(String endpointId)
    {
        createConfSemaphore.release();
    }

    /**
     * Sends Colibri packet and waits for response in
     * {@link #createColibriChannels(boolean, String, String, boolean, List)}
     * call.
     *
     * Exposed for unit tests purpose.
     *
     * @param endpointId The ID of the Colibri endpoint.
     * @param request Colibri IQ to be send towards the bridge.
     *
     * @return <tt>Packet</tt> which is JVB response or <tt>null</tt> if
     *         the request timed out.
     *
     * @throws OperationFailedException see throws description of
     * {@link XmppConnection#sendPacketAndGetReply(IQ)}.
     */
    protected Stanza sendAllocRequest(String endpointId,
                                      ColibriConferenceIQ request)
        throws OperationFailedException
    {
        return connection.sendPacketAndGetReply(request);
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasJustAllocated()
    {
        synchronized (syncRoot)
        {
            if (justAllocated)
            {
                justAllocated = false;
                return true;
            }
            return false;
        }
    }

    private void logResponse(String message, Stanza response)
    {
        if (!logger.isDebugEnabled())
        {
            return;
        }

        String responseXml = IQUtils.responseToXML(response);

        responseXml = responseXml.replace(">",">\n");

        logger.debug(message + "\n" + responseXml);
    }

    private void logRequest(String message, IQ iq)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(message + "\n" + iq.toXML().toString()
                    .replace(">",">\n"));
        }
    }

    /**
     * {@inheritDoc}
     * </t>
     * Does not block or wait for a response.
     */
    @Override
    public void expireChannels(ColibriConferenceIQ channelInfo)
    {
        ColibriConferenceIQ request;

        synchronized (syncRoot)
        {
            // Only if not in 'disposed' state
            if (checkIfDisposed("expireChannels"))
            {
                return;
            }

            colibriBuilder.reset();

            colibriBuilder.addExpireChannelsReq(channelInfo);

            request = colibriBuilder.getRequest(jitsiVideobridge);
        }

        if (request != null)
        {
            logRequest("Expire peer channels", request);

            connection.sendStanza(request);

            synchronized (stateEstimationSync)
            {
                int expiredVideoChannels
                    = ColibriConferenceIQUtil.getChannelCount(
                            channelInfo, "video");

                trackVideoChannelsAddedRemoved(-expiredVideoChannels);
            }
        }
    }

    /**
     * {@inheritDoc}
     * </t>
     * Does not block or wait for a response.
     */
    @Override
    public void updateRtpDescription(
            Map<String, RtpDescriptionPacketExtension> descriptionMap,
            ColibriConferenceIQ localChannelsInfo)
    {
        ColibriConferenceIQ request;

        synchronized (syncRoot)
        {
            // Only if not in 'disposed' state
            if (checkIfDisposed("updateRtpDescription"))
            {
                return;
            }

            colibriBuilder.reset();

            for (String contentName : descriptionMap.keySet())
            {
                ColibriConferenceIQ.Channel channel
                    = localChannelsInfo.getContent(contentName)
                        .getChannels().get(0);
                colibriBuilder.addRtpDescription(
                        descriptionMap.get(contentName),
                        contentName,
                        channel);
            }

            request = colibriBuilder.getRequest(jitsiVideobridge);
        }

        if (request != null)
        {
            logRequest("Sending RTP desc update: ", request);

            connection.sendStanza(request);
        }
    }

    /**
     * {@inheritDoc}
     * </t>
     * Does not block or wait for a response.
     */
    @Override
    public void updateTransportInfo(
            Map<String, IceUdpTransportPacketExtension> transportMap,
            ColibriConferenceIQ localChannelsInfo)
    {
        ColibriConferenceIQ request;

        synchronized (syncRoot)
        {
            if (checkIfDisposed("updateTransportInfo"))
            {
                return;
            }

            colibriBuilder.reset();

            colibriBuilder
                .addTransportUpdateReq(transportMap, localChannelsInfo);

            request = colibriBuilder.getRequest(jitsiVideobridge);
        }

        if (request != null)
        {
            logRequest("Sending transport info update: ", request);

            connection.sendStanza(request);
        }
    }

    /**
     * {@inheritDoc}
     * </t>
     * Does not block or wait for a response.
     */
    @Override
    public void updateSourcesInfo(MediaSourceMap sources,
                                  MediaSourceGroupMap sourceGroups,
                                  ColibriConferenceIQ localChannelsInfo)
    {
        ColibriConferenceIQ request;

        synchronized (syncRoot)
        {
            if (checkIfDisposed("updateSourcesInfo"))
            {
                return;
            }

            if (StringUtils.isNullOrEmpty(conferenceState.getID()))
            {
                logger.error(
                        "Have not updated source info on the bridge - "
                            + "no conference in progress");
                return;
            }

            colibriBuilder.reset();

            boolean send = false;

            // sources
            if (sources != null
                    && colibriBuilder.addSourceInfo(
                            sources.toMap(), localChannelsInfo))
            {
                send = true;
            }
            // ssrcGroups
            if (sourceGroups != null
                    && colibriBuilder.addSourceGroupsInfo(
                            sourceGroups.toMap(), localChannelsInfo))
            {
                send = true;
            }

            request = send ? colibriBuilder.getRequest(jitsiVideobridge) : null;
        }

        if (request != null)
        {
            logRequest("Sending source update: ", request);

            connection.sendStanza(request);
        }
    }

    /**
     * {@inheritDoc}
     * </t>
     * Does not block or wait for a response.
     */
    @Override
    public void updateBundleTransportInfo(
            IceUdpTransportPacketExtension transport,
            String channelBundleId)
    {
        ColibriConferenceIQ request;

        synchronized (syncRoot)
        {
            if (checkIfDisposed("updateBundleTransportInfo"))
            {
                return;
            }

            colibriBuilder.reset();

            colibriBuilder.addBundleTransportUpdateReq(
                    transport, channelBundleId);

            request = colibriBuilder.getRequest(jitsiVideobridge);
        }

        if (request != null)
        {
            logRequest("Sending bundle transport info update: ", request);

            connection.sendStanza(request);
        }
    }

    /**
     * {@inheritDoc}
     * </t>
     * Does not block or wait for a response.
     */
    @Override
    public void expireConference()
    {
        ColibriConferenceIQ request;

        synchronized (syncRoot)
        {
            if (checkIfDisposed("expireConference"))
            {
                return;
            }

            colibriBuilder.reset();

            if (StringUtils.isNullOrEmpty(conferenceState.getID()))
            {
                logger.info("Nothing to expire - no conference allocated yet");
                return;
            }

            // Expire all channels
            if (colibriBuilder.addExpireChannelsReq(conferenceState))
            {
                request = colibriBuilder.getRequest(jitsiVideobridge);

                if (request != null)
                {
                    logRequest("Expire conference: ", request);

                    connection.sendStanza(request);
                }
            }

            // Reset conference state
            conferenceState = new ColibriConferenceIQ();

            // Mark instance as 'disposed'
            dispose();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispose()
    {
        this.disposed = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDisposed()
    {
        return disposed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean muteParticipant(ColibriConferenceIQ channelsInfo,
                                   boolean mute)
    {
        if (checkIfDisposed("muteParticipant"))
        {
            return false;
        }

        ColibriConferenceIQ request = new ColibriConferenceIQ();
        request.setID(conferenceState.getID());
        request.setName(conferenceState.getName());

        ColibriConferenceIQ.Content audioContent
            = channelsInfo.getContent("audio");

        if (audioContent == null || StringUtils.isNullOrEmpty(request.getID()))
        {
            logger.error("Failed to mute - no audio content." +
                             " Conf ID: " + request.getID());
            return false;
        }

        ColibriConferenceIQ.Content requestContent
            = new ColibriConferenceIQ.Content(audioContent.getName());

        for (ColibriConferenceIQ.Channel channel : audioContent.getChannels())
        {
            ColibriConferenceIQ.Channel requestChannel
                = new ColibriConferenceIQ.Channel();

            requestChannel.setID(channel.getID());

            requestChannel.setDirection(
                    mute ? MediaDirection.SENDONLY : MediaDirection.SENDRECV);

            requestContent.addChannel(requestChannel);
        }

        if (requestContent.getChannelCount() == 0)
        {
            logger.error("Failed to mute - no channels to modify." +
                             " ConfID:" + request.getID());
            return false;
        }

        request.setType(IQ.Type.set);
        request.setTo(jitsiVideobridge);

        request.addContent(requestContent);

        connection.sendStanza(request);

        // FIXME wait for response and set local status

        return true;
    }

    /**
     * Sets world readable name that identifies the conference.
     * @param name the new name.
     */
    public void setName(Localpart name)
    {
        conferenceState.setName(name);
    }

    /**
     * Gets world readable name that identifies the conference.
     * @return the name.
     */
    public Localpart getName()
    {
        return conferenceState.getName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateChannelsInfo(
            ColibriConferenceIQ localChannelsInfo,
            Map<String, RtpDescriptionPacketExtension> descriptionMap,
            MediaSourceMap sources,
            MediaSourceGroupMap sourceGroups,
            IceUdpTransportPacketExtension bundleTransport,
            Map<String, IceUdpTransportPacketExtension> transportMap,
            String endpointId,
            List<String> relays)
    {
        ColibriConferenceIQ request;
        if (localChannelsInfo == null)
        {
            logger.error("Can not update channels -- null");
            return;
        }

        synchronized (syncRoot)
        {
            if (checkIfDisposed("updateChannelsInfo"))
            {
                return;
            }

            colibriBuilder.reset();

            boolean send = false;

            // RTP description
            if (descriptionMap != null)
            {
                for (String contentName : descriptionMap.keySet())
                {
                    ColibriConferenceIQ.Channel channel
                        = localChannelsInfo.getContent(contentName)
                            .getChannels().get(0);
                    send |= colibriBuilder.addRtpDescription(
                            descriptionMap.get(contentName),
                            contentName,
                            channel);
                }
            }
            // SSRCs
            if (sources != null
                    && colibriBuilder.addSourceInfo(
                            sources.toMap(), localChannelsInfo))
            {
                send = true;
            }
            // SSRC groups
            if (sourceGroups != null
                    && colibriBuilder.addSourceGroupsInfo(
                            sourceGroups.toMap(), localChannelsInfo))
            {
                send = true;
            }
            // Bundle transport...
            if (bundleTransport != null
                    && colibriBuilder.addBundleTransportUpdateReq(
                            bundleTransport, endpointId))
            {
                send = true;
            }
            // ...or non-bundle transport
            else if (transportMap != null
                    && colibriBuilder.addTransportUpdateReq(
                            transportMap, localChannelsInfo))
            {
                send = true;
            }
            if (relays != null
                    && colibriBuilder.addOctoRelays(relays, localChannelsInfo))
            {
                send = true;
            }

            request = send ? colibriBuilder.getRequest(jitsiVideobridge) : null;
        }

        if (request != null)
        {
            logRequest("Sending channel info update: ", request);

            connection.sendStanza(request);
        }
    }

    /**
     * Calculates how the video stream count will change after given video
     * channel count modifier is applied to the current value. Video stream
     * count is expressed as video channel count to the power of two.
     * E.g.:
     * current channels = 5
     * modifier = -2
     * result = 3 * 3 - 5 * 5 = -16
     * Read that as "there will be 16 video streams less, after 2 video channels
     * are removed".
     *
     * @param currentChannels how many video channels are there now
     * @param channelCountDiff how many video channels are to be added/removed
     *
     * @return how many video streams will be added/removed when given
     * <tt>channelCountDiff</tt> is applied to the current value.
     */
    static private int calcVideoStreamDiff(int currentChannels,
                                           int channelCountDiff)
    {
        int newChannelCount = currentChannels + channelCountDiff;
        return (newChannelCount * newChannelCount)
                    - (currentChannels * currentChannels);
    }

    /**
     * Method called whenever video channels are about to be allocated/expired,
     * but before the actual request is sent. It will track the current video
     * channel count and emit {@link BridgeEvent#VIDEOSTREAMS_CHANGED}.
     *
     * @param channelsDiff how many new video channels are to be
     *        allocated/expired.
     */
    private void trackVideoChannelsAddedRemoved(int channelsDiff)
    {
        if (channelsDiff == 0)
        {
            return;
        }

        int streamDiff = calcVideoStreamDiff(videoChannels, channelsDiff);
        videoChannels += channelsDiff;

        if (streamDiff != 0)
        {
            eventAdmin.postEvent(
                    BridgeEvent.createVideoStreamsChanged(
                            jitsiVideobridge, streamDiff));
        }
        else
        {
            logger.error(
                "Stream diff is zero ??? channels diff: " + channelsDiff);
        }
    }

    /**
     * Custom type of semaphore that allows only 1 thread to send initial
     * Colibri IQ that creates the conference.
     * It means that if {@link #conferenceState} has no ID then only 1 thread
     * will be allowed to send allocate request to the bridge. Other threads
     * will be suspended until we have the response(from which we get our
     * conference ID). Error response to create request will cause
     * <tt>OperationFailedException</tt> on the threads waiting on this
     * semaphore.
     */
    class ConferenceCreationSemaphore
    {
        /**
         * Stores reference to conference creator thread instance.
         */
        private Thread creatorThread;

        /**
         * Acquires conference creation semaphore. If we don't have conference
         * ID yet then only first thread to obtain will be allowed to go through
         * and all other threads will be suspended until it finishes it's job.
         * Once we have a conference allocated all threads are allowed to go
         * through immediately.
         *
         * @return <tt>true</tt> if current thread has just become a conference
         *         creator. That is the thread that sends first channel allocate
         *         request that results in new conference created.
         *
         * @throws OperationFailedException if we are not conference creator
         *         thread and conference creator has failed to create the
         *         conference while we've been waiting on this semaphore.
         */
        public boolean acquire()
            throws OperationFailedException
        {
            synchronized (syncRoot)
            {
                Jid jvbInUse = jitsiVideobridge;

                if (conferenceState.getID() == null && creatorThread == null)
                {
                    creatorThread = Thread.currentThread();

                    if (logger.isDebugEnabled())
                    {
                        logger.debug("I'm the conference creator - " +
                                         Thread.currentThread().getName());
                    }

                    return true;
                }
                else
                {
                    if (logger.isDebugEnabled())
                    {
                        logger.debug(
                            "Will have to wait until the conference " +
                                "is created - " + Thread.currentThread()
                                .getName());
                    }

                    while (creatorThread != null)
                    {
                        try
                        {
                            syncRoot.wait();
                        }
                        catch (InterruptedException e)
                        {
                            throw new RuntimeException(e);
                        }
                    }

                    if (conferenceState.getID() == null)
                    {
                        throw new OperationFailedException(
                            "Creator thread has failed to "
                                + "allocate channels on: " + jvbInUse
                                + ", msg: " + allocChannelsErrorMsg,
                            allocChannelsErrorCode);
                    }

                    if (logger.isDebugEnabled())
                    {
                        logger.debug(
                            "Conference created ! Continuing with " +
                                "channel allocation -" +
                                Thread.currentThread().getName());
                    }
                }
            }
            return false;
        }

        /**
         * Releases this semaphore instance. If we're a conference creator then
         * all waiting thread will be woken up.
         */
        public void release()
        {
            synchronized (syncRoot)
            {
                if (creatorThread == Thread.currentThread())
                {
                    if (logger.isDebugEnabled())
                    {
                        logger.debug(
                               "Conference creator is releasing the lock - "
                                    + Thread.currentThread().getName());
                    }

                    creatorThread = null;
                    syncRoot.notifyAll();
                }
            }
        }
    }
}
