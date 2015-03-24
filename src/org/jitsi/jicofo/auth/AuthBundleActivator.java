/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.auth;

import net.java.sip.communicator.util.*;
import net.java.sip.communicator.util.Logger;

import org.eclipse.jetty.ajp.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.nio.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.osgi.framework.*;

import java.lang.reflect.*;

/**
 * Implements <tt>BundleActivator</tt> for the OSGi bundle responsible for
 * authentication with external systems. Authentication URL pattern must be
 * configured in order to active the bundle {@link #LOGIN_URL_PNAME}.
 *
 * @author Pawel Domas
 */
public class AuthBundleActivator
    implements BundleActivator
{
    /**
     * Prefix for config properties in this class.
     */
    private static final String AUTH_PNAME = "org.jitsi.jicofo.auth";

    /**
     * The name of configuration property that specifies the
     * pattern of authentication URL. See {@link ShibbolethAuthAuthority}
     * for more info.
     */
    public static final String LOGIN_URL_PNAME = AUTH_PNAME + ".URL";

    /**
     * The name of configuration property that specifies the
     * pattern of logout URL. See {@link ShibbolethAuthAuthority}
     * for more info.
     */
    public static final String LOGOUT_URL_PNAME = AUTH_PNAME + ".LOGOUT_URL";

    /**
     * The name of the property that disables auto login feature. Authentication
     * sessions are destroyed immediately when the conference ends.
     */
    public static final String DISABLE_AUTOLOGIN_PNAME
        = AUTH_PNAME + ".DISABLE_AUTOLOGIN";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * property which specifies the port on which the servlet handling
     * external authentication works. The default value is <tt>8888</tt>.
     */
    private static final String JETTY_PORT_PNAME
        = AUTH_PNAME + ".jetty.port";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * property which specifies the keystore password to be utilized by
     * <tt>SslContextFactory</tt> when authentication servlet works on HTTPS.
     */
    private static final String JETTY_SSLCONTEXTFACTORY_KEYSTOREPASSWORD
        = AUTH_PNAME + ".jetty.sslContextFactory.keyStorePassword";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * property which specifies the keystore path to be utilized by
     * <tt>SslContextFactory</tt> when authentication servlet works on HTTPS.
     */
    private static final String JETTY_SSLCONTEXTFACTORY_KEYSTOREPATH
        = AUTH_PNAME + ".jetty.sslContextFactory.keyStorePath";

    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * property which specifies whether client certificate authentication is to
     * be required by <tt>SslContextFactory</tt> when authentication servlet
     * works on HTTPS.
     */
    private static final String JETTY_SSLCONTEXTFACTORY_NEEDCLIENTAUTH
        = AUTH_PNAME + ".jetty.sslContextFactory.needClientAuth";

    /**
     * The name of the <tt>System</tt> and/or <tt>ConfigurationService</tt>
     * property which specifies the port used by authentication servlet with
     * HTTPS. The default value is <tt>8443</tt>.
     */
    private static final String JETTY_TLS_PORT_PNAME
        = AUTH_PNAME + ".jetty.tls.port";

    /**
     * The <tt>Logger</tt>.
     */
    private static final Logger logger
        = Logger.getLogger(AuthBundleActivator.class);

    /**
     * The Jetty <tt>Server</tt> used to run authentication servlet.
     */
    private Server server;

    /**
     * Reference to service registration of {@link AuthenticationAuthority}.
     */
    private ServiceRegistration<AuthenticationAuthority>
            authAuthorityServiceRegistration;

    /**
     * The instance of {@link AuthenticationAuthority}.
     */
    private AuthenticationAuthority authAuthority;

    static BundleContext bundleContext;

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        AuthBundleActivator.bundleContext = bundleContext;

        ConfigurationService cfg
                = ServiceUtils.getService(
                        bundleContext,
                        ConfigurationService.class);

        String loginUrl = cfg.getString(LOGIN_URL_PNAME);
        String logoutUrl = cfg.getString(LOGOUT_URL_PNAME);

        if (StringUtils.isNullOrEmpty(loginUrl))
        {
            return;
        }

        logger.info("Starting authentication service... URL: " + loginUrl);

        if (loginUrl.toUpperCase().startsWith("XMPP:"))
        {
            this.authAuthority
                = new XMPPDomainAuthAuthority(loginUrl.substring(5));
        }
        else
        {
            this.authAuthority
                = new ShibbolethAuthAuthority(loginUrl, logoutUrl);
        }

        logger.info("Auth authority: " + authAuthority);

        authAuthorityServiceRegistration
            = bundleContext.registerService(
                    AuthenticationAuthority.class, authAuthority, null);

        authAuthority.start();

        // FIXME move Jetty related code to separate class
        if (!(authAuthority instanceof ShibbolethAuthAuthority))
        {
            return;
        }

        ShibbolethAuthAuthority shibbolethAuthAuthority
            = (ShibbolethAuthAuthority) authAuthority;

        // The REST API of Videobridge does not start by default.
        int port = 8888, tlsPort = 8843;
        String sslContextFactoryKeyStorePassword, sslContextFactoryKeyStorePath;
        boolean sslContextFactoryNeedClientAuth = false;

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
            Server server = new Server();

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
            else
            {
                // FIXME: add HTTPS
                //HttpConfiguration httpCfg = new HttpConfiguration();

                //httpCfg.setSecurePort(tlsPort);
                //httpCfg.setSecureScheme("https");
                /*File sslContextFactoryKeyStoreFile
                    = getAbsoluteFile(sslContextFactoryKeyStorePath, cfg);
                SslContextFactory sslContextFactory = new SslContextFactory();

                sslContextFactory.setExcludeCipherSuites(
                        "SSL_RSA_WITH_DES_CBC_SHA",
                        "SSL_DHE_RSA_WITH_DES_CBC_SHA",
                        "SSL_DHE_DSS_WITH_DES_CBC_SHA",
                        "SSL_RSA_EXPORT_WITH_RC4_40_MD5",
                        "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
                        "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                        "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA");
                sslContextFactory.setIncludeCipherSuites(".*RC4.*");
                if (sslContextFactoryKeyStorePassword != null)
                {
                    sslContextFactory.setKeyStorePassword(
                            sslContextFactoryKeyStorePassword);
                }
                sslContextFactory.setKeyStorePath(
                        sslContextFactoryKeyStoreFile.getPath());
                sslContextFactory.setNeedClientAuth(
                        sslContextFactoryNeedClientAuth);

                HttpConfiguration httpsCfg = new HttpConfiguration(httpCfg);

                httpsCfg.addCustomizer(new SecureRequestCustomizer());

                ServerConnector sslConnector
                    = new ServerConnector(
                            server,
                            new SslConnectionFactory(
                                    sslContextFactory,
                                    "http/1.1"),
                            new HttpConnectionFactory(httpsCfg));
                sslConnector.setPort(tlsPort);
                server.addConnector(sslConnector);*/

                /*
                The example borrowed from Jetty 8 docs
                SslSelectChannelConnector ssl_connector = new SslSelectChannelConnector();
                String jetty_home =
                  System.getProperty("jetty.home","../jetty-distribution/target/distribution");
                System.setProperty("jetty.home",jetty_home);
                ssl_connector.setPort(8443);
                SslContextFactory cf = ssl_connector.getSslContextFactory();
                cf.setKeyStore(jetty_home + "/etc/keystore");
                cf.setKeyStorePassword("OBF:1vn2343223434f43rt5sg23241zlu1vn4");
                cf.setKeyManagerPassword("OBF:1234324fa4lf4351u2g");
                 */
            }

            // AJP
            Ajp13SocketConnector ajp13SocketConnector
                = new Ajp13SocketConnector();
            ajp13SocketConnector.setPort(8009);

            server.addConnector(ajp13SocketConnector);

            server.setHandler(
                    new ShibbolethHandler(shibbolethAuthAuthority));

            /*
             * The server will start a non-daemon background Thread which will
             * keep the application running on success. 
             */
            server.start();

            this.server = server;
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

        if (server != null)
        {
            server.stop();
            server = null;
        }

        AuthBundleActivator.bundleContext = null;
    }
}
