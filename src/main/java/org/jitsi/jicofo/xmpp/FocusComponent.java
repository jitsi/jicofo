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
package org.jitsi.jicofo.xmpp;

import org.jetbrains.annotations.*;
import org.jitsi.osgi.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.auth.*;
import org.jitsi.jicofo.reservation.*;
import org.jitsi.meet.*;
import org.jitsi.service.configuration.*;
import org.jitsi.utils.logging.*;
import org.jitsi.xmpp.component.*;
import org.jitsi.xmpp.util.*;

import org.jivesoftware.smack.packet.*;

import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.osgi.framework.*;
import org.xmpp.packet.IQ;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * XMPP component that listens for {@link ConferenceIq}
 * and allocates {@link org.jitsi.jicofo.JitsiMeetConference}s appropriately.
 *
 * @author Pawel Domas
 */
public class FocusComponent
    extends ComponentBase
    implements BundleActivator
{
    /**
     * The logger.
     */
    private final static Logger logger
        = Logger.getLogger(FocusComponent.class);

    /**
     * Name of system and configuration property which specifies the JID from
     * which shutdown requests will be accepted.
     */
    public static final String SHUTDOWN_ALLOWED_JID_PNAME
        = "org.jitsi.jicofo.SHUTDOWN_ALLOWED_JID";

    /**
     * The JID from which shutdown requests are accepted.
     */
    private Jid shutdownAllowedJid;

    /**
     * Indicates if the focus is anonymous user or authenticated system admin.
     */
    private final boolean isFocusAnonymous;

    /**
     * The JID of focus user that will enter the MUC room. Can be user to
     * recognize real focus of the conference.
     */
    private final String focusAuthJid;

    /**
     * The manager object that creates and expires
     * {@link org.jitsi.jicofo.JitsiMeetConference}s.
     */
    private FocusManager focusManager;

    /**
     * (Optional)Authentication authority used to verify user requests.
     */
    private AuthenticationAuthority authAuthority;

    /**
     * (Optional)Reservation system that manages new rooms allocation.
     * Requires authentication system in order to verify user's identity.
     */
    private ReservationSystem reservationSystem;

    /**
     * Creates new instance of <tt>FocusComponent</tt>.
     * @param host the hostname or IP address to which this component will be
     *             connected.
     * @param port the port of XMPP server to which this component will connect.
     * @param domain the name of main XMPP domain on which this component will
     *               be served.
     * @param subDomain the name of subdomain on which this component will be
     *                  available.
     * @param secret the password used by the component to authenticate with
     *               XMPP server.
     * @param anonymousFocus indicates if the focus user is anonymous.
     * @param focusAuthJid the JID of authenticated focus user which will be
     *                     advertised to conference participants.
     */
    public FocusComponent(String host, int port,
                          String domain, String subDomain,
                          String secret,
                          boolean anonymousFocus, String focusAuthJid)
    {
        super(host, port, domain, subDomain, secret);

        this.isFocusAnonymous = anonymousFocus;
        this.focusAuthJid = focusAuthJid;
    }

    /**
     * Initializes this component.
     */
    public void init()
    {
        OSGi.start(this);
    }

    /**
     * Method will be called by OSGi after {@link #init()} is called.
     */
    @Override
    public void start(BundleContext bc)
        throws Exception
    {
        ConfigurationService configService
            = ServiceUtils2.getService(bc, ConfigurationService.class);

        loadConfig(configService, "org.jitsi.jicofo");

        if (!isPingTaskStarted())
            startPingTask();

        String shutdownAllowedJid
                = configService.getString(SHUTDOWN_ALLOWED_JID_PNAME);
        this.shutdownAllowedJid
            = isBlank(shutdownAllowedJid)
                ? null
                : JidCreate.from(shutdownAllowedJid);

        authAuthority
            = ServiceUtils2.getService(bc, AuthenticationAuthority.class);
        focusManager = ServiceUtils2.getService(bc, FocusManager.class);
        reservationSystem
            = ServiceUtils2.getService(bc, ReservationSystem.class);
    }

    /**
     * Releases resources used by this instance.
     */
    public void dispose()
    {
        OSGi.stop(this);
    }

    /**
     * Methods will be invoked by OSGi after {@link #dispose()} is called.
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        authAuthority = null;
        focusManager = null;
        reservationSystem = null;
    }

    @Override
    public String getDescription()
    {
        return "Manages Jitsi Meet conferences";
    }

    @Override
    public String getName()
    {
        return "Jitsi Meet Focus";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String[] discoInfoFeatureNamespaces()
    {
        return
            new String[]
                {
                    ConferenceIq.NAMESPACE
                };
    }

    @Override
    protected IQ handleIQGetImpl(IQ iq)
        throws Exception
    {
        try
        {
            org.jivesoftware.smack.packet.IQ smackIq = IQUtils.convert(iq);
            if (smackIq instanceof LoginUrlIq)
            {
                org.jivesoftware.smack.packet.IQ result
                    = handleAuthUrlIq((LoginUrlIq) smackIq);
                return IQUtils.convert(result);
            }
            else
            {
                return super.handleIQGetImpl(iq);
            }
        }
        catch (Exception e)
        {
            logger.error(e, e);
            throw e;
        }
    }

    /**
     * Handles an <tt>org.xmpp.packet.IQ</tt> stanza of type <tt>set</tt> which
     * represents a request.
     *
     * @param iq the <tt>org.xmpp.packet.IQ</tt> stanza of type <tt>set</tt>
     * which represents the request to handle
     * @return an <tt>org.xmpp.packet.IQ</tt> stanza which represents the
     * response to the specified request or <tt>null</tt> to reply with
     * <tt>feature-not-implemented</tt>
     * @throws Exception to reply with <tt>internal-server-error</tt> to the
     * specified request
     */
    @Override
    public IQ handleIQSetImpl(IQ iq)
        throws Exception
    {
        try
        {
            org.jivesoftware.smack.packet.IQ smackIq = IQUtils.convert(iq);

            if (smackIq instanceof ConferenceIq)
            {
                org.jivesoftware.smack.packet.IQ response
                    = handleConferenceIq((ConferenceIq) smackIq);

                return IQUtils.convert(response);
            }
            else if (smackIq instanceof ShutdownIQ)
            {
                ShutdownIQ gracefulShutdownIQ
                    = (ShutdownIQ) smackIq;

                if (!gracefulShutdownIQ.isGracefulShutdown())
                {
                    return IQUtils.convert(
                        org.jivesoftware.smack.packet.IQ.createErrorResponse(
                            smackIq,
                            XMPPError.getBuilder(
                                    XMPPError.Condition.bad_request)));
                }

                Jid from = gracefulShutdownIQ.getFrom();
                BareJid bareFrom = from.asBareJid();

                if (shutdownAllowedJid == null
                    || !shutdownAllowedJid.equals(bareFrom))
                {
                    // Forbidden
                    XMPPError.Builder forbiddenError
                        = XMPPError.getBuilder(XMPPError.Condition.forbidden);

                    logger.warn("Rejected shutdown request from: " + from);

                    return IQUtils.convert(
                        org.jivesoftware.smack.packet.IQ.createErrorResponse(
                                smackIq, forbiddenError));
                }

                logger.info("Accepted shutdown request from: " + from);

                focusManager.enableGracefulShutdownMode();

                return IQUtils.convert(
                    org.jivesoftware.smack.packet.IQ.createResultIQ(smackIq));
            }
            else if (smackIq instanceof LogoutIq)
            {
                logger.info("Logout IQ received: " + iq.toXML());

                if (authAuthority == null)
                {
                    // not-implemented
                    return null;
                }

                org.jivesoftware.smack.packet.IQ smackResult
                    = authAuthority.processLogoutIq((LogoutIq) smackIq);

                return smackResult != null
                        ? IQUtils.convert(smackResult) : null;
            }
            else
            {
                return super.handleIQSetImpl(iq);
            }
        }
        catch (Exception e)
        {
            logger.error(e, e);
            throw e;
        }
    }

    /**
     * Additional logic added for conference IQ processing like
     * authentication and room reservation.
     *
     * @param query <tt>ConferenceIq</tt> query
     * @param response <tt>ConferenceIq</tt> response which can be modified
     *                 during this processing.
     * @param roomExists <tt>true</tt> if room mentioned in the <tt>query</tt>
     *                   already exists.
     *
     * @return <tt>null</tt> if everything went ok or an error/response IQ
     *         which should be returned to the user
     */
    public org.jivesoftware.smack.packet.IQ processExtensions(
            ConferenceIq query, ConferenceIq response, boolean roomExists)
    {
        Jid peerJid = query.getFrom();
        String identity = null;

        // Authentication
        if (authAuthority != null)
        {
            org.jivesoftware.smack.packet.IQ authErrorOrResponse
                    = authAuthority.processAuthentication(query, response);

            // Checks if authentication module wants to cancel further
            // processing and eventually returns it's response
            if (authErrorOrResponse != null)
            {
                return authErrorOrResponse;
            }
            // Only authenticated users are allowed to create new rooms
            if (!roomExists)
            {
                identity = authAuthority.getUserIdentity(peerJid);
                if (identity == null)
                {
                    // Error not authorized
                    return ErrorFactory.createNotAuthorizedError(
                        query, "not authorized user domain");
                }
            }
        }

        // Check room reservation?
        if (!roomExists && reservationSystem != null)
        {
            EntityBareJid room = query.getRoom();

            ReservationSystem.Result result
                = reservationSystem.createConference(identity, room);

            logger.info(
                "Create room result: " + result + " for " + room);

            if (result.getCode() != ReservationSystem.RESULT_OK)
            {
                return ErrorFactory
                        .createReservationError(query, result);
            }
        }

        return null;
    }

    @NotNull
    private org.jivesoftware.smack.packet.IQ handleConferenceIq(
            ConferenceIq query)
        throws Exception
    {
        ConferenceIq response = new ConferenceIq();
        EntityBareJid room = query.getRoom();

        logger.info("Focus request for room: " + room);

        boolean roomExists = focusManager.getConference(room) != null;

        if (focusManager.isShutdownInProgress() && !roomExists)
        {
            // Service unavailable
            return ColibriConferenceIQ
                    .createGracefulShutdownErrorResponse(query);
        }

        // Authentication and reservations system logic
        org.jivesoftware.smack.packet.IQ error
            = processExtensions(query, response, roomExists);
        if (error != null)
        {
            return error;
        }

        boolean ready
            = focusManager.conferenceRequest(
                    room, query.getPropertiesMap());

        if (!isFocusAnonymous && authAuthority == null)
        {
            // Focus is authenticated system admin, so we let them in
            // immediately. Focus will get OWNER anyway.
            ready = true;
        }

        response.setType(org.jivesoftware.smack.packet.IQ.Type.result);
        response.setStanzaId(query.getStanzaId());
        response.setFrom(query.getTo());
        response.setTo(query.getFrom());
        response.setRoom(query.getRoom());
        response.setReady(ready);

        // Config
        response.setFocusJid(focusAuthJid);

        // Authentication module enabled?
        response.addProperty(
            new ConferenceIq.Property(
                    "authentication",
                    String.valueOf(authAuthority != null)));

        if (authAuthority != null)
        {
            response.addProperty(
                new ConferenceIq.Property(
                        "externalAuth",
                        String.valueOf(authAuthority.isExternal())));
        }

        if (focusManager.getJitsiMeetServices().getSipGateway() != null
            || focusManager.getJitsiMeetServices().getJigasiDetector() != null)
        {
            response.addProperty(
                new ConferenceIq.Property("sipGatewayEnabled", "true"));
        }

        return response;
    }

    private org.jivesoftware.smack.packet.IQ handleAuthUrlIq(
            LoginUrlIq authUrlIq)
    {
        if (authAuthority == null)
        {
            XMPPError.Builder error
                = XMPPError.getBuilder(XMPPError.Condition.service_unavailable);
            return org.jivesoftware.smack.packet.IQ
                    .createErrorResponse(authUrlIq, error);
        }

        EntityFullJid peerFullJid
            = authUrlIq.getFrom().asEntityFullJidIfPossible();
        EntityBareJid roomName = authUrlIq.getRoom();
        if (roomName == null)
        {
            XMPPError.Builder error = XMPPError.getBuilder(
                    XMPPError.Condition.not_acceptable);
            return org.jivesoftware.smack.packet.IQ
                    .createErrorResponse(authUrlIq, error);
        }

        LoginUrlIq result = new LoginUrlIq();
        result.setType(org.jivesoftware.smack.packet.IQ.Type.result);
        result.setStanzaId(authUrlIq.getStanzaId());
        result.setTo(authUrlIq.getFrom());

        boolean popup =
            authUrlIq.getPopup() != null && authUrlIq.getPopup();

        String machineUID = authUrlIq.getMachineUID();
        if (isBlank(machineUID))
        {
            XMPPError.Builder error
                = XMPPError.from(
                    XMPPError.Condition.bad_request,
                    "missing mandatory attribute 'machineUID'");
            return org.jivesoftware.smack.packet.IQ
                    .createErrorResponse(authUrlIq, error);
        }

        String authUrl
            = authAuthority.createLoginUrl(
                machineUID, peerFullJid, roomName, popup);

        result.setUrl(authUrl);

        logger.info("Sending url: " + result.toXML());

        return result;
    }
}
