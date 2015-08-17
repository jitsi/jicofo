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

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.impl.neomedia.transform.csrc.*;
import org.jitsi.impl.neomedia.transform.srtp.*;
import org.jitsi.impl.osgi.framework.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.osgi.framework.*;

import java.io.*;
import java.util.*;

/**
 * Represents the entry point of the OSGi environment of the Jitsi Meet
 * conference focus application.
 *
 * @author Pawel Domas
 */
public class OSGi
{
    /**
     * The default filename of the bundles launch sequence file. This class
     * expects to find that file in SC_HOME_DIR_LOCATION/SC_HOME_DIR_NAME.
     */
    private static final String BUNDLES_FILE = "bundles.txt";

    /**
     * Indicates whether 'mock' protocol providers should be used instead of
     * original Jitsi protocol providers. For the purpose of unit testing.
     */
    private static boolean useMockProtocols = false;

    /**
     * The <tt>OSGiLauncher</tt> instance which
     * represents the launched OSGi instance.
     */
    private static OSGiLauncher launcher;

    /**
     * <tt>BundleActivator</tt> bundle activator launched on startup/shutdown of
     * the OSGi system.
     */
    private static BundleActivator activator;

    /**
     * The locations of the OSGi bundles (or rather of the class files of their
     * <tt>BundleActivator</tt> implementations) comprising Jitsi Meet Focus.
     * An element of the <tt>BUNDLES</tt> array is an array of <tt>String</tt>s
     * and represents an OSGi start level.
     */
    private static String[][] getBundles()
    {

        String[][] bundlesFromFile = loadBundlesFromFile(BUNDLES_FILE);
        if (bundlesFromFile != null)
        {
            return bundlesFromFile;
        }

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
                "net/java/sip/communicator/impl/libjitsi/LibJitsiActivator"
            },
            {
                "net/java/sip/communicator/util/UtilActivator",
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
                //"net/java/sip/communicator/impl/version/VersionActivator"
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
                "org/jitsi/videobridge/eventadmin/Activator"
            },
            {
                "org/jitsi/videobridge/influxdb/Activator"
            },
            useMockProtocols
                ? new String[] { "mock/MockMainMethodActivator" }
                : new String[] { }
        };

        return bundles;
    }

    /**
     * Loads list of OSGi bundles to run from specified file.
     *
     * @param filename the name of the file that contains a list of OSGi
     * {@code BundleActivator} classes. Full class names should be placed on
     * separate lines.
     * @return the array of OSGi {@code BundleActivator} class names to be
     * started in order. Single class name per {@code String} array.
     */
    private static String[][] loadBundlesFromFile(String filename)
    {
        File file = ConfigUtils.getAbsoluteFile(filename, null);

        if (file == null || !file.exists())
        {
            return null;
        }

        List<String[]> lines = new ArrayList<String[]>();

        Scanner input = null;
        try
        {
            input = new Scanner(file);

            while(input.hasNextLine())
            {
                String line = input.nextLine();
                if (!StringUtils.isNullOrEmpty(line))
                {
                    lines.add(new String[] { line.trim() });
                }
            }
        }
        catch (FileNotFoundException e)
        {
            return null;
        }
        finally
        {
            if (input != null)
            {
                input.close();
            }
        }

        String[][] bundles = lines.isEmpty()
                ? null : lines.toArray(new String[lines.size()][]);

        return bundles;
    }

    static
    {
        /*
         * Before we start OSGi and, more specifically, the very Jitsi
         * Videobridge application, set the default values of the System
         * properties which affect the (optional) behavior of the application.
         */
        setSystemPropertyDefaults();
    }

    /**
     * Sets default values on <tt>System</tt> properties which affect the
     * (optional) behavior of the Jitsi Videobridge application and the
     * libraries that it utilizes. Because <tt>ConfigurationServiceImpl</tt>
     * will override <tt>System</tt> property values, the set default
     * <tt>System</tt> property values will not prevent the user from overriding
     * them. 
     */
    private static void setSystemPropertyDefaults()
    {
        /*
         * XXX A default System property value specified bellow will eventually
         * be set only if the System property in question does not have a value
         * set yet.
         */

        Map<String,String> defaults = new HashMap<String,String>();
        String true_ = Boolean.toString(true);
        String false_ = Boolean.toString(false);

        /*
         * The design at the time of this writing considers the configuration
         * file read-only (in a read-only directory) and provides only manual
         * editing for it.
         */
        defaults.put(
                ConfigurationService.PNAME_CONFIGURATION_FILE_IS_READ_ONLY,
                true_);

        defaults.put(
                DeviceConfiguration.PROP_AUDIO_SYSTEM,
                AudioSystem.LOCATOR_PROTOCOL_AUDIOSILENCE);

        defaults.put(
                MediaServiceImpl.DISABLE_VIDEO_SUPPORT_PNAME,
                true_);

        // It makes no sense for Jitsi Videobridge to pace its RTP output.
        defaults.put(
                DeviceConfiguration.PROP_VIDEO_RTP_PACING_THRESHOLD,
                Integer.toString(Integer.MAX_VALUE));

        /*
         * XXX Explicitly support JitMeet by default because is is the primary
         * use case of Jitsi Videobridge right now.
         */
        defaults.put(
                SsrcTransformEngine
                    .DROP_MUTED_AUDIO_SOURCE_IN_REVERSE_TRANSFORM,
                true_);
        defaults.put(SRTPCryptoContext.CHECK_REPLAY_PNAME, false_);

        // Use the jicofo extended handler
        defaults.put(
                org.jitsi.videobridge.influxdb.Activator
                        .LOGGING_HANDLER_CLASS_PNAME,
                org.jitsi.jicofo.log.LoggingHandler.class.getCanonicalName());

        for (Map.Entry<String,String> e : defaults.entrySet())
        {
            String key = e.getKey();

            if (System.getProperty(key) == null)
                System.setProperty(key, e.getValue());
        }
    }

    /**
     * Indicates whether mock protocol providers should be used instead of
     * original Jitsi protocol providers.
     */
    public static boolean isUseMockProtocols()
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
    public static void setUseMockProtocols(boolean useMockProtocols)
    {
        OSGi.useMockProtocols = useMockProtocols;
    }

    /**
     * Starts the OSGi infrastructure.
     *
     * @param activator the <tt>BundleActivator</tt> that will be launched after
     *                  OSGi starts.
     */
    public static synchronized void start(BundleActivator activator)
    {
        if (OSGi.activator != null)
            throw new IllegalStateException("activator");

        OSGi.activator = activator;

        if (launcher == null)
        {
            launcher = new OSGiLauncher(getBundles());
        }

        launcher.start(activator);
    }

    /**
     * Stops the Jitsi Meet Focus bundles and the OSGi implementation.
     *
     * The <tt>BundleActivator</tt> that has been passed to
     * {@link #start(BundleActivator)} will be launched after shutdown.
     */
    public static synchronized void stop()
    {
        if (launcher != null && activator != null)
        {
            launcher.stop(activator);

            activator = null;
        }
    }
}
