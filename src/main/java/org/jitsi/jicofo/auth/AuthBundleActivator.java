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
package org.jitsi.jicofo.auth;

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.*;
import org.glassfish.jersey.servlet.*;
import org.jitsi.jicofo.rest.*;
import org.jitsi.osgi.*;
import org.jitsi.rest.*;
import org.jitsi.service.configuration.*;
import org.jitsi.utils.logging.*;
import org.jxmpp.jid.impl.*;
import org.osgi.framework.*;

import java.util.*;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * Implements <tt>BundleActivator</tt> for the OSGi bundle responsible for
 * authentication with external systems. Authentication URL pattern must be
 * configured in order to active the bundle {@link #LOGIN_URL_PNAME}.
 *
 * @author Pawel Domas
 */
public class AuthBundleActivator
    extends AbstractJettyBundleActivator
{
    /**
     * The prefix of the names of {@code ConfigurationService} and/or
     * {@code System} properties defined by {@code AuthBundleActivator}.
     */
    private static final String AUTH_PNAME = "org.jitsi.jicofo.auth";

    /**
     * The name of the {@code ConfigurationService} property which specifies the
     * pattern of authentication URL. See {@link ShibbolethAuthAuthority} for
     * more information.
     */
    public static final String LOGIN_URL_PNAME = AUTH_PNAME + ".URL";

    /**
     * The name of the {@code ConfigurationService} property which specifies the
     * pattern of logout URL. See {@link ShibbolethAuthAuthority} for more
     * information.
     */
    public static final String LOGOUT_URL_PNAME = AUTH_PNAME + ".LOGOUT_URL";

    /**
     * The name of the {@code ConfigurationService} property which disables auto
     * login feature. Authentication sessions are destroyed immediately when the
     * conference ends.
     */
    public static final String DISABLE_AUTOLOGIN_PNAME
        = AUTH_PNAME + ".DISABLE_AUTOLOGIN";

    /**
     * Name of configuration property that controls authentication session
     * lifetime.
     */
    private final static String AUTHENTICATION_LIFETIME_PNAME
        = "org.jitsi.jicofo.auth.AUTH_LIFETIME";

    /**
     * Default lifetime of authentication session(24H).
     */
    private final static long DEFAULT_AUTHENTICATION_LIFETIME
        = 24 * 60 * 60 * 1000;

    /**
     * The {@code Logger} used by the {@code AuthBundleActivator} class and its
     * instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(AuthBundleActivator.class);

    /**
     * Reference to service registration of {@link AuthenticationAuthority}.
     */
    private
        ServiceRegistration<AuthenticationAuthority>
            authAuthorityServiceRegistration;

    /**
     * The instance of {@link AuthenticationAuthority}.
     */
    private AuthenticationAuthority authAuthority;

    static BundleContext bundleContext;

    /**
     * Initializes a new {@code AuthBundleActivator} instance.
     */
    public AuthBundleActivator()
    {
        // The server started here handles many endpoints (health checks, etc.), hence the generic
        // configuration key scope (jicofo.rest), but this class is responsible for starting it.
        super(AUTH_PNAME, "jicofo.rest");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Handler initializeHandlerList(
            BundleContext bundleContext,
            Server server)
        throws Exception
    {
        List<Handler> handlers = new ArrayList<>();

        // Shibboleth
        if (authAuthority instanceof ShibbolethAuthAuthority)
        {
            logger.info("Adding Shibboleth handler");
            ShibbolethAuthAuthority shibbolethAuthAuthority
                = (ShibbolethAuthAuthority) authAuthority;

            handlers.add(new ShibbolethHandler(shibbolethAuthAuthority));
        }

        // FIXME While Shibboleth is optional, the health checks of Jicofo (over
        // REST) are mandatory at the time of this writing. Make the latter
        // optional as well (in a way similar to Videobridge, for example).
        ServletContextHandler appHandler
            = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        appHandler.setContextPath("/");
        appHandler.addServlet(new ServletHolder(new ServletContainer(
            new Application(bundleContext))), "/*");
        handlers.add(appHandler);

        return initializeHandlerList(handlers);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        AuthBundleActivator.bundleContext = bundleContext;

        ConfigurationService cfg
            = ServiceUtils2.getService(
                    bundleContext,
                    ConfigurationService.class);
        String loginUrl = cfg.getString(LOGIN_URL_PNAME);
        long authenticationLifetime
            = cfg.getLong(
                    AUTHENTICATION_LIFETIME_PNAME,
                    DEFAULT_AUTHENTICATION_LIFETIME);
        boolean disableAutoLogin
            = cfg.getBoolean(
                    DISABLE_AUTOLOGIN_PNAME, false);

        if (isNotBlank(loginUrl))
        {
            logger.info("Starting authentication service... URL: " + loginUrl);

            if (loginUrl.toUpperCase().startsWith("XMPP:"))
            {
                authAuthority
                    = new XMPPDomainAuthAuthority(
                            disableAutoLogin,
                            authenticationLifetime,
                            JidCreate.domainBareFrom(loginUrl.substring(5)));
            }
            else if (loginUrl.toUpperCase().startsWith("EXT_JWT:"))
            {
                authAuthority
                    = new ExternalJWTAuthority(
                            JidCreate.domainBareFrom(loginUrl.substring(8)));
            }
            else
            {
                String logoutUrl = cfg.getString(LOGOUT_URL_PNAME);

                authAuthority
                    = new ShibbolethAuthAuthority(
                            disableAutoLogin,
                            authenticationLifetime, loginUrl, logoutUrl);
            }
        }

        if (authAuthority != null)
        {
            logger.info("Auth authority: " + authAuthority);

            authAuthorityServiceRegistration
                = bundleContext.registerService(
                        AuthenticationAuthority.class,
                        authAuthority,
                        null);

            authAuthority.start();
        }

        super.start(bundleContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (authAuthorityServiceRegistration != null)
        {
            authAuthorityServiceRegistration.unregister();
            authAuthorityServiceRegistration = null;
        }
        if (authAuthority != null)
        {
            authAuthority.stop();
            authAuthority = null;
        }

        super.stop(bundleContext);

        AuthBundleActivator.bundleContext = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean willStart(BundleContext bundleContext)
        throws Exception
    {
        boolean b = super.willStart(bundleContext);

        if (b)
        {
            // Shibboleth works the same as every other web-based Single Sign-on
            // (SSO) system so it requires the Jetty HTTP server.
            b = (authAuthority instanceof ShibbolethAuthAuthority);

            // FIXME While Shibboleth is optional, the health checks of Jicofo
            // (over REST) are mandatory at the time of this writing. Make the
            // latter optional as well (in a way similar to Videobridge, for
            // example).
            if (!b)
                b = true;
        }
        return b;
    }
}
