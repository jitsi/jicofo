/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.auth;

import net.java.sip.communicator.util.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.jicofo.*;
import org.jitsi.util.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Class responsible for keeping track of users authentication with external
 * systems.
 * <br/>
 * Authentication flow:
 * <ol><li>User asks for authentication URL that contains authentication token
 * used to identify the request. See {@link #createAuthenticationUrl(String,
 * String)}</li><li>
 * User visits authentication URL and does authenticate with an external system.
 * </li><li>
 * Once user authenticates it is redirected by external system to our handler
 * servlet({@link ShibbolethHandler}. It uses the token to identify the
 * request and bind authentication ID to user's JID. See {@link
 * #authenticateUser(String, String)}
 * </li><li>
 * Users JID is authenticated for the time of the conference.
 * </li></ol>
 * <br/>
 * Authentication tokens once generated and not used will expire after
 * {@link #TOKEN_LIFETIME}. If user authenticates before conference has
 * started for the purpose of creation of the room and will not create it,
 * then authentication will expire after {@link #PRE_AUTHENTICATION_LIFETIME}.
 *
 * @author Pawel Domas
 */
public class AuthAuthority
    implements FocusManager.FocusAllocationListener
{
    /**
     * The logger.
     */
    private final static Logger logger = Logger.getLogger(AuthAuthority.class);

    /**
     * Interval at which we check for token/authentication states expiration.
     */
    private final static long EXPIRE_POLLING_INTERVAL = 10000L;

    /**
     * Pre-authentication lifetime in milliseconds. That is how long do we
     * keep authentication state valid before the room gets created. After
     * that authentication is valid for the time of the conference.
     */
    private final static long PRE_AUTHENTICATION_LIFETIME = 30000L;

    /**
     * Authentication token lifetime in milliseconds.
     */
    private final static long TOKEN_LIFETIME = 30000L;

    /**
     * The name of configuration property that lists "reserved" rooms.
     * Reserved rooms is the room that can be created by unauthenticated users
     * even when authentication is required to create any room. List room
     * names separated by ",".
     */
    private static final String RESERVED_ROOMS_PNAME
            = "org.jitsi.jicofo.auth.RESERVED_ROOMS";

    /**
     * Synchronization root.
     */
    private final Object syncRoot = new Object();

    /**
     * Authentication URL pattern which uses {@link String#format(String,
     * Object...)} to insert token into the URL as first string argument.<br/>
     * For example:<br/>
     * 'https://external-authentication.server.net/auth?token=%1$s'<br/>
     * Given that the token is '1234' the patter above will result in URL:<br/>
     * 'https://external-authentication.server.net/auth?token=1234'
     */
    private final String authUrlPattern;

    /**
     * The map of user JIDs to {@link AuthenticationState}.
     */
    private Map<String, AuthenticationState> authenticationStateMap
            = new HashMap<String, AuthenticationState>();

    /**
     * The map of token's string representation to {@link AuthenticationToken}.
     */
    private Map<String, AuthenticationToken> tokensMap
            = new HashMap<String, AuthenticationToken>();

    /**
     * Parent {@link FocusManager}.
     */
    private FocusManager focusManager;

    /**
     * The list of registered {@link AuthenticationListener}s.
     */
    private List<AuthenticationListener> authenticationListeners
            = new CopyOnWriteArrayList<AuthenticationListener>();

    /**
     * The timer used to check for the expiration of tokens and/or
     * authentication states.
     */
    private Timer expireTimer;

    /**
     * An array containing reserved rooms. See {@link #RESERVED_ROOMS_PNAME}.
     */
    private final String[] reservedRooms;

    /**
     * Creates new instance of {@link AuthAuthority}.
     * @param authUrlPattern the pattern used for constructing external
     *        authentication URLs. See {@link #authUrlPattern} for more info.
     */
    public AuthAuthority(String authUrlPattern)
    {
        if (StringUtils.isNullOrEmpty(authUrlPattern)) {
            throw new IllegalArgumentException(
                "Invalid auth url: '" + authUrlPattern + "'");
        }
        this.authUrlPattern = authUrlPattern;

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
        expireTimer = new Timer("AuthenticationExpireTimer", true);
        expireTimer.scheduleAtFixedRate(
            new ExpireTask(), EXPIRE_POLLING_INTERVAL, EXPIRE_POLLING_INTERVAL);

        this.focusManager
            = ServiceUtils.getService(
                    FocusBundleActivator.bundleContext, FocusManager.class);

        focusManager.setFocusAllocationListener(this);
    }

    /**
     * Stops this authentication authority instance.
     */
    public void stop()
    {
        if (expireTimer != null)
        {
            expireTimer.cancel();
            expireTimer = null;
        }
        if (focusManager != null)
        {
            focusManager.setFocusAllocationListener(null);
            focusManager = null;
        }
    }

    /**
     * Checks if given user is allowed to create the room.
     * @param peerJid the Jabber ID of the user.
     * @param roomName the name of the conference room to be checked.
     * @return <tt>true</tt> if it's OK to create the room for given name on
     *         behalf of verified user or <tt>false</tt> otherwise.
     */
    public boolean isAllowedToCreateRoom(String peerJid, String roomName)
    {
        if (roomName.contains("@"))
        {
            roomName = roomName.substring(0, roomName.indexOf("@"));
        }
        return isRoomReserved(roomName)
                || authenticationStateMap.containsKey(peerJid);
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
     * Creates an URL to be used by users to authenticate with external
     * authentication system.
     * @param userJid the Jabber ID of the user to be authenticated through
     *                produced URL.
     * @param roomName the name of the conference room for which
     *                 authentication URL will be valid.
     * @return an URL to be used by users to authenticate with external
     *         authentication system.
     */
    public String createAuthenticationUrl(String userJid, String roomName)
    {
        String token = createAuthToken(userJid, roomName);
        return String.format(authUrlPattern, token);
    }

    private synchronized String createAuthToken(String userJid,
                                               String roomName)
    {
        // FIXME: improve token generation mechanism
        String tokenStr = String.valueOf(System.nanoTime());
        AuthenticationToken token
            = new AuthenticationToken(tokenStr, userJid, roomName);
        synchronized (syncRoot)
        {
            tokensMap.put(tokenStr, token);
        }
        return tokenStr;
    }

    /**
     * Registers to the list of <tt>AuthenticationListener</tt>s.
     * @param l the <tt>AuthenticationListener</tt> to be added to listeners
     *          list.
     */
    public void addAuthenticationListener(AuthenticationListener l)
    {
        if (!authenticationListeners.contains(l))
        {
            authenticationListeners.add(l);
        }
    }

    /**
     * Unregisters from the list of <tt>AuthenticationListener</tt>s.
     * @param l the <tt>AuthenticationListener</tt> that will be removed from
     *          authentication listeners list.
     */
    public void removeAuthenticationListener(AuthenticationListener l)
    {
        authenticationListeners.remove(l);
    }

    /**
     * Method should be called to finish authentication process.
     * @param tokenStr a string which authentication token that identifies
     *                 user's authentication request. Based on the token we
     *                 know user's JID and the name of conference room for
     *                 which authentication will be valid.
     * @param authIdentity the identity obtained from external authentication
     *                     system that will be bound to the user's JID.
     * @return <tt>true</tt> if user has been authenticated successfully or
     *         <tt>false</tt> if given token is invalid.
     */
    public boolean authenticateUser(String tokenStr, String authIdentity)
    {
        synchronized (syncRoot)
        {
            AuthenticationToken token = tokensMap.get(tokenStr);
            if (token == null)
            {
                logger.error("Invalid tokenStr: " + tokenStr);
                return false;
            }

            // Remove token
            tokensMap.remove(tokenStr);

            String userJid = token.getUserJid();

            // Create authentication state for the token and user identity
            AuthenticationState authenticationState
                = new AuthenticationState(
                        userJid, token.getRoomName(), authIdentity);
            authenticationStateMap.put(userJid, authenticationState);

            notifyJidAuthenticated(authenticationState);
        }
        return true;
    }

    private void notifyJidAuthenticated(AuthenticationState authState)
    {
        logger.info("Jid " + authState.getUserJid()
                + " authenticated as: " + authState.getAuthenticatedIdentity());

        for (AuthenticationListener l : authenticationListeners)
        {
            l.jidAuthenticated(
                    authState.getUserJid(),
                    authState.getAuthenticatedIdentity());
        }
    }

    void expireToken(AuthenticationToken token)
    {
        synchronized (syncRoot)
        {
            if (tokensMap.remove(token.getToken()) != null)
            {
                logger.info("Expiring token: " + token.getToken());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onFocusDestroyed(String roomName)
    {
        synchronized (syncRoot)
        {
            // Expire tokens for "roomName"
            List<AuthenticationToken> tokens
                = new ArrayList<AuthenticationToken>(tokensMap.values());
            for (AuthenticationToken authToken : tokens)
            {
                if (authToken.getRoomName().equals(roomName))
                {
                    expireToken(authToken);
                }
            }
            // Expire authentications for "roomName"
            List<AuthenticationState> authentications
                = new ArrayList<AuthenticationState>(
                        authenticationStateMap.values());
            for (AuthenticationState authState : authentications)
            {
                if (authState.getRoomName().equals(roomName))
                {
                    removeAuthentication(authState);
                }
            }
        }
    }

    private void removeAuthentication(AuthenticationState authState)
    {
        synchronized (syncRoot)
        {
            if (authenticationStateMap.remove(authState.getUserJid()) != null)
            {
                logger.info("Authentication removed: " + authState);
            }
        }
    }

    /**
     * Returns <tt>true</tt> if user is authenticated in given conference room.
     * @param jabberID the Jabber ID of the user to be verified.
     * @param roomName conference room name which is the context of
     *                 authentication.
     */
    public boolean isUserAuthenticated(String jabberID, String roomName)
    {
        if (StringUtils.isNullOrEmpty(jabberID))
        {
            logger.warn("JID is null");
        }

        AuthenticationState authState = authenticationStateMap.get(jabberID);

        return authState != null && roomName.equals(authState.getRoomName());
    }

    /**
     * Task expires tokens and authentications.
     */
    private class ExpireTask extends TimerTask
    {
        @Override
        public void run()
        {
            if (focusManager == null)
            {
                // Shutting down..
                return;
            }
            FocusManager focusManagerConst = focusManager;
            // Expire tokens
            ArrayList<AuthenticationToken> tokens;
            synchronized (syncRoot)
            {
                tokens = new ArrayList<AuthenticationToken>(tokensMap.values());
            }
            for (AuthenticationToken token : tokens)
            {
                if (System.currentTimeMillis() - token.getCreationTimestamp()
                        > TOKEN_LIFETIME)
                {
                    expireToken(token);
                }
            }
            // Expire pre-authentications(authentication for which the room
            // has not been created yet)
            ArrayList<AuthenticationState> authentications;
            synchronized (syncRoot)
            {
                authentications
                    = new ArrayList<AuthenticationState>(
                            authenticationStateMap.values());
            }
            for (AuthenticationState authState : authentications)
            {
                if (focusManagerConst
                        .getConference(authState.getRoomName()) != null)
                {
                    continue;
                }
                if (System.currentTimeMillis() - authState.getAuthTimestamp()
                        > PRE_AUTHENTICATION_LIFETIME)
                {
                    removeAuthentication(authState);
                }
            }
        }
    }
}
