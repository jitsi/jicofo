/*
 * Copyright @ 2018 - present 8x8, Inc.
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
package org.jitsi.jicofo.util;

import java.util.*;

/**
 * A helper class to calculate the maximum ingress/egress/octo packet rate that
 * goes through a bridge that serves a conference of a given size and topology.
 *
 * We use maxes because it allows us to compute worst case scenarios in our
 * load-balancing scheme.
 */
public class MaxPacketRateCalculator
{
    public MaxPacketRateCalculator(int numberOfConferenceBridges, int numberOfGlobalSenders, int numberOfSpeakers)
    {
        this.numberOfConferenceBridges = numberOfConferenceBridges;
        this.numberOfGlobalSenders = numberOfGlobalSenders;
        this.numberOfSpeakers = numberOfSpeakers;
    }

    private final int numberOfConferenceBridges;
    private final int numberOfGlobalSenders;
    private final int numberOfSpeakers;

    /**
     * 30pps for audio, 50pps for 180p, 100pps for 360p and 250pps for
     * 720p.
     */
    private final int[] maxPacketRatePps = {
        30, /* max audio pps of a participant sending audio */
        50, /* max 180p pps of a participant sending simulcast  */
        100, /* max 360p pps of a participant sending simulcast */
        250 /* max 720p pps of a participant sending simulcast */
    };

    /**
     * Computes the (max) total packet rate of a bridge that serves
     * numberOfLocalSenders local senders and numberOfLocalReceivers local
     * receivers and assuming the rest are octo participants.
     *
     * @param numberOfLocalSenders the local senders
     * @param numberOfLocalReceivers the local receivers
     * @return the (max) total packet rate of a bridge that serves
     * numberOfLocalSenders local senders and numberOfLocalReceivers local
     * receivers.
     */
    public int computeEgressPacketRatePps(int numberOfLocalSenders, int numberOfLocalReceivers)
    {
        // regardless of the participant distribution, in a 100 people/20
        // senders call each sender receivers 19 other senders, 1 in HD and 18
        // in LD.
        return (numberOfLocalSenders + numberOfLocalReceivers)
            * (numberOfSpeakers * maxPacketRatePps[0] + (numberOfGlobalSenders - 2) * maxPacketRatePps[1] + maxPacketRatePps[3]);
    }

    public int computeIngressPacketRatePps(int numberOfLocalSenders)
    {
        return numberOfLocalSenders*Arrays.stream(maxPacketRatePps).sum();
    }

    public int computeOctoEgressPacketRate(int numberOfLocalSenders)
    {
        // the octo packet rate depends on how many local senders there are.
        return numberOfConferenceBridges * computeIngressPacketRatePps(numberOfLocalSenders);
    }

    public int computeOctoIngressPacketRate(int numberOfLocalSenders)
    {
        // the octo packet rate depends on how many local senders there are.
        return computeIngressPacketRatePps(numberOfGlobalSenders - numberOfLocalSenders);
    }

}
