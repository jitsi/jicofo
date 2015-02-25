/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.auth.*;
import org.jitsi.util.*;
import org.jivesoftware.smack.packet.*;
import org.xmpp.component.*;
import org.xmpp.packet.IQ;

/**
 * XMPP component that listens for {@link ConferenceIq}
 * and allocates {@link org.jitsi.jicofo.JitsiMeetConference}s appropriately.
 *
 * @author Pawel Domas
 */
public class FocusComponent
    extends AbstractComponent
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
        = "org.jitsi.focus.shutdown.ALLOWED_JID";

    /**
     * The JID from which shutdown request are accepted.
     */
    private final String shutdownAllowedJid;

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
     * Optional password for focus user authentication. If authenticated login
     * is used we expect focus user to have admin privileges, so that it has
     * explicit moderator rights. Also in this case focus component will always
     * return 'ready=true' status, so that users don't have to wait for
     * the focus to create the room. If focus is authenticated and is not
     * an admin then will refuse to join MUC room.
     */

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
     * Creates new instance of <tt>FocusComponent</tt>.
     * @param anonymousFocus indicates if the focus user is anonymous.
     * @param focusAuthJid the JID of authenticated focus user which will be
     *                     advertised to conference participants.
     */
    public FocusComponent(boolean anonymousFocus, String focusAuthJid)
    {
        this.isFocusAnonymous = anonymousFocus;
        this.focusAuthJid = focusAuthJid;
        this.shutdownAllowedJid
            = FocusBundleActivator.getConfigService()
                    .getString(SHUTDOWN_ALLOWED_JID_PNAME);

        new ConferenceIqProvider();
        new ColibriIQProvider();
    }

    /**
     * Initializes this component.
     */
    public void init()
    {
        this.focusManager = ServiceUtils.getService(
            FocusBundleActivator.bundleContext, FocusManager.class);

        this.authAuthority = ServiceUtils.getService(
            FocusBundleActivator.bundleContext, AuthenticationAuthority.class);

        focusManager.start();
    }

    /**
     * Releases resources used by this instance.
     */
    public void dispose()
    {
        focusManager.stop();
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
    protected IQ handleIQGet(IQ iq)
        throws Exception
    {
        try
        {
            org.jivesoftware.smack.packet.IQ smackIq = IQUtils.convert(iq);
            if (smackIq instanceof ColibriStatsIQ)
            {
                // Reply with stats
                ColibriStatsIQ statsReply = new ColibriStatsIQ();

                statsReply.setType(
                    org.jivesoftware.smack.packet.IQ.Type.RESULT);
                statsReply.setPacketID(iq.getID());
                statsReply.setTo(iq.getFrom().toString());

                int conferenceCount = focusManager.getConferenceCount();

                // Return conference count
                statsReply.addStat(
                    new ColibriStatsExtension.Stat(
                        "conferences",
                        Integer.toString(conferenceCount)));
                statsReply.addStat(
                    new ColibriStatsExtension.Stat(
                        "graceful_shutdown",
                        focusManager.isShutdownInProgress()
                                ? "true" : "false"));

                return IQUtils.convert(statsReply);
            }
            else if (smackIq instanceof LoginUrlIQ)
            {
                org.jivesoftware.smack.packet.IQ result
                    = handleAuthUrlIq((LoginUrlIQ) smackIq);
                return IQUtils.convert(result);
            }
            else
            {
                return super.handleIQGet(iq);
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
     * @see AbstractComponent#handleIQSet(IQ)
     */
    @Override
    public IQ handleIQSet(IQ iq)
        throws Exception
    {
        try
        {
            org.jivesoftware.smack.packet.IQ smackIq = IQUtils.convert(iq);

            if (smackIq instanceof ConferenceIq)
            {
                ConferenceIq query = (ConferenceIq) smackIq;
                ConferenceIq response = new ConferenceIq();
                String peerJid = query.getFrom();
                String room = query.getRoom();

                logger.info("Focus request for room: " + room);

                boolean roomExists = focusManager.getConference(room) != null;

                if (focusManager.isShutdownInProgress() && !roomExists)
                {
                    // Service unavailable
                    org.jivesoftware.smack.packet.IQ smackReply
                        = ColibriConferenceIQ
                            .createGracefulShutdownErrorResponse(query);
                    // Fix error responses
                    return IQUtils.convert(smackReply);
                }

                // Authentication
                if (authAuthority != null)
                {
                    org.jivesoftware.smack.packet.IQ authErrorOrResponse
                        = authAuthority.processAuthentication(
                                query, response, roomExists);

                    // Checks if authentication module wants to cancel further
                    // processing and eventually returns it's response
                    if (authErrorOrResponse != null)
                    {
                        return IQUtils.convert(authErrorOrResponse);
                    }
                }

                boolean ready
                    = focusManager.conferenceRequest(
                            room, query.getPropertiesMap());

                if (!isFocusAnonymous && authAuthority == null)
                {
                    // Focus is authenticated system admin, so we let
                    // them in immediately. Focus will get OWNER anyway.
                    ready = true;
                }

                response.setType(org.jivesoftware.smack.packet.IQ.Type.RESULT);
                response.setPacketID(query.getPacketID());
                response.setFrom(query.getTo());
                response.setTo(query.getFrom());
                response.setRoom(query.getRoom());
                response.setReady(ready);

                // Config
                response.setFocusJid(focusAuthJid);

                // Authentication module enabled ?
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
                if (focusManager.getJitsiMeetServices().getSipGateway() != null)
                {
                    response.addProperty(
                        new ConferenceIq.Property("sipGatewayEnabled", "true"));
                }

                return IQUtils.convert(response);
            }
            else if (smackIq instanceof GracefulShutdownIQ)
            {
                GracefulShutdownIQ gracefulShutdownIQ
                    = (GracefulShutdownIQ) smackIq;

                String from = gracefulShutdownIQ.getFrom();
                String bareFrom
                    = org.jivesoftware.smack.util.StringUtils
                        .parseBareAddress(from);

                if (StringUtils.isNullOrEmpty(shutdownAllowedJid)
                    || !shutdownAllowedJid.equals(bareFrom))
                {
                    // Forbidden
                    XMPPError forbiddenError
                        = new XMPPError(XMPPError.Condition.forbidden);

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
                return super.handleIQSet(iq);
            }
        }
        catch (Exception e)
        {
            logger.error(e, e);
            throw e;
        }
    }

    private org.jivesoftware.smack.packet.IQ handleAuthUrlIq(
            LoginUrlIQ authUrlIq)
    {
        if (authAuthority == null)
        {
            XMPPError error
                = new XMPPError(XMPPError.Condition.service_unavailable);
            return org.jivesoftware.smack.packet.IQ
                    .createErrorResponse(authUrlIq, error);
        }

        String peerFullJid = authUrlIq.getFrom();
        String roomName = authUrlIq.getRoom();
        if (StringUtils.isNullOrEmpty(roomName))
        {
            XMPPError error = new XMPPError(XMPPError.Condition.no_acceptable);
            return org.jivesoftware.smack.packet.IQ
                    .createErrorResponse(authUrlIq, error);
        }

        LoginUrlIQ result = new LoginUrlIQ();
        result.setType(org.jivesoftware.smack.packet.IQ.Type.RESULT);
        result.setPacketID(authUrlIq.getPacketID());
        result.setTo(authUrlIq.getFrom());

        boolean popup =
            authUrlIq.getPopup() != null && authUrlIq.getPopup();

        String machineUID = authUrlIq.getMachineUID();
        if (StringUtils.isNullOrEmpty(machineUID))
        {
            XMPPError error
                = new XMPPError(
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
