# 
Conference-request refers to an initial exchange between a client and jicofo, which happens before the client joins
the conference MUC. Its purposes are to:
1. Notify jicofo that it should join a certain MUC.
2. Have the client block until jicofo has created/joined the MUC (in case the client is not allowed to create a MUC).
3. Allow the first client in a conference to specify certain conference-wide options to jicofo.
4. Allow jicofo to redirect the client to a visitor node.
5. Allow external authentication with an XMPP domain or [Shibboleth](./shibboleth.md)

# Format
The request and response share a similar format. 

## HTTP format
For HTTP, the conference request is encoded as JSON and POSTed to /conference-request/v1. Note that authentication is
not supported over HTTP due to the lack of an associated JID. 

The reason HTTP support was added is to prevent unnecessary connections to prosody when visitor redirection is used 
(i.e. a visitor sends an HTTP request, then logs into the XMPP server specified in the response)

### Example request
```js
{
    "room": "conferenceName@example.com", // The JID of the conference
    "properties": {
        "rtcstatsEnabled": true
    }
}
```
### Example response
```js
{
    "ready": true, // Jicofo has joined the room and the client can proceed.
    "focusJid": "jicofo@v1.example.com", // Indicates jicofo's JID, which can be trusted by the client
    "vnode": "v1", // Redirect to a visitor node with id "v1"
    "properties": {
        "sipGatewayEnabled": true // Signal jigasi support
    }
}
```

## XMPP format
For XMPP, the conference request is encoded in a 
[ConferenceIq](https://github.com/jitsi/jitsi-xmpp-extensions/blob/master/src/main/java/org/jitsi/xmpp/extensions/jitsimeet/ConferenceIq.java).

### Example request
```xml
<iq to='client@example.com' from='jicofo@example.com' type='set'>
    <conference xmlns='http://jitsi.org/protocol/focus' room='conferenceName@example.com'>
        <property name='rtcstatsEnabled' value='true'/>
    </conference>
</iq>
```

### Example response
```xml
<iq to='jicofo@example.com' from='client@example.com' type='set'>
    <conference xmlns='http://jitsi.org/protocol/focus' ready='true' focusJid='jicofo@v1.example.com' vnode='v1'>
        <property name='sipGatewayEnabled' value='true'/>"
    </conference>
</iq>
```
