/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
package org.jitsi.jicofo.auth;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import org.jetbrains.annotations.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.packet.*;

import org.jxmpp.jid.*;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * Common class for {@link AuthenticationAuthority} implementations.
 *
 * @author Pawel Domas
 */
public abstract class AbstractAuthAuthority
    implements AuthenticationAuthority, ConferenceStore.Listener
{
    /**
     * The logger.
     */
    private final static Logger logger = new LoggerImpl(AbstractAuthAuthority.class.getName());

    /**
     * Interval at which we check for authentication sessions expiration.
     */
    private final static long EXPIRE_POLLING_INTERVAL = 10000L;

    /**
     * Authentication session lifetime in milliseconds.
     */
    private final long authenticationLifetime;

    /**
     * If set to <tt>true</tt> authentication session will be destroyed
     * immediately after end of the conference for which it was created.
     */
    private final boolean enableAutoLogin;

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
     *
     * Note that access to this field is almost always protected by a lock on
     * {@link #syncRoot}. However, {@link #getSession(String)} executes
     * {@link Map#get(Object)} on it, which wouldn't be safe with a
     * {@link HashMap} (as opposed to a {@link ConcurrentHashMap}.
     * I've chosen this solution, because I don't know whether the cleaner
     * solution of synchronizing on {@link #syncRoot} in
     * {@link #getSession(String)} is safe.
     */
    private final Map<String, AuthenticationSession> authenticationSessions
            = new ConcurrentHashMap<>();

    /**
     * The list of registered {@link AuthenticationListener}s.
     */
    private final List<AuthenticationListener> authenticationListeners
            = new CopyOnWriteArrayList<>();

    /**
     * Creates new instance of <tt>AbstractAuthAuthority</tt>.
     *
     * @param enableAutoLogin disables auto login feature. Authentication
     * sessions are destroyed immediately when the conference ends.
     * @param authenticationLifetime specifies how long authentication sessions
     * will be stored in Jicofo's memory. Interval in milliseconds.
     */
    public AbstractAuthAuthority(boolean enableAutoLogin, Duration authenticationLifetime)
    {
        this.enableAutoLogin = enableAutoLogin;
        this.authenticationLifetime = authenticationLifetime.toMillis();

        if (!enableAutoLogin)
        {
            logger.info("Auto login disabled");
        }

        logger.info("Authentication lifetime: " + authenticationLifetime);
    }

    /**
     * Finds an {@link AuthenticationSession} session.
     *
     * @param selector - Must return <tt>true</tt> when a match is found.
     * @return the first {@link AuthenticationSession} that matches given
     * predicate or <tt>null</tt>.
     */
    protected AuthenticationSession findSession(
        Predicate<AuthenticationSession> selector)
    {
        ArrayList<AuthenticationSession> sessions
            = new ArrayList<>(authenticationSessions.values());

        return sessions
            .stream()
            .filter(selector)
            .findFirst().orElse(null);
    }

    /**
     * Creates new <tt>AuthenticationSession</tt> for given parameters.
     *
     * @param machineUID unique machine identifier for new session.
     * @param authIdentity authenticated user's identity name that will be
     *                     used in new session.
     * @param roomName the name of the conference for which the session will be
     *                 created
     * @return new <tt>AuthenticationSession</tt> for given parameters.
     */
    protected AuthenticationSession createNewSession(
            String machineUID, String authIdentity, EntityBareJid roomName)
    {
        synchronized (syncRoot)
        {
            AuthenticationSession session
                = new AuthenticationSession(
                        machineUID,
                        createNonExistingUUID().toString(),
                        authIdentity,
                        roomName);

            authenticationSessions.put(session.getSessionId(), session);

            logger.info("Authentication session created for " + authIdentity + " SID: " + session.getSessionId());

            return session;
        }
    }

    /**
     * Returns new <tt>UUID</tt> that does not collide with any of the existing
     * ones.
     */
    private UUID createNonExistingUUID()
    {
        synchronized (syncRoot)
        {
            UUID uuid = UUID.randomUUID();
            while (authenticationSessions.containsKey(uuid.toString()))
            {
                uuid = UUID.randomUUID();
            }
            return uuid;
        }
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
        if (isBlank(authIdentity) || isBlank(machineUID))
        {
            return null;
        }

        return findSession(
            session -> session.getUserIdentity().equals(authIdentity)
                            && session.getMachineUID().equals(machineUID));
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
    protected AuthenticationSession findSessionForJabberId(Jid jabberId)
    {
        if (jabberId == null)
        {
            return null;
        }

        return findSession(
            session -> jabberId.equals(session.getUserJabberId()));
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
        return sessionId != null ? authenticationSessions.get(sessionId) : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getSessionForJid(Jid jabberId)
    {
        AuthenticationSession session = findSessionForJabberId(jabberId);
        return session != null ? session.getSessionId() : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getUserIdentity(Jid jabberId)
    {
        AuthenticationSession session = findSessionForJabberId(jabberId);

        return session != null ? session.getUserIdentity() : null;
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
            AuthenticationSession session = getSession(sessionId);

            if (session == null)
                return;

            if (authenticationSessions.remove(sessionId) != null)
            {
                logger.info("Authentication removed: " + session);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void conferenceEnded(EntityBareJid roomName)
    {
        if (enableAutoLogin)
        {
            return;
        }

        synchronized (syncRoot)
        {
            Iterator<AuthenticationSession> sessionIterator
                    = authenticationSessions.values().iterator();

            while (sessionIterator.hasNext())
            {
                AuthenticationSession session = sessionIterator.next();
                if (roomName.equals(session.getRoomName()))
                {
                    logger.info(
                        "Removing session for ended conference, S: " + session);
                    sessionIterator.remove();
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

    protected void notifyUserAuthenticated(Jid userJid,
                                           String identity,
                                           String sessionId)
    {
        logger.info("Jid " + userJid + " authenticated as: " + identity);

        for (AuthenticationListener l : authenticationListeners)
        {
            l.jidAuthenticated(userJid, identity, sessionId);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IQ processAuthentication(
            ConferenceIq query, ConferenceIq response)
    {
        synchronized (syncRoot)
        {
            return processAuthLocked(query, response);
        }
    }

    /**
     * Implements {@link AuthenticationAuthority#
     * processAuthentication(ConferenceIq, ConferenceIq, boolean)}. Runs in
     * synchronized section of authentication sessions storage lock.
     */
    protected abstract IQ processAuthLocked(
            ConferenceIq query, ConferenceIq response);

    /**
     * Utility method to by used by implementing classes in order to
     * verify authentication session's identifier during authentication
     * request handling process({@link #processAuthentication(ConferenceIq,
     * ConferenceIq)}).
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

        if (isNotBlank(sessionId))
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
            AuthenticationSession session,
            Jid peerJid,
            ConferenceIq response)
    {
        session.setUserJabberId(peerJid);

        logger.info("Authenticated jid: " + peerJid + " with session: " + session);

        notifyUserAuthenticated(peerJid, session.getUserIdentity(), session.getSessionId());

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
    @NotNull
    public IQ processLogoutIq(LogoutIq iq)
    {
        //String peerJid = iq.getFrom();
        String sessionId = iq.getSessionId();

        if (getSession(sessionId) == null)
        {
            return ErrorFactory.createSessionInvalidResponse(iq);
        }

        LogoutIq result = new LogoutIq();

        result.setType(org.jivesoftware.smack.packet.IQ.Type.result);
        result.setStanzaId(iq.getStanzaId());
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
        expireTimer.scheduleAtFixedRate(new ExpireTask(), EXPIRE_POLLING_INTERVAL, EXPIRE_POLLING_INTERVAL);
    }

    /**
     * Stops this authentication authority instance.
     */
    public void shutdown()
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
