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

import org.jxmpp.jid.*;

import java.util.*;

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
    private Jid userJabberId;

    /**
     * Optional room name to which this session ID is bound.
     */
    private EntityBareJid roomName;

    /**
     * Creates new instance of <tt>AuthenticationSession</tt>.
     * @param machineUID unique machine identifier that will be used to
     *                   distinguish between session for the same user on
     *                   different machines.
     * @param sessionId unique session identifier.
     * @param userIdentity user's identity in the scope of authentication
 *                     system, usually login name or email address.
     * @param roomName full name of MUC room which hosts the conference for
     */
    public AuthenticationSession(String machineUID, String sessionId, String
            userIdentity, EntityBareJid roomName)
    {
        this.machineUID = Objects.requireNonNull(machineUID, "machineUID");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.userIdentity = Objects.requireNonNull(userIdentity, "userIdentity");

        this.roomName = roomName;
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
    public Jid getUserJabberId()
    {
        return userJabberId;
    }

    /**
     * Assigns new Jabber ID to this session instance.
     * @param userJabberId the Jabber ID of the user that will be associated
     *                     with this session from now on.
     */
    public void setUserJabberId(Jid userJabberId)
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
     * Returns ful name of MUC room for which this session has been created.
     */
    public EntityBareJid getRoomName()
    {
        return roomName;
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
        builder.append(", R=").append(roomName);
        builder.append("]@").append(hashCode());
        return builder.toString();
    }
}
