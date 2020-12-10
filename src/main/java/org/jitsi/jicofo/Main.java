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

import kotlin.jvm.functions.*;
import org.jetbrains.annotations.*;
import org.jitsi.cmd.*;
import org.jitsi.config.*;
import org.jitsi.jicofo.osgi.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.meet.*;
import org.jitsi.metaconfig.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging.*;
import org.osgi.framework.*;
import org.xeustechnologies.jcl.*;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * Provides the <tt>main</tt> entry point of Jitsi Meet conference focus.
 *
 * @author Pawel Domas
 */
public class Main
{
    private static final Logger logger = Logger.getLogger(Main.class);

    /**
     * Program entry point.
     *
     * @param args command-line arguments.
     */
    public static void main(String[] args)
            throws ParseException
    {
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                logger.error("An uncaught exception occurred in thread=" + t, e));

        setupMetaconfigLogger();
        setSystemProperties(args);
        JitsiConfig.Companion.reloadNewConfig();

        // Make sure that passwords are not printed by ConfigurationService
        // on startup by setting password regExpr and cmd line args list
        ConfigUtils.PASSWORD_SYS_PROPS = "pass";
        ConfigUtils.PASSWORD_CMD_LINE_ARGS = "secret,user_password";


        final Object exitSyncRoot = new Object();
        // Register shutdown hook to perform cleanup before exit
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            synchronized (exitSyncRoot)
            {
                exitSyncRoot.notifyAll();
            }
        }));
        logger.info("Starting OSGi services.");
        BundleActivator activator = startOsgi(exitSyncRoot);

        logger.debug("Waiting for OSGi services to start");
        try
        {
            WaitableBundleActivator.waitUntilStarted();
        }
        catch (Exception e)
        {
            logger.error("Failed to start all OSGi bundles, exiting.");
            OSGi.stop(activator);
            return;
        }
        logger.info("OSGi services started.");

        JicofoServices.jicofoServicesSingleton = new JicofoServices(WaitableBundleActivator.getBundleContext());

        try
        {
            synchronized (exitSyncRoot)
            {
                exitSyncRoot.wait();
            }
        }
        catch (Exception e)
        {
            logger.error(e, e);
        }

        logger.info("Stopping services.");
        JicofoServices.jicofoServicesSingleton.stop();
        JicofoServices.jicofoServicesSingleton = null;
        OSGi.stop(activator);
    }

    private static BundleActivator startOsgi(Object exitSyncRoot)
    {
        JicofoBundleConfig bundleConfig = new JicofoBundleConfig();
        OSGi.setBundleConfig(bundleConfig);
        bundleConfig.setSystemPropertyDefaults();

        ClassLoader classLoader = loadBundlesJars(bundleConfig);
        OSGi.setClassLoader(classLoader);

        /*
         * Start OSGi. It will invoke the application programming interfaces
         * (APIs) of Jitsi Videobridge. Each of them will keep the application
         * alive.
         */
        BundleActivator activator = new BundleActivator()
        {
            @Override
            public void start(BundleContext bundleContext)
            {
                bundleContext.registerService(
                        ShutdownService.class,
                        new ShutdownService()
                        {
                            private boolean shutdownStarted = false;

                            @Override
                            public void beginShutdown()
                            {
                                if (shutdownStarted)
                                    return;

                                shutdownStarted = true;

                                synchronized (exitSyncRoot)
                                {
                                    exitSyncRoot.notifyAll();
                                }
                            }
                        }, null
                );
            }

            @Override
            public void stop(BundleContext bundleContext)
                    throws Exception
            {
                // We're doing nothing
            }
        };


        // Start OSGi
        logger.warn("Starting Osgi");
        OSGi.start(activator);

        return activator;
    }
    /**
     * Read the command line arguments and env variables, and set the corresponding system properties used for
     * configuration of the XMPP component and client connections.
     */
    private static void setSystemProperties(String[] args)
            throws ParseException
    {
        CmdLine cmdLine = new CmdLine();

        if (isBlank(System.getenv("JICOFO_SECRET")))
        {
            cmdLine.addRequiredArgument("--secret");
        }

        // We may end execution here if one of required arguments is missing
        cmdLine.parse(args);

        // XMPP host/domain
        String host;
        String componentDomain;
        // Try to get domain, can be null after this call(we'll fix that later)
        componentDomain = cmdLine.getOptionValue("domain");
        // Host name
        host = cmdLine.getOptionValue(
                "--host",
                componentDomain == null ? "localhost" : componentDomain);
        // Try to fix component domain
        if (isBlank(componentDomain))
        {
            componentDomain = host;
        }
        if (componentDomain != null)
        {
            // For backward compat, the "--domain" command line argument controls the domain for the XMPP component
            // as well as XMPP client connection.
            System.setProperty(XmppClientConnectionConfig.legacyXmppDomainPropertyName, componentDomain);
        }
        if (host != null)
        {
            // For backward compat, the "--host" command line argument controls the hostname for the XMPP component
            // as well as XMPP client connection.
            System.setProperty(XmppClientConnectionConfig.legacyHostnamePropertyName, host);
        }

        String componentSubDomain = cmdLine.getOptionValue("--subdomain", "focus");
        int port = cmdLine.getIntOptionValue("--port", 5347);
        String secret = cmdLine.getOptionValue("--secret");
        if (isBlank(secret))
        {
            secret = System.getenv("JICOFO_SECRET");
        }

        XmppComponentConfig.config = new XmppComponentConfig(
                host == null ? "" : host,
                componentDomain == null ? "" : componentDomain,
                componentSubDomain == null ? "" : componentSubDomain,
                port,
                secret == null ? "" : secret
        );

        // XMPP client connection
        String focusDomain = cmdLine.getOptionValue("--user_domain");
        String focusUserName = cmdLine.getOptionValue("--user_name");
        String focusPassword = cmdLine.getOptionValue("--user_password");
        if (isBlank(focusPassword))
        {
            focusPassword = System.getenv("JICOFO_AUTH_PASSWORD");
        }

        if (focusDomain != null)
        {
            System.setProperty(XmppClientConnectionConfig.legacyDomainPropertyName, focusDomain);
        }
        if (focusUserName != null)
        {
            System.setProperty(XmppClientConnectionConfig.legacyUsernamePropertyName, focusUserName);
        }
        if (isNotBlank(focusPassword))
        {
            System.setProperty(XmppClientConnectionConfig.legacyPasswordPropertyName, focusPassword);
        }
    }

    private static void setupMetaconfigLogger()
    {
        org.jitsi.utils.logging2.Logger configLogger = new org.jitsi.utils.logging2.LoggerImpl("org.jitsi.config");
        MetaconfigSettings.Companion.setLogger(new MetaconfigLogger()
        {
            @Override
            public void warn(@NotNull Function0<String> function0)
            {
                configLogger.warn(function0::invoke);
            }

            @Override
            public void error(@NotNull Function0<String> function0)
            {
                configLogger.error(function0::invoke);
            }

            @Override
            public void debug(@NotNull Function0<String> function0)
            {
                configLogger.debug(function0::invoke);
            }
        });
    }

    /**
     * Creates class loader that able to load classes from jars of selected by
     * bundleConfig {@code OSGiBundleConfig#BUNDLES_JARS_PATH} parameter.
     * @param bundleConfig - instance with path to extended bundles jar.
     * @return OSGi class loader for bundles.
     */
    private static ClassLoader loadBundlesJars(OSGiBundleConfig bundleConfig)
    {
        String bundlesJarsPath = bundleConfig.getBundlesJarsPath();
        if (bundlesJarsPath == null)
        {
            return ClassLoader.getSystemClassLoader();
        }

        JarClassLoader jcl = new JarClassLoader();
        jcl.add(bundlesJarsPath + "/");
        return new OSGiClassLoader(jcl, ClassLoader.getSystemClassLoader());
    }
}
