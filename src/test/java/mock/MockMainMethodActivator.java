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
package mock;

import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.*;
import org.osgi.framework.*;

/**
 * Activator does the job of "main" method - executed during normal program
 * flow.
 *
 * @author Pawel Domas
 */
public class MockMainMethodActivator
    implements BundleActivator
{
    private static boolean started;

    private static FocusComponent focusComponent;

    @Override
    public void start(BundleContext context)
        throws Exception
    {
        // These properties are set in OSGiHandler
        focusComponent = new FocusComponent(
            System.getProperty(FocusManager.HOSTNAME_PNAME),
            -1, // whatever port in mock
            System.getProperty(FocusManager.XMPP_DOMAIN_PNAME),
            "focus",
            "secret",
            true, "focus@test.domain.net");

        focusComponent.init();

        synchronized (MockMainMethodActivator.class)
        {
            started = true;
            MockMainMethodActivator.class.notifyAll();
        }
    }

    @Override
    public void stop(BundleContext context)
        throws Exception
    {
        focusComponent.dispose();
    }

    public static FocusComponent getFocusComponent()
    {
        return focusComponent;
    }

    public static void waitUntilStarted(long timeout)
    {
        synchronized (MockMainMethodActivator.class)
        {
            if (!started)
            {
                try
                {
                    MockMainMethodActivator.class.wait(timeout);
                    if (!started)
                    {
                        throw new RuntimeException(
                            "Failed to wait for activator to get started");
                    }
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
