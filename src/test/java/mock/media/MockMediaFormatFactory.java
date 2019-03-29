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
package mock.media;

import org.jitsi.impl.neomedia.format.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.utils.*;

import java.util.*;

/**
 *
 */
public class MockMediaFormatFactory
    implements MediaFormatFactory
{
    @Override
    public MediaFormat createUnknownMediaFormat(MediaType type)
    {
        return new AudioMediaFormatImpl("unknown");
    }

    @Override
    public MediaFormat createMediaFormat(String encoding)
    {
        return null;
    }

    @Override
    public MediaFormat createMediaFormat(byte rtpPayloadType)
    {
        return null;
    }

    @Override
    public MediaFormat createMediaFormat(String encoding, double clockRate)
    {
        return null;
    }

    @Override
    public MediaFormat createMediaFormat(String encoding, double clockRate,
                                         int channels)
    {
        return null;
    }

    @Override
    public MediaFormat createMediaFormat(String encoding, double clockRate,
                                         Map<String, String> formatParams,
                                         Map<String, String> advancedAttrs)
    {
        return null;
    }

    @Override
    public MediaFormat createMediaFormat(String encoding, double clockRate,
                                         int channels, float frameRate,
                                         Map<String, String> formatParams,
                                         Map<String, String> advancedAttrs)
    {
        return null;
    }

    @Override
    public MediaFormat createMediaFormat(byte rtpPayloadType, String encoding,
                                         double clockRate, int channels,
                                         float frameRate,
                                         Map<String, String> formatParams,
                                         Map<String, String> advancedAttrs)
    {
        return null;
    }
}
