/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc
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
package org.jitsi.jicofo.util

import org.osgi.framework.BundleContext

/**
 * Gets an OSGi service registered in a specific <tt>BundleContext</tt> by
 * its <tt>Class</tt>
 *
 * @param <T> the very type of the OSGi service to get
 * @param bundleContext the <tt>BundleContext</tt> in which the service to
 * get has been registered
 * @param serviceClass the <tt>Class</tt> with which the service to get has
 * been registered in the <tt>bundleContext</tt>
 * @return the OSGi service registered in <tt>bundleContext</tt> with the
 * specified <tt>serviceClass</tt> if such a service exists there;
 * otherwise, <tt>null</tt>
</T> */
fun <T> getService(bundleContext: BundleContext?, serviceClass: Class<T>?): T? {
    return bundleContext?.getService(bundleContext.getServiceReference(serviceClass))
}