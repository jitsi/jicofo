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
import org.jitsi.metaconfig.*;
import org.jitsi.shutdown.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;


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
        JitsiConfig.Companion.reloadNewConfig();

        // Make sure that passwords are not printed by ConfigurationService
        // on startup by setting password regExpr and cmd line args list
        ConfigUtils.PASSWORD_SYS_PROPS = "pass";
        ConfigUtils.PASSWORD_CMD_LINE_ARGS = "user_password";


        ShutdownServiceImpl shutdownService = new ShutdownServiceImpl();
        // Register shutdown hook to perform cleanup before exit
        Runtime.getRuntime().addShutdownHook(new Thread(shutdownService::beginShutdown));

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
        JicofoServices.setJicofoServicesSingleton(null);
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
