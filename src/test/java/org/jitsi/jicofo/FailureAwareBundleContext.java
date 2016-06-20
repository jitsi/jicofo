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
package org.jitsi.jicofo;

import org.osgi.framework.*;

import java.io.*;
import java.util.*;

/**
 * A class is a wrapper for BundleContext which will make every call crash with
 * a <tt>RuntimeException</tt> once the failure mode has been turned on.
 * The purpose of that is to abort all tests after we have detected a deadlock.
 * Otherwise any tests started after the deadlock had failed will block
 * the main thread forever.
 *
 * @author Pawel Domas
 */
public class FailureAwareBundleContext
    implements BundleContext
{
    private final BundleContext bc;

    private String failureMessage;

    public FailureAwareBundleContext(BundleContext bc)
    {
        this.bc = bc;
    }

    public String getFailureMessage()
    {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage)
    {
        this.failureMessage = failureMessage;
    }

    private void assertNotFaulty()
    {
        if (failureMessage != null)
        {
            throw new RuntimeException(failureMessage);
        }
    }

    @Override
    public String getProperty(String s)
    {
        assertNotFaulty();

        return bc.getProperty(s);
    }

    @Override
    public Bundle getBundle()
    {
        assertNotFaulty();

        return bc.getBundle();
    }

    @Override
    public Bundle installBundle(String s, InputStream inputStream)
        throws BundleException
    {
        assertNotFaulty();

        return bc.installBundle(s, inputStream);
    }

    @Override
    public Bundle installBundle(String s)
        throws BundleException
    {
        assertNotFaulty();

        return bc.installBundle(s);
    }

    @Override
    public Bundle getBundle(long l)
    {
        assertNotFaulty();

        return bc.getBundle(l);
    }

    @Override
    public Bundle[] getBundles()
    {
        assertNotFaulty();

        return bc.getBundles();
    }

    @Override
    public void addServiceListener(ServiceListener serviceListener, String s)
        throws InvalidSyntaxException
    {
        assertNotFaulty();

        bc.addServiceListener(serviceListener, s);
    }

    @Override
    public void addServiceListener(ServiceListener serviceListener)
    {
        assertNotFaulty();

        bc.addServiceListener(serviceListener);
    }

    @Override
    public void removeServiceListener(ServiceListener serviceListener)
    {
        assertNotFaulty();

        bc.removeServiceListener(serviceListener);
    }

    @Override
    public void addBundleListener(BundleListener bundleListener)
    {
        assertNotFaulty();

        bc.addBundleListener(bundleListener);
    }

    @Override
    public void removeBundleListener(BundleListener bundleListener)
    {
        assertNotFaulty();

        bc.removeBundleListener(bundleListener);
    }

    @Override
    public void addFrameworkListener(FrameworkListener frameworkListener)
    {
        assertNotFaulty();

        bc.addFrameworkListener(frameworkListener);
    }

    @Override
    public void removeFrameworkListener(FrameworkListener frameworkListener)
    {
        assertNotFaulty();

        bc.removeFrameworkListener(frameworkListener);
    }

    @Override
    public ServiceRegistration<?> registerService(
        String[] strings, Object o, Dictionary<String, ?> dictionary)
    {
        assertNotFaulty();

        return bc.registerService(strings, o, dictionary);
    }

    @Override
    public ServiceRegistration<?> registerService(
        String s, Object o, Dictionary<String, ?> dictionary)
    {
        assertNotFaulty();

        return bc.registerService(s, o, dictionary);
    }

    @Override
    public <S> ServiceRegistration<S> registerService(
        Class<S> aClass, S s, Dictionary<String, ?> dictionary)
    {
        assertNotFaulty();

        return bc.registerService(aClass, s, dictionary);
    }

    @Override
    public ServiceReference<?>[] getServiceReferences(String s, String s1)
        throws InvalidSyntaxException
    {
        assertNotFaulty();

        return bc.getServiceReferences(s, s1);
    }

    @Override
    public ServiceReference<?>[] getAllServiceReferences(
        String s, String s1)
        throws InvalidSyntaxException
    {
        assertNotFaulty();

        return bc.getAllServiceReferences(s, s1);
    }

    @Override
    public ServiceReference<?> getServiceReference(String s)
    {
        assertNotFaulty();

        return bc.getServiceReference(s);
    }

    @Override
    public <S> ServiceReference<S> getServiceReference(Class<S> aClass)
    {
        assertNotFaulty();

        return bc.getServiceReference(aClass);
    }

    @Override
    public <S> Collection<ServiceReference<S>> getServiceReferences(
        Class<S> aClass, String s)
        throws InvalidSyntaxException
    {
        assertNotFaulty();

        return bc.getServiceReferences(aClass, s);
    }

    @Override
    public <S> S getService(ServiceReference<S> serviceReference)
    {
        assertNotFaulty();

        return bc.getService(serviceReference);
    }

    @Override
    public boolean ungetService(
        ServiceReference<?> serviceReference)
    {
        assertNotFaulty();

        return bc.ungetService(serviceReference);
    }

    @Override
    public File getDataFile(String s)
    {
        assertNotFaulty();

        return bc.getDataFile(s);
    }

    @Override
    public Filter createFilter(String s)
        throws InvalidSyntaxException
    {
        assertNotFaulty();

        return bc.createFilter(s);
    }

    @Override
    public Bundle getBundle(String s)
    {
        assertNotFaulty();

        return bc.getBundle(s);
    }
}
