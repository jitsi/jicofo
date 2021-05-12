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
package org.jitsi.jicofo;

import kotlin.jvm.functions.*;
import org.jetbrains.annotations.*;
import org.jitsi.cmd.*;
import org.jitsi.config.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.metaconfig.*;
import org.jitsi.shutdown.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * Provides the <tt>main</tt> entry point of Jitsi Meet conference focus.
 *
 * @author Pawel Domas
 */
public class Main
{
    private static final Logger logger = new LoggerImpl(Main.class.getName());

    /**
     * Program entry point.
     *
     * @param args command-line arguments.
     */
    public static void main(String[] args)
            throws ParseException
    {
        logger.info("Starting Jicofo.");

        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                logger.error("An uncaught exception occurred in thread=" + t, e));

        setupMetaconfigLogger();
        setSystemProperties(args);
        JitsiConfig.Companion.reloadNewConfig();

        // Make sure that passwords are not printed by ConfigurationService
        // on startup by setting password regExpr and cmd line args list
        ConfigUtils.PASSWORD_SYS_PROPS = "pass";
        ConfigUtils.PASSWORD_CMD_LINE_ARGS = "user_password";


        ShutdownServiceImpl shutdownService = new ShutdownServiceImpl();
        // Register shutdown hook to perform cleanup before exit
        Runtime.getRuntime().addShutdownHook(new Thread(shutdownService::beginShutdown));

        JicofoServices jicofoServices = new JicofoServices();
        JicofoServices.jicofoServicesSingleton = jicofoServices;

        try
        {
            shutdownService.waitForShutdown();
        }
        catch (Exception e)
        {
            logger.error(e, e);
        }

        logger.info("Stopping services.");
        jicofoServices.shutdown();
        TaskPools.shutdown();
        JicofoServices.jicofoServicesSingleton = null;
    }

    /**
     * Read the command line arguments and env variables, and set the corresponding system properties used for
     * configuration of the XMPP component and client connections.
     */
    private static void setSystemProperties(String[] args)
            throws ParseException
    {
        CmdLine cmdLine = new CmdLine();

        // We may end execution here if one of required arguments is missing
        cmdLine.parse(args);

        // XMPP host/domain
        String host;
        String componentDomain;
        // Try to get domain, can be null after this call(we'll fix that later)
        componentDomain = cmdLine.getOptionValue("domain");
        // Host name
        host = cmdLine.getOptionValue("--host", componentDomain == null ? "localhost" : componentDomain);
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
}
