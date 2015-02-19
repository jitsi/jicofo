/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.auth;

import net.java.sip.communicator.util.Logger;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.*;
import org.jitsi.util.*;

import org.jivesoftware.smack.packet.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Common class for {@link AuthenticationAuthority} implementations.
 *
 * @author Pawel Domas
 */
public abstract class AbstractAuthAuthority
    implements AuthenticationAuthority
{
    /**
     * The logger.
     */
    private final static Logger logger
        = Logger.getLogger(AbstractAuthAuthority.class);

    /**
     * Name of configuration property that controls authentication session
     * lifetime.
     */
    private final static String AUTHENTICATION_LIFETIME_PNAME
            = "org.jitsi.jicofo.auth.AUTH_LIFETIME";

    /**
     * Default lifetime of authentication session(24H).
     */
    private final static long DEFAULT_AUTHENTICATION_LIFETIME
        = 24 * 60 * 60 * 1000;

    /**
     * Interval at which we check for authentication sessions expiration.
     */
    private final static long EXPIRE_POLLING_INTERVAL = 10000L;

    /**
     * Authentication session lifetime in milliseconds.
     */
    private final long authenticationLifetime;

    /**
     * The timer used to check for the expiration of authentication sessions.
     */
    private Timer expireTimer;

    /**
     * Synchronization root.
     */
    protected final Object syncRoot = new Object();

    /**
     * The map of user JIDs to {@link AuthenticationSession}.
     */
    private Map<String, AuthenticationSession> authenticationSessions
            = new HashMap<String, AuthenticationSession>();

    /**
     * The list of registered {@link AuthenticationListener}s.
     */
    private List<AuthenticationListener> authenticationListeners
            = new CopyOnWriteArrayList<AuthenticationListener>();

    /**
     * Creates new instance of <tt>AbstractAuthAuthority</tt>.
     */
    public AbstractAuthAuthority()
    {
        authenticationLifetime = FocusBundleActivator.getConfigService()
                .getLong(AUTHENTICATION_LIFETIME_PNAME,
                        DEFAULT_AUTHENTICATION_LIFETIME);

        logger.info("Authentication lifetime: " + authenticationLifetime);
    }

    /**
     * Creates new <tt>AuthenticationSession</tt> for given parameters.
     *
     * @param machineUID unique machine identifier for new session.
     * @param authIdentity authenticated user's identity name that will be
     *                     used in new session.
     *
     * @return new <tt>AuthenticationSession</tt> for given parameters.
     */
    protected AuthenticationSession createNewSession(
            String machineUID, String authIdentity)
    {
        synchronized (syncRoot)
        {
            AuthenticationSession session
                = new AuthenticationSession(
                        machineUID,
                        createNonExistingUUID().toString(),
                        authIdentity);

            authenticationSessions.put(session.getSessionId(), session);

            logger.info(
                "Authentication session created for "
                        + authIdentity + " SID: " + session.getSessionId());

            return session;
        }
    }

    /**
     * Returns new <tt>UUID</tt> that does not collide with any of the existing
     * ones.
     */
    private UUID createNonExistingUUID()
    {
        UUID uuid = UUID.randomUUID();
        while (authenticationSessions.containsKey(uuid.toString()))
        {
            uuid = UUID.randomUUID();
        }
        return uuid;
    }

    /**
     * Finds <tt>AuthenticationSession</tt> for given MUID and
     * user's authenticated identity name.
     *
     * @param machineUID unique machine identifier used to distinguish
     *                   between sessions for the same user on different
     *                   machines.
     * @param authIdentity authenticated user's identity name(usually login
     *                     or email)
     *
     * @return <tt>AuthenticationSession</tt> for given MUID and
     *         user's authenticated identity name
     */
    protected AuthenticationSession findSessionForIdentity(
            String machineUID, String authIdentity)
    {
        if (StringUtils.isNullOrEmpty(authIdentity)
                || StringUtils.isNullOrEmpty(machineUID))
        {
            return null;
        }
        synchronized (syncRoot)
        {
            for (AuthenticationSession session
                    : authenticationSessions.values())
            {
                if (session.getUserIdentity().equals(authIdentity)
                        && session.getMachineUID().equals(machineUID))
                {
                    return session;
                }
            }
            return null;
        }
    }

    /**
     * Finds <tt>AuthenticationSession</tt> for given Jabber ID.
     *
     * @param jabberId Jabber ID for which authentication session will be
     *                 searched.
     *
     * @return <tt>AuthenticationSession</tt> for given Jabber ID or
     * <tt>null</tt> if not found.
     */
    protected AuthenticationSession findSessionForJabberId(String jabberId)
    {
        if (StringUtils.isNullOrEmpty(jabberId))
        {
            return null;
        }
        synchronized (syncRoot)
        {
            for(AuthenticationSession session : authenticationSessions.values())
            {
                if (jabberId.equals(session.getUserJabberId()))
                {
                    return session;
                }
            }
        }
        return null;
    }

    /**
     * Returns <tt>AuthenticationSession</tt> for given session identifier or
     * <tt>null</tt> if not found.
     *
     * @param sessionId unique authentication session identifier string that
     *                  will be used in the search.
     */
    protected AuthenticationSession getSession(String sessionId)
    {
        return authenticationSessions.get(sessionId);
    }

    /**
     * Destroys <tt>AuthenticationSession</tt> for given ID.
     *
     * @param sessionId unique authentication session identifier.
     */
    public void destroySession(String sessionId)
    {
        synchronized (syncRoot)
        {
            AuthenticationSession session
                    = authenticationSessions.get(sessionId);

            if (session != null)
            {
                if (authenticationSessions.remove(sessionId) != null)
                {
                    logger.info("Authentication removed: " + session);
                }
            }
        }
    }

    /**
     * Registers to the list of <tt>AuthenticationListener</tt>s.
     * @param l the <tt>AuthenticationListener</tt> to be added to listeners
     *          list.
     */
    @Override
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
    @Override
    public void removeAuthenticationListener(AuthenticationListener l)
    {
        authenticationListeners.remove(l);
    }

    protected void notifyUserAuthenticated(String userJid, String identity)
    {
        logger.info("Jid " + userJid + " authenticated as: " + identity);

        for (AuthenticationListener l : authenticationListeners)
        {
            l.jidAuthenticated(userJid, identity);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IQ processAuthentication(
            ConferenceIq query, ConferenceIq response, boolean roomExists)
    {
        synchronized (syncRoot)
        {
            return processAuthLocked(query, response, roomExists);
        }
    }

    /**
     * Implements {@link AuthenticationAuthority#
     * processAuthentication(ConferenceIq, ConferenceIq, boolean)}. Runs in
     * synchronized section of authentication sessions storage lock.
     */
    protected abstract IQ processAuthLocked(
            ConferenceIq query, ConferenceIq response, boolean roomExists);

    /**
     * Utility method to by used by implementing classes in order to
     * verify authentication session's identifier during authentication
     * request handling process({@link #processAuthentication(ConferenceIq,
     * ConferenceIq, boolean)}).
     *
     * @param query <tt>ConferenceIq</tt> that contains(or not) session id
     *              for the verification.
     *
     * @return <tt>null</tt> if no problems where discovered during
     * verification process or XMPP error that should be returned as response
     * to given <tt>query</tt>.
     */
    protected IQ verifySession(ConferenceIq query)
    {
        String sessionId = query.getSessionId();
        AuthenticationSession session = getSession(sessionId);

        if (!StringUtils.isNullOrEmpty(sessionId))
        {
            // Session not found: session-invalid
            if (session == null)
            {
                // session-invalid
                return ErrorFactory.createSessionInvalidResponse(query);
            }
            // Check if session is used with the same machine UID
            String sessionMUID = session.getMachineUID();
            String queryMUID = query.getMachineUID();
            if (!sessionMUID.equals(queryMUID))
            {
                // not-acceptable
                return ErrorFactory.createNotAcceptableError(
                        query, "machine UID mismatch or empty");
            }
        }
        return null;
    }

    /**
     * This method should be called during authentication request processing
     * in order to authenticate JID found in the 'from' field of the request
     * with given <tt>AuthenticationSession</tt>(based on supplied session-id).
     * When called it means that the request sent by <tt>peerJid</tt> is
     * valid and has been authenticated for given <tt>session</tt>.
     * Authentication process is specific to {@link AuthenticationAuthority}
     * implementation.
     *
     * @param session <tt>AuthenticationSession</tt> which corresponds to the
     *                session id supplied in the request.
     * @param peerJid user's JID that will be associated with
     *                <tt>AuthenticationSession</tt>.
     * @param response the response instance that will be sent back to the user.
     *                 It will be updated with required information to describe
     *                 the session.
     */
    protected void authenticateJidWithSession(
        AuthenticationSession session, String peerJid, ConferenceIq response)
    {
        session.setUserJabberId(peerJid);

        logger.info(
            "Authenticated jid: " + peerJid + " with session: " + session);

        notifyUserAuthenticated(peerJid, session.getUserIdentity());

        // Re-new session activity timestamp
        session.touch();

        // Update response
        response.setIdentity(session.getUserIdentity());
        response.setSessionId(session.getSessionId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IQ processLogoutIq(LogoutIq iq)
    {
        //String peerJid = iq.getFrom();
        String sessionId = iq.getSessionId();

        if (getSession(sessionId) == null)
        {
            return ErrorFactory.createSessionInvalidResponse(iq);
        }

        LogoutIq result = new LogoutIq();

        result.setType(org.jivesoftware.smack.packet.IQ.Type.RESULT);
        result.setPacketID(iq.getPacketID());
        result.setFrom(iq.getTo());
        result.setTo(iq.getFrom());

        // Logout URL for external system
        String logoutUrl = createLogoutUrl(sessionId);
        result.setLogoutUrl(logoutUrl);

        // Destroy local application session
        destroySession(sessionId);

        return result;
    }

    /**
     * Returns URL which should be visited by the user in order to complete
     * the logout process. If this step is not required then <tt>null</tt>
     * is returned.
     *
     * @param sessionId authentication session identifier string.
     */
    protected abstract String createLogoutUrl(String sessionId);

    /**
     * Start this authentication authority instance.
     */
    public void start()
    {
        expireTimer = new Timer("AuthenticationExpireTimer", true);
        expireTimer.scheduleAtFixedRate(
            new ExpireTask(), EXPIRE_POLLING_INTERVAL, EXPIRE_POLLING_INTERVAL);
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
    }

    /**
     * Task expires tokens and authentications.
     */
    private class ExpireTask extends TimerTask
    {
        @Override
        public void run()
        {
            synchronized (syncRoot)
            {
                Iterator<AuthenticationSession> sessionsIter
                    = authenticationSessions.values().iterator();
                while (sessionsIter.hasNext())
                {
                    AuthenticationSession session = sessionsIter.next();
                    if (System.currentTimeMillis()
                            - session.getActivityTimestamp()
                                    > authenticationLifetime)
                    {
                        logger.info("Expiring session:" + session);
                        sessionsIter.remove();
                    }
                }
            }
        }
    }
}
