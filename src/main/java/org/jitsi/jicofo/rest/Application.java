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
import org.jetbrains.annotations.*;
import org.jitsi.jicofo.auth.*;
import org.jitsi.jicofo.health.*;
import org.jitsi.utils.version.*;

import java.time.*;

/**
 * Adds the configuration for the REST web endpoints.
 */
public class Application
    extends ResourceConfig
{
    protected final Clock clock = Clock.systemUTC();

    public Application(ShibbolethAuthAuthority shibbolethAuthAuthority,
                       @NotNull Version version,
                       JicofoHealthChecker healthChecker)
    {
        register(new AbstractBinder()
        {
            @Override
            protected void configure()
            {
               bind(clock).to(Clock.class);
            }
        });
        packages("org.jitsi.jicofo.rest");

        if (healthChecker != null)
        {
            register(new org.jitsi.rest.Health(healthChecker));
        }

        register(new org.jitsi.rest.Version(version));

        if (shibbolethAuthAuthority != null)
        {
            register(new ShibbolethLogin(shibbolethAuthAuthority));
        }
    }
}
