/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.reservation;

/**
 * FIXME: work in progress
 *
 * @author Pawel Domas
 */
public interface ReservationSystem
{
    public static final int RESULT_OK = 1;

    public static final int RESULT_NOT_ALLOWED = 2;

    public static final int RESULT_CONFLICT = 3;

    public static final int RESULT_INTERNAL_ERROR = 100;

    public int createConference(String owner, String name);

    public int deleteConference(String name);
}
