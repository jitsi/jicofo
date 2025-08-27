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
import org.jitsi.config.*;
import org.jitsi.metaconfig.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import sun.misc.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;


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
    {
        logger.info("Starting Jicofo.");

        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                logger.error("An uncaught exception occurred in thread=" + t, e));

        if (System.getProperty("config.file", "").isEmpty())
        {
            logger.warn("Required property config.file is missing. Set with -Dconfig.file=");
            return;
        }
        setupMetaconfigLogger();
        JitsiConfig.Companion.reloadNewConfig();

        // Make sure that passwords are not printed by ConfigurationService
        // on startup by setting password regExpr and cmd line args list
        ConfigUtils.PASSWORD_SYS_PROPS = "pass";
        ConfigUtils.PASSWORD_CMD_LINE_ARGS = "user_password";


        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(shutdownLatch::countDown));

        JicofoServices jicofoServices;
        try
        {
            // JicofoServices starts various services in different threads, some of which depend on the singleton being
            // set. Make sure they are blocked until the initialization has completed and the singleton has been set.
            synchronized (JicofoServices.getJicofoServicesSingletonSyncRoot())
            {
                jicofoServices = new JicofoServices();
                JicofoServices.setJicofoServicesSingleton(jicofoServices);
            }
        }
        catch (Exception e)
        {
            logger.error("Failed to start jicofo: ", e);
            TaskPools.shutdown();
            return;
        }

        AtomicInteger exitStatus = new AtomicInteger();

        /* Catch signals and cause them to trigger a clean shutdown. */
        for (String signalName : List.of("TERM", "HUP", "INT"))
        {
            try
            {
                Signal.handle(new Signal(signalName), signal ->
                {
                    // Matches java.lang.Terminator
                    exitStatus.set(signal.getNumber() + 128);
                    logger.info("Caught signal " + signal + ", shutting down.");

                    shutdownLatch.countDown();
                });
            }
            catch (IllegalArgumentException e)
            {
                /* Unknown signal on this platform, or not allowed to register this signal; that's fine. */
                logger.warn("Unable to register signal " + signalName, e);
            }
        }


        try
        {
            shutdownLatch.await();
        }
        catch (Exception e)
        {
            logger.error(e, e);
        }

        logger.info("Stopping services.");
        jicofoServices.shutdown();
        TaskPools.shutdown();
        JicofoServices.setJicofoServicesSingleton(null);
        logger.info("Jicofo has been stopped, exiting with exit code " + exitStatus.get());
        System.exit(exitStatus.get());
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
