/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2019-Present 8x8 Inc
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
package org.jitsi.protocol.xmpp.colibri.exception;

/**
 * A {@link ColibriException} indicating that the remote Colibri endpoint
 * responded to our request with a "conference not found" error.
 */
public class ConferenceNotFoundException extends ColibriException
{
    public ConferenceNotFoundException(String message)
    {
        super(message);
    }

    @Override
    public ColibriException clone(String prefix)
    {
        return new ConferenceNotFoundException(prefix + getMessage());
    }
}
