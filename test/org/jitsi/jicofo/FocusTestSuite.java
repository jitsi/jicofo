package org.jitsi.jicofo;

import org.junit.runner.*;
import org.junit.runners.*;

/**
 * FIXME: tests work when launched individually, there are still init/deinit
 *        problems to be fixed when they run one after another
 */
@RunWith(Suite.class)
@Suite.SuiteClasses(
    {
        //XmppTest.class, FIXME: to be fixed
        MockTest.class,
        AdvertiseSSRCsTest.class,
        BundleTest.class,
        RolesTest.class
    })
public class FocusTestSuite
{
}
