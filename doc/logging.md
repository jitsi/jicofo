# General
In order to allow clients to log events to an InfluxDB instance without
granting them direct access to the database, the server-side focus accepts 'log
request' messages from the participants in a conference. The authentication for
these messages is based on the MUC -- a client is allowed to log a message if
it is a member of the MUC.

The focus does not send any acknowledgement for log requests.

# Message format
The 'log request' messages use the format defined in XEP-0337, with certain
assumptions. 

The 'id' field is used to distinguish between different types of events. Only
a limited set of them are supported (currently only 'PeerConnectionStats').

The text content of the 'message' element is *always* assumed to be 
base64-encoded. If there is a tag with name 'deflated' and value 'true',
the text content of the 'message' element is compressed with zlib DEFLATE
(RFC1951) and then base64-encoded.

# Example message
The message to be logged in both of the following examples is 'PLEASELOGME':

### Not compressed
```xml
<log xmlns='urn:xmpp:eventlog' id='PeerConnectionStats'>
  <message>UExFQVNFTE9HTUU=</message>
</log>
```

### Compressed
```xml
<log xmlns='urn:xmpp:eventlog' id='PeerConnectionStats'>
  <message>C/BxdQx29fF393UFAA==</message>
  <tag name='deflated' value='true'/>
</log>
```

