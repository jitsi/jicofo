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

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.xmpp.*;
import org.osgi.framework.*;

public class JicofoTestServices extends JicofoServices
{
    public JicofoTestServices(@NotNull BundleContext bundleContext)
    {
        super(bundleContext);
    }

    /**
     * Do not start or stop the FocusComponent.
     */
    @Override
    public void startFocusComponent()
    {
    }

    /**
     * Do not start or stop the FocusComponent.
     */
    @Override
    public void stopFocusComponent()
    {
    }

    /**
     * Expose as `public` for testing (can not override with the same name because it is final).
     */
    public FocusComponent getFocusComponent_()
    {
        return super.getFocusComponent();
    }
}
