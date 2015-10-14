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

import java.io.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.eclipse.jetty.server.*;
import org.jitsi.jicofo.FocusManager;
import org.jitsi.rest.*;
import org.osgi.framework.*;

/**
 * Implements a Jetty {@code Handler} which is to provide the HTTP interface of
 * the JSON public API of Jicofo.
 *
 * @author Lyubomir Marinov
 */
public class HandlerImpl
    extends AbstractJSONHandler
{
    /**
     * Initializes a new {@code HandlerImpl} instance within a specific
     * {@code BundleContext}.
     *
     * @param bundleContext the {@code BundleContext} within which the new
     * instance is to be initialized
     */
    public HandlerImpl(BundleContext bundleContext)
    {
        super(bundleContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doGetHealthJSON(
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
        throws IOException,
               ServletException
    {
        beginResponse(/* target */ null, baseRequest, request, response);

        FocusManager focusManager = getFocusManager();

        if (focusManager == null)
        {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
        else
        {
            Health.getJSON(focusManager, baseRequest, request, response);
        }

        endResponse(/* target */ null, baseRequest, request, response);
    }

    /**
     * Gets the {@code FocusManager} instance available to this Jetty
     * {@code Handler}.
     *
     * @return the {@code FocusManager} instance available to this Jetty
     * {@code Handler} or {@code null} if no {@code FocusManager} instance is
     * available to this Jetty {@code Handler}
     */
    public FocusManager getFocusManager()
    {
        return getService(FocusManager.class);
    }
}
