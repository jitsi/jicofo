/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.auth;

/**
 * Class represents stores information about single user's authentication
 * session.
 *
 * @author Pawel Domas
 */
public class AuthenticationSession
{
    /**
     * Machine unique identifier used to associate session with particular
     * user's machine, so that single user can login from different locations at
     * the same time.
     */
    private final String machineUID;

    /**
     * Authentication session identifier.
     */
    private final String sessionId;

    /**
     * Authenticated user's identity name(usually login name).
     */
    private final String userIdentity;

    /**
     * Timestamp in millis used to track the moment of last activity on
     * session instance, so that it can eventually expire after exceeding
     * time limit of inactivity.
     */
    private long activityTimestamp = System.currentTimeMillis();

    /**
     * User's jabber ID recently used with this session. Used to bind
     * anonymous JID to {@link #userIdentity}.
     */
    private String userJabberId;

    /**
     * Creates new instance of <tt>AuthenticationSession</tt>.
     * @param machineUID unique machine identifier that will be used to
     *                   distinguish between session for the same user on
     *                   different machines.
     * @param sessionId unique session identifier.
     * @param userIdentity user's identity in the scope of authentication
     *                     system, usually login name or email address.
     */
    public AuthenticationSession(String machineUID, String sessionId, String
            userIdentity)
    {
        if (machineUID == null)
            throw new NullPointerException("machineUID");
        if (sessionId == null)
            throw new NullPointerException("sessionId");
        if (userIdentity == null)
            throw new NullPointerException("userIdentity");

        this.machineUID = machineUID;
        this.sessionId = sessionId;
        this.userIdentity = userIdentity;
    }

    /**
     * Returns session identifier.
     */
    public String getSessionId()
    {
        return sessionId;
    }

    /**
     * Returns user authenticated identity name in the scope of authentication
     * system(usually login name or email address).
     */
    public String getUserIdentity()
    {
        return userIdentity;
    }

    /**
     * Timestamp in millis of last activity on this session instance.
     */
    public long getActivityTimestamp()
    {
        return activityTimestamp;
    }

    /**
     * Re-news activity timestamp to {@link System#currentTimeMillis()}.
     */
    public void touch()
    {
        activityTimestamp = System.currentTimeMillis();
    }

    /**
     * Returns Jabber ID assigned to this session.
     */
    public String getUserJabberId()
    {
        return userJabberId;
    }

    /**
     * Assigns new Jabber ID to this session instance.
     * @param userJabberId the Jabber ID of the user that will be associated
     *                     with this session from now on.
     */
    public void setUserJabberId(String userJabberId)
    {
        this.userJabberId = userJabberId;
    }

    /**
     * Returns machine unique identifier which identifies owner machine of
     * this <tt>AuthenticationSession</tt>.
     * @return <tt>String</tt> machine UID.
     */
    public String getMachineUID()
    {
        return machineUID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder("AuthSession[");
        builder.append("ID=").append(userIdentity);
        builder.append(", JID=").append(userJabberId);
        builder.append(", SID=").append(sessionId);
        builder.append(", MUID=").append(machineUID);
        long lifetime = System.currentTimeMillis() - activityTimestamp;
        builder.append(", LIFE_TM_SEC=").append((lifetime/1000L));
        builder.append("]@").append(hashCode());
        return builder.toString();
    }
}
