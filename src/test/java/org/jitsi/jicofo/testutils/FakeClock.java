package org.jitsi.jicofo.testutils;

import java.time.*;

public abstract class FakeClock extends Clock
{
    private Instant now = Instant.ofEpochMilli(0);

    @Override
    public Instant instant()
    {
        return now;
    }

    public void elapse(Duration duration)
    {
        now = now.plus(duration);
    }
}
