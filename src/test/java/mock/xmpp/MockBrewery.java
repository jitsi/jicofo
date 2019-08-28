/*
 * Jicofo, the Jitsi Conference Focus.
 *
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
package mock.xmpp;

import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;

public class MockBrewery<T extends ExtensionElement>
    extends BaseBrewery<T>
{
    public MockBrewery(
        ProtocolProviderHandler protocolProvider,
        String breweryJid)
    {
        super(protocolProvider, breweryJid, null, null);
    }

    @Override
    protected void onInstanceStatusChanged(Jid jid, T status)
    {}

    @Override
    protected void notifyInstanceOffline(Jid jid)
    {}

    public void addNewBrewInstance(Jid jid, T el)
    {
        processInstanceStatusChanged(jid, el);
    }

    public void updateInstanceStats(Jid jid, T el)
    {
        processInstanceStatusChanged(jid, el);
    }

    public List<BrewInstance> getInstances()
    {
        return instances;
    }
}
