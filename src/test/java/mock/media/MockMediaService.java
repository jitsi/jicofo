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
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.service.neomedia.recording.*;
import org.jitsi.utils.*;

import java.awt.*;
import java.beans.*;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 *
 */
public class MockMediaService
    implements MediaService
{
    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener)
    {

    }

    @Override
    public void addRecorderListener(Recorder.Listener listener)
    {

    }

    @Override
    public EncodingConfiguration createEmptyEncodingConfiguration()
    {
        return null;
    }

    @Override
    public MediaStream createMediaStream(MediaDevice device)
    {
        return null;
    }

    @Override
    public MediaStream createMediaStream(MediaType mediaType)
    {
        return null;
    }

    @Override
    public MediaStream createMediaStream(StreamConnector connector,
                                         MediaDevice device)
    {
        return null;
    }

    @Override
    public MediaStream createMediaStream(StreamConnector connector,
                                         MediaType mediaType)
    {
        return null;
    }

    @Override
    public MediaStream createMediaStream(StreamConnector connector,
                                         MediaDevice device,
                                         SrtpControl srtpControl)
    {
        return null;
    }

    @Override
    public MediaStream createMediaStream(StreamConnector connector,
                                         MediaType mediaType,
                                         SrtpControl srtpControl)
    {
        return createMockStream(mediaType);
    }

    private MockMediaStream createMockStream(MediaType mediaType)
    {
        if (MediaType.VIDEO.equals(mediaType))
        {
            return new MockVideoMediaStream();
        }
        else
        {
            return new MockMediaStream();
        }
    }

    @Override
    public MediaDevice createMixer(MediaDevice device)
    {
        return null;
    }

    @Override
    public Recorder createRecorder(MediaDevice device)
    {
        return null;
    }

    @Override
    public Recorder createRecorder(RTPTranslator translator)
    {
        return null;
    }

    @Override
    public RTPTranslator createRTPTranslator()
    {
        return null;
    }

    @Override
    public SrtpControl createSrtpControl(SrtpControlType srtpControlType)
    {
        return null;
    }

    @Override
    public List<ScreenDevice> getAvailableScreenDevices()
    {
        return null;
    }

    @Override
    public EncodingConfiguration getCurrentEncodingConfiguration()
    {
        return null;
    }

    @Override
    public MediaDevice getDefaultDevice(MediaType mediaType,
                                        MediaUseCase useCase)
    {
        return null;
    }

    @Override
    public ScreenDevice getDefaultScreenDevice()
    {
        return null;
    }

    @Override
    public List<MediaDevice> getDevices(MediaType mediaType,
                                        MediaUseCase useCase)
    {
        return null;
    }

    @Override
    public Map<MediaFormat, Byte> getDynamicPayloadTypePreferences()
    {
        return null;
    }

    @Override
    public MediaFormatFactory getFormatFactory()
    {
        return new MockMediaFormatFactory();
    }

    @Override
    public VolumeControl getInputVolumeControl()
    {
        return null;
    }

    @Override
    public MediaDevice getMediaDeviceForPartialDesktopStreaming(int width,
                                                                int height,
                                                                int x, int y)
    {
        return null;
    }

    @Override
    public Point getOriginForDesktopStreamingDevice(MediaDevice mediaDevice)
    {
        return null;
    }

    @Override
    public VolumeControl getOutputVolumeControl()
    {
        return null;
    }

    @Override
    public Iterator<Recorder.Listener> getRecorderListeners()
    {
        return null;
    }

    @Override
    public Object getVideoPreviewComponent(MediaDevice device,
                                           int preferredWidth,
                                           int preferredHeight)
    {
        return null;
    }

    @Override
    public boolean isPartialStreaming(MediaDevice mediaDevice)
    {
        return false;
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener)
    {

    }

    @Override
    public void removeRecorderListener(Recorder.Listener listener)
    {

    }

    @Override
    public String getRtpCname()
    {
        return null;
    }

    @Override
    public RecorderEventHandler createRecorderEventHandlerJson(String filename)
        throws IOException
    {
        return null;
    }
}
