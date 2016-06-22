package org.jitsi.jicofo.auth;

import com.google.api.client.auth.oauth2.*;
import com.google.api.client.googleapis.auth.oauth2.*;

import com.google.api.client.http.*;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.apache.*;
import com.google.api.client.json.*;
import com.google.api.client.json.jackson2.*;
import com.google.api.services.oauth2.*;
import com.google.api.services.youtube.*;
import net.java.sip.communicator.util.*;
import org.apache.http.*;
import org.apache.http.client.entity.*;
import org.apache.http.client.methods.*;
import org.apache.http.message.*;
import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.impl.reservation.rest.*;
import org.jivesoftware.smack.packet.*;

import java.io.*;
import java.security.*;
import java.util.*;

/**
 * Created by pdomas on 20/06/16.
 */
public class OAuth2Authority
    extends AbstractAuthAuthority
    implements AuthenticationAuthority
{
    private final static Logger logger = Logger.getLogger(OAuth2Authority.class);

    /**
     * Configure your CLIENT ID here.
     */
    final static String CLIENT_ID = "10174444445-cn4444444444u.apps.googleusercontent.com";

    /**
     * Configure your client secret here.
     */
    final static String CLIENT_SECRET = "UT222222ny-H1111111232323";

    final static String REDIRECT_URL = "http://pawel.jitsi.net/login.html";

    ApacheHttpTransport httpTransport;

    JacksonFactory jsonFactory;

    public OAuth2Authority()
    {
        httpTransport = new ApacheHttpTransport();
        jsonFactory = new JacksonFactory();
    }

    private Collection<String> getScopes()
    {
        List<String> scopes = new ArrayList<>();

        scopes.add(Oauth2Scopes.USERINFO_EMAIL);
        scopes.add(YouTubeScopes.YOUTUBE_UPLOAD);

        return scopes;
    }

    protected IQ processAuthLocked(ConferenceIq query, ConferenceIq response)
    {

        String room = query.getRoom();
        String peerJid = query.getFrom();

        String sessionId = query.getSessionId();
        AuthenticationSession session = getSession(sessionId);

        IQ error = verifyGAuthToken(query, sessionId);
        if (error != null)
        {
            return error;
        }

        // Check for invalid session
        error = verifySession(query);
        if (error != null)
        {
            return error;
        }

        // Authenticate JID with session
        if (session != null)
        {
            authenticateJidWithSession(session, peerJid, response);
        }

        return null;
    }

    private IQ verifyGAuthToken(IQ query, String sessionId)
    {
        logger.info("GAUTH_TOKEN: " + sessionId);
        HttpGet post = new HttpGet(baseUrl + "/conference");

        List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(1);
        Map<String, Object> jsonMap = conference.createJSonMap();

        for (Map.Entry<String, Object> entry : jsonMap.entrySet())
        {
            nameValuePairs.add(
                new BasicNameValuePair(
                    entry.getKey(), String.valueOf(entry.getValue())));
        }

        post.setEntity(new UrlEncodedFormEntity(nameValuePairs, "UTF8"));

        logger.info("Sending post: " + jsonMap);

        org.apache.http.HttpResponse response = null;

        try
        {
            response = client.execute(post);

            int statusCode = response.getStatusLine().getStatusCode();

            logger.info("STATUS CODE: " + statusCode);

            if (200 == statusCode || 201 == statusCode)
            {
                // OK
                readConferenceResponse(conference, response);

                return new ApiHandler.ApiResult(statusCode, conference);
            }
            else
            {
                ErrorResponse error = readErrorResponse(response);

                return new ApiHandler.ApiResult(statusCode, error);
            }
        }
        finally
        {
            if (response != null && response.getEntity() != null)
            {
                response.getEntity().consumeContent();
            }
        }
        return null;
    }

    private IQ verifyGAuthToken1(IQ query, String sessionId)
    {
        logger.info("GAUTH_TOKEN: " + sessionId);
        GoogleIdTokenVerifier verifier
            = new GoogleIdTokenVerifier.Builder(httpTransport, jsonFactory)
            .setAudience(Arrays.asList(CLIENT_ID))
            .build();
        try
        {
            logger.info("Verify token: " + verifier.verify(sessionId));
        }
        catch (GeneralSecurityException e)
        {
            logger.error("GSE", e);
            return IQ.createErrorResponse(query, new XMPPError(
                XMPPError.Condition.not_authorized));
        }
        catch (IOException e)
        {
            e.printStackTrace();
            return IQ.createErrorResponse(query, new XMPPError(
                XMPPError.Condition.interna_server_error));
        }
        return null;
    }


    String authenticateUser(String machineUID, String authIdentity,
                            String roomName,   Map<String, String> properties)
    {
        AuthenticationSession session
            = findSessionForIdentity(machineUID, authIdentity);

        if (session == null)
        {
            session = createNewSession(
                machineUID, authIdentity, roomName, properties);
        }

        return session.getSessionId();
    }

    protected String createLogoutUrl(String sessionId)
    {
        return null;
    }

    public String createLoginUrl(
        String machineUID, String peerFullJid, String roomName, boolean popup)
    {
        GoogleAuthorizationCodeFlow flow
            = new GoogleAuthorizationCodeFlow.Builder(
                    httpTransport, jsonFactory, CLIENT_ID, CLIENT_SECRET,
                    getScopes())
                    .setAccessType("online")
                    .build();

        GoogleAuthorizationCodeRequestUrl authCodeReqURL
            = flow.newAuthorizationUrl();

        authCodeReqURL.setRedirectUri(REDIRECT_URL);
        //String state = "room=" + roomName;
        //state += "&machineUID=" + machineUID;
        //state += "&close=" + popup;
        String state = "https://pawel.jitsi.net/" + roomName;
        authCodeReqURL.setState(state);

        return authCodeReqURL.build();
    }

    public boolean isExternal()
    {
        return false;
    }
}
