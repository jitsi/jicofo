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

import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.control.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.event.*;

import static org.mockito.Mockito.*;

import java.awt.*;
import java.util.*;

/**
 * {@link VideoMediaStream} mock implementation.
 *
 * @author Pawel Domas
 */
public class MockVideoMediaStream
    extends MockMediaStream
    implements VideoMediaStream
{
    @Override
    public void addVideoListener(VideoListener videoListener)
    {

    }

    @Override
    public KeyFrameControl getKeyFrameControl()
    {
        return null;
    }

    @Override
    public Component getLocalVisualComponent()
    {
        return null;
    }

    @Override
    public QualityControl getQualityControl()
    {
        return null;
    }

    @Override
    public Component getVisualComponent()
    {
        return null;
    }

    @Override
    public Component getVisualComponent(long l)
    {
        return null;
    }

    @Override
    public java.util.List<Component> getVisualComponents()
    {
        return null;
    }

    @Override
    public void movePartialDesktopStreaming(int i, int i1)
    {

    }

    @Override
    public void removeVideoListener(VideoListener videoListener)
    {

    }

    @Override
    public void updateQualityControl(Map<String, String> map)
    {

    }

    @Override
    public BandwidthEstimator getOrCreateBandwidthEstimator()
    {
        return mock(BandwidthEstimator.class);
    }
}
