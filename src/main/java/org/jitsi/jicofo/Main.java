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
     * The name of the command-line argument which specifies the XMPP domain
     * to use for the XMPP client connection.
     */
    private static final String DOMAIN_ARG_NAME = "domain";

    /**
     * The name of the command-line argument which specifies the name of XMPP
     * domain used by focus user to login.
     */
    private static final String USER_DOMAIN_ARG_NAME = "--user_domain";

    /**
     * The name of the command-line argument which specifies the name of XMPP
     * user name to be used by the focus user('focus' by default).
     */
    private static final String USER_NAME_ARG_NAME = "--user_name";

    /**
     * Default value for {@link #USER_NAME_ARG_NAME}.
     */
    private static final String USER_NAME_ARG_VALUE = "focus";

    /**
     * The name of the command-line argument which specifies the password used
     * by focus XMPP user to login. If neither this argument nor the environment
     * variable named by {@link #USER_PASSWORD_ENV_NAME} is provided then focus
     * will use anonymous authentication method.
     */
    private static final String USER_PASSWORD_ARG_NAME = "--user_password";

    /**
     * The name of the environment variable which, when set and not an empty
     * string, acts as an alternative to the command-line argument named by
     * {@link #USER_PASSWORD_ARG_NAME}.
     */
    private static final String USER_PASSWORD_ENV_NAME = "JICOFO_AUTH_PASSWORD";

    /**
     * The name of the command-line argument which specifies the IP address or
     * the name of the XMPP host to connect to.
     */
    private static final String HOST_ARG_NAME = "--host";

    /**
     * The default value of the {@link #HOST_ARG_NAME} command-line argument if
     * it is not explicitly provided.
     */
    private static final String HOST_ARG_VALUE = "localhost";

    /**
     * The name of the command-line argument which specifies the port of the
     * XMPP host to connect on.
     */
    private static final String PORT_ARG_NAME = "--port";

    /**
     * The default value of the {@link #PORT_ARG_NAME} command-line argument if
     * it is not explicitly provided.
     */
    private static final int PORT_ARG_VALUE = 5347;

    /**
     * The name of the command-line argument which specifies the secret key for
     * the sub-domain of the Jabber component implemented by this application
     * with which it is to authenticate to the XMPP server to connect to. This
     * secret can alternatively be specified using an environment variable; see
     * {@link #SECRET_ENV_NAME}.
     */
    private static final String SECRET_ARG_NAME = "--secret";

    /**
     * The name of the environment variable which, when set and not an empty
     * string, acts as an alternative to the command-line argument named by
     * {@link #SECRET_ARG_NAME}.
     */
    private static final String SECRET_ENV_NAME = "JICOFO_SECRET";

    /**
     * The name of the command-line argument which specifies sub-domain name for
     * the focus component.
     */
    private static final String SUBDOMAIN_ARG_NAME = "--subdomain";

    /**
     * The name of the command-line argument which specifies sub-domain name for
     * the focus component.
     */
    private static final String SUBDOMAIN_ARG_VALUE = "focus";

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

        CmdLine cmdLine = new CmdLine();
        setupMetaconfigLogger();

        if (isBlank(System.getenv(SECRET_ENV_NAME)))
        {
            cmdLine.addRequiredArgument(SECRET_ARG_NAME);
        }

        // We may end execution here if one of required arguments is missing
        cmdLine.parse(args);

        // XMPP host/domain
        String host;
        String componentDomain;
        // Try to get domain, can be null after this call(we'll fix that later)
        componentDomain = cmdLine.getOptionValue(DOMAIN_ARG_NAME);
        // Host name
        host = cmdLine.getOptionValue(
                HOST_ARG_NAME,
                componentDomain == null ? HOST_ARG_VALUE : componentDomain);
        // Try to fix component domain
        if (isBlank(componentDomain))
        {
            componentDomain = host;
        }

        // Jicofo XMPP component
        String componentSubDomain
            = cmdLine.getOptionValue(
                    SUBDOMAIN_ARG_NAME, SUBDOMAIN_ARG_VALUE);

        int port = cmdLine.getIntOptionValue(PORT_ARG_NAME, PORT_ARG_VALUE);

        String secret = cmdLine.getOptionValue(SECRET_ARG_NAME);
        if (isBlank(secret))
        {
            secret = System.getenv(SECRET_ENV_NAME);
        }

        // Jicofo user
        String focusDomain = cmdLine.getOptionValue(USER_DOMAIN_ARG_NAME);

        String focusUserName
            = cmdLine.getOptionValue(
                    USER_NAME_ARG_NAME, USER_NAME_ARG_VALUE);

        String focusPassword = cmdLine.getOptionValue(USER_PASSWORD_ARG_NAME);
        if (isBlank(focusPassword))
        {
            focusPassword = System.getenv(USER_PASSWORD_ENV_NAME);
        }

        // Focus specific config properties
        System.setProperty(FocusManager.HOSTNAME_PNAME, host);
        System.setProperty(FocusManager.XMPP_DOMAIN_PNAME, componentDomain);
        System.setProperty(FocusManager.FOCUS_USER_DOMAIN_PNAME, focusDomain);
        System.setProperty(FocusManager.FOCUS_USER_NAME_PNAME, focusUserName);
        if (isNotBlank(focusPassword))
        {
            System.setProperty(
                    FocusManager.FOCUS_USER_PASSWORD_PNAME, focusPassword);
        }

        ComponentMain componentMain = new ComponentMain();

        boolean focusAnonymous = isBlank(focusPassword);

        focusXmppComponent
            = new FocusComponent(
                    host, port, componentDomain, componentSubDomain,
                    secret, focusAnonymous, focusUserName + "@" + focusDomain);

        JicofoBundleConfig osgiBundles = new JicofoBundleConfig();

        componentMain.runMainProgramLoop(focusXmppComponent, osgiBundles);
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
