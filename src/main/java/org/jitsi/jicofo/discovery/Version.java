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
package org.jitsi.jicofo.discovery;

/**
 * Wraps original Smack IQ to implement additional methods.
 *
 * @author Pawel Domas
 */
public class Version
    extends org.jivesoftware.smackx.packet.Version
{
    /**
     * Creates a string representation of this <tt>Version</tt> IQ which will
     * include the application name, version number and OS.
     * @return a string with the following format {name}({version},{os})
     */
    public String getNameVersionOsString()
    {
        return getName() + "(" + getVersion() + "," + getOs() + ")";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return "Version[" + getNameVersionOsString() + "@" + hashCode();
    }
}
