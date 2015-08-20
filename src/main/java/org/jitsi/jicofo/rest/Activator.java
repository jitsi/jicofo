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
package org.jitsi.jicofo.rest;

import net.java.sip.communicator.util.*;
import net.java.sip.communicator.util.Logger;
import org.eclipse.jetty.ajp.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.nio.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.auth.*;
import org.jitsi.jicofo.reservation.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.osgi.framework.*;

import java.lang.reflect.*;

/**
 * Plugin bundle activator for REST reservation system.
 *
 * @author Pawel Domas
 */
public class Activator
    implements BundleActivator
{
    /**
     * The logger.
     */
    private final static Logger logger
        = Logger.getLogger(Activator.class);

    private static final String REST_PNAME = "org.jitsi.jicofo.rest";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * property which specifies the port on which the servlet handling
     * external authentication works. The default value is <tt>8888</tt>.
     */
    private static final String JETTY_PORT_PNAME
        = REST_PNAME + ".jetty.port";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * property which specifies the keystore password to be utilized by
     * <tt>SslContextFactory</tt> when authentication servlet works on HTTPS.
     */
    private static final String JETTY_SSLCONTEXTFACTORY_KEYSTOREPASSWORD
        = REST_PNAME + ".jetty.sslContextFactory.keyStorePassword";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * property which specifies the keystore path to be utilized by
     * <tt>SslContextFactory</tt> when authentication servlet works on HTTPS.
     */
    private static final String JETTY_SSLCONTEXTFACTORY_KEYSTOREPATH
        = REST_PNAME + ".jetty.sslContextFactory.keyStorePath";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * property which specifies whether client certificate authentication is to
     * be required by <tt>SslContextFactory</tt> when authentication servlet
     * works on HTTPS.
     */
    private static final String JETTY_SSLCONTEXTFACTORY_NEEDCLIENTAUTH
        = REST_PNAME + ".jetty.sslContextFactory.needClientAuth";

    /**
     * The name of the <tt>System</tt> and/or <tt>ConfigurationService</tt>
     * property which specifies the port used by authentication servlet with
     * HTTPS. The default value is <tt>8443</tt>.
     */
    private static final String JETTY_TLS_PORT_PNAME
        = REST_PNAME + ".jetty.tls.port";

    /**
     * Reservation system handler.
     */
    RESTControl restControl;

    RequestHandler requestHandler = new RequestHandler();

    /**
     * Reservation system OSGi service registration.
     */
    private ServiceRegistration<RESTControl> serviceRegistration;

    private Server server;

    @Override
    public void start(BundleContext context)
            throws Exception
    {
        ConfigurationService config
            = ServiceUtils.getService(context, ConfigurationService.class);

        // The REST API of Videobridge does not start by default.
        int port = 8888, tlsPort = 8843;
        String sslContextFactoryKeyStorePassword, sslContextFactoryKeyStorePath;
        boolean sslContextFactoryNeedClientAuth = false;

        ConfigurationService cfg
            = ServiceUtils.getService(context, ConfigurationService.class);

        if (cfg == null)
        {
            port = Integer.getInteger(JETTY_PORT_PNAME, port);
            sslContextFactoryKeyStorePassword
                = System.getProperty(JETTY_SSLCONTEXTFACTORY_KEYSTOREPASSWORD);
            sslContextFactoryKeyStorePath
                = System.getProperty(JETTY_SSLCONTEXTFACTORY_KEYSTOREPATH);
            sslContextFactoryNeedClientAuth
                = Boolean.getBoolean(JETTY_SSLCONTEXTFACTORY_NEEDCLIENTAUTH);
            tlsPort = Integer.getInteger(JETTY_TLS_PORT_PNAME, tlsPort);
        }
        else
        {
            port = cfg.getInt(JETTY_PORT_PNAME, port);
            sslContextFactoryKeyStorePassword
                = cfg.getString(JETTY_SSLCONTEXTFACTORY_KEYSTOREPASSWORD);
            sslContextFactoryKeyStorePath
                = cfg.getString(JETTY_SSLCONTEXTFACTORY_KEYSTOREPATH);
            sslContextFactoryNeedClientAuth
                = cfg.getBoolean(
                JETTY_SSLCONTEXTFACTORY_NEEDCLIENTAUTH,
                sslContextFactoryNeedClientAuth);
            tlsPort = cfg.getInt(JETTY_TLS_PORT_PNAME, tlsPort);
        }

        try
        {
            this.server = new Server();

            /*
             * If HTTPS is not enabled, serve the REST API of Jitsi Videobridge
             * over HTTP.
             */
            if (sslContextFactoryKeyStorePath == null)
            {
                // HTTP
                SelectChannelConnector httpConnector
                    = new SelectChannelConnector();

                httpConnector.setPort(port);
                server.addConnector(httpConnector);
            }

            server.setHandler(requestHandler);

            /*
             * The server will start a non-daemon background Thread which will
             * keep the application running on success.
             */
            server.start();

            logger.info("!!! started REST endpoint on port " + port);
        }
        catch (Throwable t)
        {
            // Log any Throwable for debugging purposes and rethrow.
            logger.error("Failed to start external authentication bundle.", t);
            if (t instanceof Error)
                throw (Error) t;
            else if (t instanceof Exception)
                throw (Exception) t;
            else
                throw new UndeclaredThrowableException(t);
        }

        restControl = new RESTControl();

        serviceRegistration = context.registerService(
            RESTControl.class, restControl, null);

        FocusManager focusManager
            = ServiceUtils.getService(context, FocusManager.class);

        restControl.start(focusManager);
    }

    @Override
    public void stop(BundleContext context)
            throws Exception
    {
        if (serviceRegistration != null)
        {
            serviceRegistration.unregister();
            serviceRegistration = null;

            restControl.stop();
        }

        if (server != null)
            server.stop();
    }
}
