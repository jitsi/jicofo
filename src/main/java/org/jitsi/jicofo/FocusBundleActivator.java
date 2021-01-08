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

import org.osgi.framework.*;

/**
 * Activator of the Jitsi Meet Focus bundle.
 *
 * @author Pawel Domas
 */
public class FocusBundleActivator
    implements BundleActivator
{
    /**
     * OSGi bundle context held by this activator.
     */
    public static BundleContext bundleContext;

    /**
     * {@link org.jitsi.jicofo.FocusManager} instance created by this activator.
     */
    private FocusManager focusManager;

    @Override
    public void start(BundleContext context)
        throws Exception
    {
        bundleContext = context;

        focusManager = new FocusManager();
        focusManager.start();
    }

    @Override
    public void stop(BundleContext context)
        throws Exception
    {
        if (focusManager != null)
        {
            focusManager.stop();
            focusManager = null;
        }
    }
}
