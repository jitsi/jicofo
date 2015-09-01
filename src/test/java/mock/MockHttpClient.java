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
package mock;

import org.apache.http.*;
import org.apache.http.client.*;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.*;
import org.apache.http.conn.scheme.*;
import org.apache.http.impl.conn.*;
import org.apache.http.params.*;
import org.apache.http.protocol.*;

import java.util.*;
import java.io.IOException;

/**
 * @author Maksym Kulish
 */
public class MockHttpClient implements HttpClient
{

    private HttpResponse resp;
    private List<HttpResponse> responseList = null;
    private int currentResponseNumber = 0;

    public HttpParams getParams()
    {
        return new BasicHttpParams();
    }

    public MockHttpClient(HttpResponse response)
    {
        this.resp = response;
    }

    public MockHttpClient(List<HttpResponse> responses)
    {

        this.responseList = responses;

    }

    public ClientConnectionManager getConnectionManager()
    {
        SchemeRegistry registry = new SchemeRegistry();
        return new SingleClientConnManager(this.getParams(), registry);
    }

    public HttpResponse execute(HttpUriRequest var1) throws
            IOException, ClientProtocolException
    {
        HttpResponse response = null;
        if (this.responseList != null)
        {
            if (currentResponseNumber >= this.responseList.size())
            {
                currentResponseNumber = 0;
            }
            response = this.responseList.get(currentResponseNumber);
            currentResponseNumber++;
        }
        else
        {
            response = this.resp;
        }
        return response;
    }

    public HttpResponse execute(HttpUriRequest var1, HttpContext var2)
            throws IOException, ClientProtocolException
    {
        return null;
    }

    public HttpResponse execute(HttpHost var1, HttpRequest var2)
            throws IOException, ClientProtocolException
    {
        return null;
    }

    public HttpResponse execute(HttpHost var1, HttpRequest var2,
                                HttpContext var3)
            throws IOException, ClientProtocolException
    {
        return null;
    }

    public <T> T execute(HttpUriRequest var1, ResponseHandler<? extends T> var2)
            throws IOException, ClientProtocolException
    {
        return null;
    }

    public <T> T execute(HttpUriRequest var1,
                         ResponseHandler<? extends T> var2, HttpContext var3)
            throws IOException, ClientProtocolException
    {
        return null;
    }

    public <T> T execute(HttpHost var1, HttpRequest var2,
                         ResponseHandler<? extends T> var3)
            throws IOException, ClientProtocolException
    {
        return null;
    }

    public <T> T execute(HttpHost var1, HttpRequest var2,
                         ResponseHandler<? extends T> var3, HttpContext var4)
            throws IOException, ClientProtocolException
    {
        return null;
    }

}
