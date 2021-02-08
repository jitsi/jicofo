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
package org.jitsi.jicofo.recording.jibri;

import kotlin.*;
import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.event.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.jibri.*;

import org.jitsi.jicofo.xmpp.*;
import org.json.simple.*;
import org.jxmpp.jid.*;

import java.util.concurrent.*;

/**
 * <tt>JibriDetector</tt> manages the pool of Jibri instances which exist in
 * the current session. Does that by joining "brewery" room where Jibris connect
 * to and publish their status in MUC presence.
 *
 * @author Pawel Domas
 */
public class JibriDetector
    extends BaseBrewery<JibriStatusPacketExt>
{
    /**
     * The logger
     */
    private static Logger createLogger(String type)
    {
        Logger logger = new LoggerImpl(JibriDetector.class.getName());
        logger.addContext("type", type);
        return logger;
    }



    /**
     * TODO: Refactor to use a common executor.
     */
    private final static ExecutorService eventEmitterExecutor
            = Executors.newSingleThreadExecutor(
                    new CustomizableThreadFactory("JibriDetector-AsyncEventEmitter", false));

    private final AsyncEventEmitter<EventHandler> eventEmitter = new AsyncEventEmitter<>(eventEmitterExecutor);

    /**
     * Creates new instance of <tt>JibriDetector</tt>
     * @param breweryJid the JID of the brewery MUC room.
     * @param isSip <tt>true</tt> if this instance will work with SIP gateway
     * Jibris or <tt>false</tt> for live streaming Jibris
     */
    public JibriDetector(
            XmppProvider xmppProvider,
            @NotNull EntityBareJid breweryJid,
            boolean isSip)
    {
        super(
            xmppProvider,
            breweryJid,
            JibriStatusPacketExt.ELEMENT_NAME,
            JibriStatusPacketExt.NAMESPACE,
            createLogger(isSip ? "sip_jibri" : "jibri"));
    }

    /**
     * Selects first idle Jibri which can be used to start recording.
     *
     * @return XMPP address of idle Jibri instance or <tt>null</tt> if there are
     * no Jibris available currently.
     */
    public Jid selectJibri()
    {
        return instances.stream()
            .filter(jibri -> jibri.status.isAvailable())
            .map(jibri -> jibri.jid)
            .findFirst()
            .orElse(null);
    }

    @Override
    protected void onInstanceStatusChanged(@NotNull Jid jid, @NotNull JibriStatusPacketExt presenceExt)
    {
        if (!presenceExt.isAvailable())
        {
            if (presenceExt.getBusyStatus() == null || presenceExt.getHealthStatus() == null)
            {
                notifyInstanceOffline(jid);
            }
        }
    }

    @Override
    protected void notifyInstanceOffline(Jid jid)
    {
        eventEmitter.fireEventAsync(handler ->
        {
            handler.instanceOffline(jid);
            return Unit.INSTANCE;
        });
    }

    void addHandler(EventHandler eventHandler)
    {
        eventEmitter.addHandler(eventHandler);
    }

    void removeHandler(EventHandler eventHandler)
    {
        eventEmitter.removeHandler(eventHandler);
    }

    @SuppressWarnings("unchecked")
    public JSONObject getStats()
    {
        JSONObject stats = new JSONObject();
        stats.put("count", getInstanceCount(null));
        stats.put(
            "available",
            getInstanceCount(brewInstance -> brewInstance.status != null && brewInstance.status.isAvailable()));
        return stats;
    }

    public interface EventHandler
    {
        default void instanceOffline(Jid jid) {}
    }
}
