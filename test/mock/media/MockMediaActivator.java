package mock.media;

import org.jitsi.service.neomedia.*;
import org.osgi.framework.*;

/**
 *
 */
public class MockMediaActivator
    implements BundleActivator
{
    private ServiceRegistration<MediaService> msRegistration;

    @Override
    public void start(BundleContext context)
        throws Exception
    {
        MockMediaService mediaService = new MockMediaService();

        this.msRegistration
            = context.registerService(MediaService.class, mediaService, null);
    }

    @Override
    public void stop(BundleContext context)
        throws Exception
    {
        msRegistration.unregister();
    }
}
