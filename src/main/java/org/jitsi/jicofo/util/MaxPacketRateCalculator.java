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
    private final int[] packetRatePps = { 30, 50, 100, 250 };

    /**
     * Assuming a 100 peeps conference with 20 senders, computes the (max) total
     * packet rate of a bridge that hosts numberOfLocalSenders local senders,
     * numberOfLocalReceivers local receivers and the remaining octo
     * participants.
     *
     * @param numberOfLocalSenders the local senders
     * @param numberOfLocalReceivers the local receivers
     * @return the (max) total bitrate of a bridge that hosts numberOfLocalSenders local senders, numberOfLocalReceivers
     * local receivers and the remaining octo participants
     */
    public int computeMaxUpload(int numberOfLocalSenders, int numberOfLocalReceivers)
    {
        // regardless of the participant distribution, in a 100 people call each
        // sender receivers 19 other senders.
        return (numberOfLocalSenders + numberOfLocalReceivers)
            * (numberOfSpeakers * packetRatePps[0] + (numberOfGlobalSenders - 2) * packetRatePps[1] + packetRatePps[3]);
    }
    public int computeMaxDownload(int numberOfLocalSenders)
    {
        // regardless of the participant distribution, in a 100 people call each
        // sender receivers 19 other senders.
        return numberOfLocalSenders*Arrays.stream(packetRatePps).sum();
    }

    public int computeMaxOctoSendBitrate(int numberOfLocalSenders)
    {
        // the octo packet rate depends on how many local senders there are.
        return numberOfConferenceBridges * computeMaxDownload(numberOfLocalSenders);
    }

    public int computeMaxOctoReceiveBitrate(int numberOfLocalSenders)
    {
        // the octo packet rate depends on how many local senders there are.
        return computeMaxDownload(numberOfGlobalSenders - numberOfLocalSenders);
    }

}
