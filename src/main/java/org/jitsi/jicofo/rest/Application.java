/*
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.glassfish.hk2.utilities.binding.*;
import org.glassfish.jersey.server.*;
import org.jitsi.jicofo.util.*;
import org.osgi.framework.*;

import java.time.*;

/**
 * Adds the configuration for the REST web endpoints.
 */
public class Application
    extends ResourceConfig
{
    protected final Clock clock = Clock.systemUTC();

    public Application(BundleContext bundleContext)
    {
        register(new OsgiServiceBinder(bundleContext));
        register(new AbstractBinder()
        {
            @Override
            protected void configure()
            {
               bind(clock).to(Clock.class);
            }
        });
        packages("org.jitsi.jicofo.rest");
        // Load any resources from Jicoco
        packages("org.jitsi.rest");
    }
}
