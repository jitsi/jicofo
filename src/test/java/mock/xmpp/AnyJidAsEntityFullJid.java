/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2017 Atlassian Pty Ltd
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

import org.jxmpp.jid.*;
import org.jxmpp.jid.parts.*;

/**
 * Mock implementation that accepts any JID and pretends its a
 * {@link EntityFullJid}. This is only used for the
 * {@link org.jivesoftware.smack.XMPPConnection} that requires a full JID only
 * by contract, but not actually for execution.
 */
public class AnyJidAsEntityFullJid implements EntityFullJid
{
    private final Jid ourJid;

    AnyJidAsEntityFullJid(Jid jid)
    {
        ourJid = jid;
    }

    @Override
    public Localpart getLocalpart()
    {
        return ourJid.getLocalpartOrNull();
    }

    @Override
    public EntityBareJid asEntityBareJid()
    {
        return ourJid.asEntityBareJidIfPossible();
    }

    @Override
    public String asEntityBareJidString()
    {
        Jid bare = ourJid.asEntityBareJidIfPossible();
        if (bare != null)
        {
            return bare.toString();
        }

        return null;
    }

    @Override
    public Resourcepart getResourcepart()
    {
        return ourJid.getResourceOrNull();
    }

    @Override
    public Domainpart getDomain()
    {
        return ourJid.getDomain();
    }

    @Override
    public String asUnescapedString()
    {
        return ourJid.asUnescapedString();
    }

    @Override
    public boolean isEntityJid()
    {
        return ourJid.isEntityJid();
    }

    @Override
    public boolean isEntityBareJid()
    {
        return ourJid.isEntityBareJid();
    }

    @Override
    public boolean isEntityFullJid()
    {
        return ourJid.isEntityFullJid();
    }

    @Override
    public boolean isDomainBareJid()
    {
        return ourJid.isDomainBareJid();
    }

    @Override
    public boolean isDomainFullJid()
    {
        return ourJid.isDomainFullJid();
    }

    @Override
    public boolean hasNoResource()
    {
        return ourJid.hasNoResource();
    }

    @Override
    public boolean hasResource()
    {
        return ourJid.hasResource();
    }

    @Override
    public boolean hasLocalpart()
    {
        return ourJid.hasLocalpart();
    }

    @Override
    public BareJid asBareJid()
    {
        return ourJid.asBareJid();
    }

    @Override
    public EntityBareJid asEntityBareJidIfPossible()
    {
        return ourJid.asEntityBareJidIfPossible();
    }

    @Override
    public EntityBareJid asEntityBareJidOrThrow()
    {
        return ourJid.asEntityBareJidOrThrow();
    }

    @Override
    public EntityFullJid asEntityFullJidIfPossible()
    {
        return ourJid.asEntityFullJidIfPossible();
    }

    @Override
    public EntityFullJid asEntityFullJidOrThrow()
    {
        return ourJid.asEntityFullJidOrThrow();
    }

    @Override
    public EntityJid asEntityJidIfPossible()
    {
        return ourJid.asEntityJidIfPossible();
    }

    @Override
    public EntityJid asEntityJidOrThrow()
    {
        return ourJid.asEntityJidOrThrow();
    }

    @Override
    public FullJid asFullJidIfPossible()
    {
        return ourJid.asFullJidIfPossible();
    }

    @Override
    public EntityFullJid asFullJidOrThrow()
    {
        return ourJid.asFullJidOrThrow();
    }

    @Override
    public DomainBareJid asDomainBareJid()
    {
        return ourJid.asDomainBareJid();
    }

    @Override
    public DomainFullJid asDomainFullJidIfPossible()
    {
        return ourJid.asDomainFullJidIfPossible();
    }

    @Override
    public DomainFullJid asDomainFullJidOrThrow()
    {
        return ourJid.asDomainFullJidOrThrow();
    }

    @Override
    public Resourcepart getResourceOrNull()
    {
        return ourJid.getResourceOrNull();
    }

    @Override
    public Resourcepart getResourceOrEmpty()
    {
        return ourJid.getResourceOrEmpty();
    }

    @Override
    public Resourcepart getResourceOrThrow()
    {
        return ourJid.getResourceOrThrow();
    }

    @Override
    public Localpart getLocalpartOrNull()
    {
        return ourJid.getLocalpartOrNull();
    }

    @Override
    public Localpart getLocalpartOrThrow()
    {
        return ourJid.getLocalpartOrThrow();
    }

    @Override
    public boolean isParentOf(Jid jid)
    {
        return ourJid.isParentOf(jid);
    }

    @Override
    public boolean isParentOf(EntityBareJid bareJid)
    {
        return ourJid.isParentOf(bareJid);
    }

    @Override
    public boolean isParentOf(EntityFullJid fullJid)
    {
        return ourJid.isParentOf(fullJid);
    }

    @Override
    public boolean isParentOf(DomainBareJid domainBareJid)
    {
        return ourJid.isParentOf(domainBareJid);
    }

    @Override
    public boolean isParentOf(DomainFullJid domainFullJid)
    {
        return ourJid.isParentOf(domainFullJid);
    }

    @Override
    public <T extends Jid> T downcast(Class<T> c)
    {
        return ourJid.downcast(c);
    }

    @Override
    public boolean equals(CharSequence charSequence)
    {
        return ourJid.equals(charSequence);
    }

    @Override
    public boolean equals(String string)
    {
        return ourJid.equals(string);
    }

    @Override
    public String intern()
    {
        return ourJid.intern();
    }

    @Override
    public int length()
    {
        return ourJid.length();
    }

    @Override
    public char charAt(int index)
    {
        return ourJid.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end)
    {
        return ourJid.subSequence(start, end);
    }

    @Override
    public int compareTo(Jid o)
    {
        return ourJid.compareTo(o);
    }

    @Override
    public boolean equals(Object obj)
    {
        return ourJid.equals(obj);
    }

    @Override
    public int hashCode()
    {
        return ourJid.hashCode();
    }

    @Override
    public String toString()
    {
        return ourJid.toString();
    }
}
