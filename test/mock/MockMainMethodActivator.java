/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package mock;

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
        focusComponent = new FocusComponent(true);

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
