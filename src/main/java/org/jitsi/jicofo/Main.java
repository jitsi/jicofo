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
import org.jitsi.jicofo.osgi.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.meet.*;
import org.jitsi.metaconfig.*;
import org.jitsi.utils.logging.*;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * Provides the <tt>main</tt> entry point of Jitsi Meet conference focus.
 *
 * @author Pawel Domas
 */
public class Main
{
    private static Logger logger = Logger.getLogger(Main.class);

    /**
     * Stores {@link FocusComponent} instance for the health check purpose.
     */
    private static FocusComponent focusXmppComponent;

    /**
     * @return the Jicofo XMPP component.
     */
    public static FocusComponent getFocusXmppComponent()
    {
        return focusXmppComponent;
    }

    /**
     * Program entry point.
     * @param args command-line arguments.
     */
    public static void main(String[] args)
        throws ParseException
    {
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            logger.error("An uncaught exception occurred in thread=" + t, e));

        setupMetaconfigLogger();
        setSystemProperties(args);

        ComponentMain componentMain = new ComponentMain();

        ClientConnectionConfig clientConnectionConfig = XmppConfig.xmppConfig.getClientConnectionConfig();

        // Whether the XMPP user connection is authenticated or anonymous
        boolean isAnonymous = isBlank(clientConnectionConfig.getPassword());
        // The JID of the XMPP user connection
        String jicofoClientJid
            = clientConnectionConfig.getUsername().toString() + "@" + clientConnectionConfig.getDomain().toString();

        focusXmppComponent = new FocusComponent(new ComponentConfig(), isAnonymous, jicofoClientJid);

        JicofoBundleConfig osgiBundles = new JicofoBundleConfig();

        componentMain.runMainProgramLoop(focusXmppComponent, osgiBundles);
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
            System.setProperty(FocusManager.XMPP_DOMAIN_PNAME, componentDomain);
            System.setProperty(ComponentConfig.domainPropertyName, componentDomain);
        }
        if (host != null)
        {
            // For backward compat, the "--host" command line argument controls the hostname for the XMPP component
            // as well as XMPP client connection.
            System.setProperty(ComponentConfig.hostnamePropertyName, host);
            System.setProperty(ClientConnectionConfig.legacyHostnamePropertyName, host);
        }

        String componentSubDomain = cmdLine.getOptionValue("--subdomain", "focus");
        if (componentSubDomain != null)
        {
            System.setProperty(ComponentConfig.subdomainPropertyName, componentSubDomain);
        }

        int port = cmdLine.getIntOptionValue("--port", 5347);
        System.setProperty(ComponentConfig.portPropertyName, String.valueOf(port));

        String secret = cmdLine.getOptionValue("--secret");
        if (isBlank(secret))
        {
            secret = System.getenv("JICOFO_SECRET");
        }
        if (secret != null)
        {
            System.setProperty(ComponentConfig.secretPropertyName, secret);
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
            System.setProperty(ClientConnectionConfig.legacyDomainPropertyName, focusDomain);
        }
        if (focusUserName != null)
        {
            System.setProperty(ClientConnectionConfig.legacyUsernamePropertyName, focusUserName);
        }
        if (isNotBlank(focusPassword))
        {
            System.setProperty(ClientConnectionConfig.legacyPasswordPropertyName, focusPassword);
        }
    }

    private static void setupMetaconfigLogger() {
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
