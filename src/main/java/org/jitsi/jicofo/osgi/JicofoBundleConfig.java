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
package org.jitsi.jicofo.osgi;

import org.jitsi.meet.*;

import java.util.*;

/**
 * Jicofo OSGi bundles description.
 *
 * @author Pawel Domas
 */
public class JicofoBundleConfig
    extends OSGiBundleConfig
{
    /**
     * Indicates whether 'mock' protocol providers should be used instead of
     * original Jitsi protocol providers. For the purpose of unit testing.
     */
    private boolean useMockProtocols = false;

    /**
     * Indicates whether mock protocol providers should be used instead of
     * original Jitsi protocol providers.
     */
    public boolean isUseMockProtocols()
    {
        return useMockProtocols;
    }

    /**
     * Make OSGi use mock protocol providers instead of original Jitsi protocols
     * implementation.
     *
     * @param useMockProtocols <tt>true</tt> if Jitsi protocol providers should
     *                         be replaced with mock version.
     */
    public void setUseMockProtocols(boolean useMockProtocols)
    {
        this.useMockProtocols = useMockProtocols;
    }

    /**
     * The locations of the OSGi bundles (or rather of the class files of their
     * <tt>BundleActivator</tt> implementations) comprising Jitsi Meet Focus.
     * An element of the <tt>BUNDLES</tt> array is an array of <tt>String</tt>s
     * and represents an OSGi start level.
     */
    protected String[][] getBundlesImpl()
    {

        String[] protocols =
            {
                "org/jitsi/impl/protocol/xmpp/XmppProtocolActivator"
            };

        String[] mockProtocols =
            {
                "mock/MockActivator"
            };

        String[][] bundles = {
            {
                "org/jitsi/service/libjitsi/LibJitsiActivator"
            },
            {
                "net/java/sip/communicator/util/UtilActivator",
                "org/jitsi/eventadmin/Activator",
                //"net/java/sip/communicator/impl/fileaccess/FileAccessActivator"
            },
            {
                "net/java/sip/communicator/impl/configuration/ConfigurationActivator"
            },
            {
                //"net/java/sip/communicator/impl/resources/ResourceManagementActivator"
            },
            {
                //"net/java/sip/communicator/impl/dns/DnsUtilActivator"
            },
            {
                "net/java/sip/communicator/impl/credentialsstorage/CredentialsStorageActivator"
            },
            {
                "net/java/sip/communicator/impl/netaddr/NetaddrActivator"
            },
            {
                //"net/java/sip/communicator/impl/packetlogging/PacketLoggingActivator"
            },
            {
                //"net/java/sip/communicator/service/gui/internal/GuiServiceActivator"
            },
            {
                "net/java/sip/communicator/service/protocol/media/ProtocolMediaActivator"
            },
            {
                //"net/java/sip/communicator/service/notification/NotificationServiceActivator",
                //"net/java/sip/communicator/impl/globaldisplaydetails/GlobalDisplayDetailsActivator"
            },
            useMockProtocols
                ? new String[]
                {
                    "mock/media/MockMediaActivator"
                }
                : new String[]
                {
                    //"net/java/sip/communicator/impl/neomedia/NeomediaActivator"
                },
            {
                //"net/java/sip/communicator/impl/certificate/CertificateVerificationActivator"
            },
            {
                "org/jitsi/jicofo/version/VersionActivator"
            },
            {
                "net/java/sip/communicator/service/protocol/ProtocolProviderActivator"
            },
            // Shall we use mock protocol providers?
            useMockProtocols ? mockProtocols : protocols,
            {
                "org/jitsi/jicofo/FocusBundleActivator",
                "org/jitsi/impl/reservation/rest/Activator",
                "org/jitsi/jicofo/auth/AuthBundleActivator"
            },
            {
                "org/jitsi/jicofo/bridge/JvbDoctor",
                "org/jitsi/jicofo/VersionBroadcaster"
            }
        };

        return bundles;
    }

    @Override
    public Map<String, String> getSystemPropertyDefaults()
    {
        // "super" is setting defaults common to all components
        Map<String, String> defaults = super.getSystemPropertyDefaults();

        String true_ = Boolean.toString(true);

        // make sure we use the properties files for configuration
        defaults.put(
            "net.java.sip.communicator.impl.configuration.USE_PROPFILE_CONFIG",
            true_);

        return defaults;
    }
}
