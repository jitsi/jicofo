package mock.media;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.format.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.format.*;

import javax.media.*;
import javax.media.format.*;
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
