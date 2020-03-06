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
package org.jitsi.jicofo.bridge;

import java.util.*;

class MaxBitrateCalculator
{
    public MaxBitrateCalculator(int numberOfConferenceBridges, int numberOfGlobalSenders, int numberOfSpeakers)
    {
        this.numberOfConferenceBridges = numberOfConferenceBridges;
        this.numberOfGlobalSenders = numberOfGlobalSenders;
        this.numberOfSpeakers = numberOfSpeakers;
    }

    private final int numberOfConferenceBridges;
    private final int numberOfGlobalSenders;
    private final int numberOfSpeakers;

    /**
     * 30 kbps for audio, 150 kbps for 180p, 500kbps for 360p and 3200kbps for
     * 720p.
     */
    private int[] bitratesKbps = { 50, 200, 500, 3200 };

    /**
     * Assuming a 100 peeps conference with 20 senders, computes the (max) total
     * bitrate of a bridge that hosts numberOfLocalSenders local senders, numberOfLocalReceivers local receivers and the
     * remaining octo participants.
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
            * (numberOfSpeakers * bitratesKbps[0] + (numberOfGlobalSenders - 2) * bitratesKbps[1] + bitratesKbps[3]);
    }
    public int computeMaxDownload(int numberOfLocalSenders)
    {
        // regardless of the participant distribution, in a 100 people call each
        // sender receivers 19 other senders.
        return numberOfLocalSenders*Arrays.stream(bitratesKbps).sum();
    }

    public int computeMaxOctoSendBitrate(int numberOfLocalSenders)
    {
        // the octo bitrate depends on how many local senders there are.
        return numberOfConferenceBridges * computeMaxDownload(numberOfLocalSenders);
    }

    public int computeMaxOctoReceiveBitrate(int numberOfLocalSenders)
    {
        // the octo bitrate depends on how many local senders there are.
        return computeMaxDownload(numberOfGlobalSenders - numberOfLocalSenders);
    }

}
