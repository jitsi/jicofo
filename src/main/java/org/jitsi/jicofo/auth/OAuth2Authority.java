package org.jitsi.jicofo.auth;

import com.google.api.client.googleapis.auth.oauth2.*;

import com.google.api.client.http.*;
import com.google.api.client.http.apache.*;
import com.google.api.client.json.*;
import com.google.api.client.json.jackson2.*;
import com.google.api.services.oauth2.*;
import com.google.api.services.youtube.*;
import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jivesoftware.smack.packet.*;

import java.io.*;
import java.util.*;

/**
 * Created by pdomas on 20/06/16.
 */
public class OAuth2Authority
    extends AbstractAuthAuthority
    implements AuthenticationAuthority
{
    /**
     * Configure your CLIENT ID here.
     */
    final static String CLIENT_ID = "10174444445-cn4444444444u.apps.googleusercontent.com";

    /**
     * Configure your client secret here.
     */
    final static String CLIENT_SECRET = "UT222222ny-H1111111232323";

    final static String REDIRECT_URL = "http://localhost:8888/login";

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

        // Check for invalid session
        IQ error = verifySession(query);
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
                    getScopes()).build();

        GoogleAuthorizationCodeRequestUrl authCodeReqURL
            = flow.newAuthorizationUrl();

        authCodeReqURL.setRedirectUri(REDIRECT_URL);
        String state = "room=" + roomName;
        state += "&machineUID=" + machineUID;
        state += "&close=" + popup;
        authCodeReqURL.setState(state);

        return authCodeReqURL.build();
    }

    public boolean isExternal()
    {
        return true;
    }
}
