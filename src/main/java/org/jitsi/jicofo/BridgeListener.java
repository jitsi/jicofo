/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.jicofo;

/**
 * The interface used to observe JVB status. The listener will be notified by
 * {@link BridgeSelector} instance whenever it removes or adds JVB instance.
 *
 * @author Pawel Domas
 */
public interface BridgeListener
{
    /**
     * Called when new working bridge is detected.
     *
     * @param src the event sender.
     * @param bridgeJid the JID of the bridge that has just been detected.
     */
    void onBridgeUp(BridgeSelector src, String bridgeJid);

    /**
     * Called when bridge bridge goes down(is considered broken due to an error
     * or failed health check).
     *
     * @param src the event sender
     * @param bridgeJid the JID of the bridge that has just died.
     */
    void onBridgeDown(BridgeSelector src, String bridgeJid);
}
