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
import org.jitsi.rest.*;
import org.jitsi.utils.logging.*;
import org.jxmpp.jid.impl.*;
import org.osgi.framework.*;

import java.time.*;
import java.util.*;

/**
 * Implements <tt>BundleActivator</tt> for the OSGi bundle responsible for
 * authentication with external systems.
 *
 * @author Pawel Domas
 */
public class AuthBundleActivator
    extends AbstractJettyBundleActivator
{
    /**
     * The {@code Logger} used by the {@code AuthBundleActivator} class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(AuthBundleActivator.class);

    /**
     * The instance of {@link AuthenticationAuthority}.
     */
    public static AuthenticationAuthority authAuthority;

    /**
     * Initializes a new {@code AuthBundleActivator} instance.
     */
    public AuthBundleActivator()
    {
        // The server started here handles many endpoints (health checks, etc.), hence the generic
        // configuration key scope (jicofo.rest), but this class is responsible for starting it.
        super("org.jitsi.jicofo.auth", "jicofo.rest");
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
        if (AuthConfig.config.getEnabled())
        {
            String loginUrl = AuthConfig.config.getLoginUrl();
            AuthConfig.Type type = AuthConfig.config.getType();
            Duration authenticationLifetime = AuthConfig.config.getAuthenticationLifetime();
            boolean enableAutoLogin = AuthConfig.config.getEnableAutoLogin();
            logger.info("Starting authentication service with type=" + type +" loginUrl=" + loginUrl + " lifetime="
                + authenticationLifetime);

            switch (type)
            {
            case XMPP:
                authAuthority
                    = new XMPPDomainAuthAuthority(
                            enableAutoLogin,
                            authenticationLifetime,
                            JidCreate.domainBareFrom(loginUrl));
                break;
            case JWT:
                authAuthority
                    = new ExternalJWTAuthority(
                            JidCreate.domainBareFrom(loginUrl));
                break;
            case SHIBBOLETH:
                authAuthority
                    = new ShibbolethAuthAuthority(
                            enableAutoLogin,
                            authenticationLifetime,
                            loginUrl,
                            AuthConfig.config.getLogoutUrl());
            }

            logger.info("Auth authority: " + authAuthority);

            authAuthority.start();
        }

        // NB: this is what starts Jetty...
        super.start(bundleContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (authAuthority != null)
        {
            authAuthority.stop();
            authAuthority = null;
        }

        super.stop(bundleContext);
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
