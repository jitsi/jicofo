/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.auth;

import net.java.sip.communicator.util.*;

import net.java.sip.communicator.util.Logger;
import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.reservation.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.*;
import org.jivesoftware.smack.packet.*;
import org.osgi.framework.*;

/**
 * Shibboleth implementation of {@link AuthenticationAuthority} interface.
 *
 * Authentication servlet {@link ShibbolethHandler} must be deployed under
 * the location secured by Shibboleth(called *login location*). When user wants
 * to login, the application retrieves login URL and redirects user to it(see
 * {@link #createLoginUrl(String, String, String, boolean)}. When user
 * attempts to access it will be asked for Shibboleth credentials. Once user
 * logs-in, request attributes will be filled by Shibboleth system including
 * 'email' which is treated as users identity. The servlet will bind
 * identity to new session ID which will be returned to the user. After user
 * has his session id it is stored in a cookie which can be used for
 * authenticating future requests.
 *
 * FIXME move to Shibboleth 'impl' package
 *
 * @author Pawel Domas
 */
public class ShibbolethAuthAuthority
    extends AbstractAuthAuthority
    implements AuthenticationAuthority
{
    /**
     * The logger.
     */
    private final static Logger logger
        = Logger.getLogger(ShibbolethAuthAuthority.class);

    /**
     * The name of configuration property that lists "reserved" rooms.
     * Reserved rooms is the room that can be created by unauthenticated users
     * even when authentication is required to create any room. List room
     * names separated by ",".
     */
    private static final String RESERVED_ROOMS_PNAME
            = "org.jitsi.jicofo.auth.RESERVED_ROOMS";

    /**
     * Value constant which should be passed as {@link
     * AuthBundleActivator#LOGIN_URL_PNAME} and {@link
     * AuthBundleActivator#LOGOUT_URL_PNAME} in order to use default
     * Shibboleth URLs for login and logout. It can not be skipped, because
     * Shibboleth will not be enabled otherwise.
     */
    public static final String DEFAULT_URL_CONST = "shibboleth:default";

    /**
     * Authentication URL pattern which uses {@link String#format(String,
     * Object...)} to insert string arguments into the request.<br/>
     * Arguments exposed to the service are:<br/>
     * %1$s - *machineUID* that is identifier of users machine which can be
     * used by the system to distinguish between session for the same login
     * on different machines.<br/>
     * %2$s - *usersJID* Jabber ID of the user who has requested the URL<br/>
     * %3$s - *roomName* full name of the conference MUC in the form of
     * "room@muc.server.net"<br/>
     * %4$s - *popup* indicates if this URL will be opened in a popup. In
     * this case session-id can be immediately passed to parent window using
     * CORS window messages.<br/>
     * Example:<br/>
     * 'https://external-authentication.server.net/login/?machineUID=%1$s
     * &room=%3$s&popup=%4$s'<br/>
     *
     */
    private String loginUrlPattern
        = "login/?machineUID=%1$s&room=%3$s&close=%4$s";

    /**
     * URL for logout location. Optionally session-id argument is
     * available:<br/>
     * %1$s - *session ID* authentication session identifier which will be
     * terminated.
     */
    private String logoutUrlPattern = "../Shibboleth.sso/Logout";

    /**
     * An array containing reserved rooms. See {@link #RESERVED_ROOMS_PNAME}.
     */
    private final String[] reservedRooms;

    // FIXME: get reservation system out of here
    private ReservationSystem reservationSystem;

    /**
     * Creates new instance of <tt>ShibbolethAuthAuthority</tt> with default
     * login and logout URL locations.
     */
    public ShibbolethAuthAuthority()
    {
        this(DEFAULT_URL_CONST, DEFAULT_URL_CONST);
    }

    /**
     * Creates new instance of {@link ShibbolethAuthAuthority}.
     * @param loginUrlPattern the pattern used for constructing external
     *        authentication URLs. See {@link #loginUrlPattern} for more info.
     */
    public ShibbolethAuthAuthority(String loginUrlPattern,
                                   String logoutUrlPattern)
    {
        // Override authenticate URL ?
        if (!StringUtils.isNullOrEmpty(loginUrlPattern)
                && !DEFAULT_URL_CONST.equals(loginUrlPattern))
        {
            this.loginUrlPattern = loginUrlPattern;
        }
        // Override or disable logout URL
        if (!DEFAULT_URL_CONST.equals(logoutUrlPattern))
        {
            this.logoutUrlPattern = logoutUrlPattern;
        }

        // Parse reserved rooms
        String reservedRoomsStr
            = FocusBundleActivator.getConfigService().getString
                (RESERVED_ROOMS_PNAME, "");

        reservedRooms = reservedRoomsStr.split(",");
    }

    /**
     * Start this authentication authority instance.
     */
    public void start()
    {
        BundleContext bc = FocusBundleActivator.bundleContext;

        this.reservationSystem
            = ServiceUtils.getService(bc, ReservationSystem.class);

        /*
        FIXME: handle conference ended event
        this.focusManager = ServiceUtils.getService(bc, FocusManager.class);

        focusManager.setFocusAllocationListener(this);*/

        super.start();
    }

    /**
     * Stops this authentication authority instance.
     */
    public void stop()
    {
        super.stop();

        /*if (focusManager != null)
        {
            focusManager.setFocusAllocationListener(null);
            focusManager = null;
        }*/
    }

    /**
     * Checks if given user is allowed to create the room.
     * @param sessionId authentication session identifier.
     * @param roomName the name of the conference room to be checked.
     * @return <tt>true</tt> if it's OK to create the room for given name on
     *         behalf of verified user or <tt>false</tt> otherwise.
     */
    boolean isAllowedToCreateRoom(String sessionId, String roomName)
    {
        String fullName = roomName;
        roomName = MucUtil.extractName(roomName);
        AuthenticationSession session = getSession(sessionId);
        // If there's no reservation system then allow based on authentication
        if (reservationSystem == null)
        {
            return isRoomReserved(roomName) || session != null;
        }
        // No session - no reservation check
        if (session == null)
        {
            return false;
        }

        int result
            = reservationSystem.createConference(
                    session.getUserIdentity(), fullName);
        logger.info("Create room result: " + result);
        return result == ReservationSystem.RESULT_OK;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isExternal()
    {
        return true;
    }

    private boolean isRoomReserved(String roomName)
    {
        for (String reservedRoom : reservedRooms)
        {
            if (reservedRoom.equals(roomName))
                return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public String createLoginUrl(String machineUID, String  userJid,
                                 String roomName,   boolean popup)
    {
        return String.format(
                loginUrlPattern, machineUID, userJid, roomName, popup);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String createLogoutUrl(String sessionId)
    {
        if (logoutUrlPattern == null)
        {
            return null;
        }
        // By default: "../Shibboleth.sso/Logout"
        return String.format(logoutUrlPattern, sessionId);
    }

    /**
     * Method called by the servlet in order to create new authentication
     * session.
     *
     * @param machineUID user's machine identifier that wil be used to
     *                   distinguish between the sessions for the same login
     *                   name on different machines.
     * @param authIdentity the identity obtained from external authentication
     *                     system that will be bound to the user's JID.
     * @return <tt>true</tt> if user has been authenticated successfully or
     *         <tt>false</tt> if given token is invalid.
     */
    String authenticateUser(String machineUID, String authIdentity)
    {
        synchronized (syncRoot)
        {
            AuthenticationSession session
                = findSessionForIdentity(machineUID, authIdentity);

            if (session == null)
            {
                session = createNewSession(machineUID, authIdentity);
            }

            return session.getSessionId();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isUserAuthenticated(String jabberId, String roomName)
    {
        return findSessionForJabberId(jabberId) != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected IQ processAuthLocked(
            ConferenceIq query, ConferenceIq response, boolean roomExists)
    {
        String room = query.getRoom();
        String peerJid = query.getFrom();

        String sessionId = query.getSessionId();
        AuthenticationSession session = getSession(sessionId);

        // Check for invalid session
        IQ error = verifySession(query);
        if (error != null)
        {
            return error;
        }

        // Security checks for 'create room' permissions
        if (!roomExists && !isAllowedToCreateRoom(sessionId, room))
        {
            logger.info(
                    "Not allowed to create the room: "
                            + peerJid + " " + "SID: " + sessionId);
            // Error not authorized
            return ErrorFactory.createNotAuthorizedError(query);
        }

        // Authenticate JID with session
        if (session != null)
        {
            authenticateJidWithSession(session, peerJid, response);
        }

        return null;
    }
}
