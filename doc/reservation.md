### Support for a reservation system over REST API

It is possible to connect Jicofo to external conference reservation system using
REST API. Before new Jitsi-meet conference is created reservation system will be
queried for room availability. The system is supposed to return positive or
negative response which also contains conference duration. Jicofo will enforce
conference duration and if the time limit is exceeded the conference will be
terminated. If any authentication system is enabled then user's identity will be
included in the reservation system query.

#### Enable reservation system

In order to enable reservation system URL base for REST API endpoint must be
 configured in the following property:

```
org.jitsi.impl.reservation.rest.BASE_URL=http://reservation.example.com
```

It can be either specified with `-Darg=value` when running from the
command line directly or in `/etc/jitsi/jicofo/sip-communicator.properties`
in case Jicofo has been installed from our [Debian package].

[Debian package]: https://github.com/jitsi/jitsi-meet/blob/master/doc/quick-install.md

URL base is used to construct request URL. Currently only `'/conference'`
endpoint is supported, so all request will go to:

```
http://reservation.example.com/conference
```

#### Call flow

##### Notes

All API calls use following date and time format:

`yyyy-MM-dd'T'HH:mm:ss.SSSX` - more info can be found in
`SimpleDateFormat` [JavaDoc]

[JavaDoc]: https://docs.oracle.com/javase/6/docs/api/java/text/SimpleDateFormat.html

##### Conference allocation

When the first user joins MUC room(Jitsi-meet URL is opened) `HTTP POST`
request is sent to `'/conference'` endpoint with the following parameters
included:

* `name (string)` - short name of the conference room(not full MUC address).
* `start_time (string)` - conference start date and time
* `mail_owner (string)` - if authentication system is enabled this field will
 contain user's identity. It that case it will not be possible to create new
 conference room without authenticating.

Then reservation system is expected to respond with one of the following
responses:

###### HTTP 200 or 201 Conference created successfully

In HTTP response JSON object is expected. It should contain conference `id`
assigned by the system and `duration` measured in seconds. Sample response body:

```
{
  'id': 364758328,
  'name': 'conference1234',
  'mail_owner': 'user@server.com',
  'start_time': '2048-04-20T17:55:12.000Z',
  'duration': 900000
}
```

###### HTTP 409 - Conference already exists

This is to recover from previous Jicofo failure. If for some reason it was
restarted and will try to create the room again this response informs Jicofo
that the conference room exists already. It is expected to contain
`conflict_id` in JSON response body:

```
{
  'conflict_id': 364758328
}
```

Jicofo will use `HTTP GET` to fetch info about conflicting conference for
given `conflict_id`. More infor about this request in "Reading conference info"
section.

###### HTTP 4xx

Other response codes will cause conference creation failure. JSON response
can contain `message` object which will be sent back to the client.

For example `user1` tries to start new conference by sending
`conference` IQ to Jicofo. System will reject the request.

Client -> Jicofo:

```
<iq from='client1@xmpp.com' to='jicofo.meet.com' type='set'>
  <conference xmlns='http://jitsi.org/protocol/focus' room='testroom1' />
</iq>
```

Jicofo -> Reservation system:

```
POST /conference HTTP/1.1
content-type:application/x-www-form-urlencoded;charset=utf-8
host: http://reservation.example.com
content-length: length

name=testroom1&start_time=2048-04-20T17%3A55%3A12.000Z&mail_owner=client1%40xmpp.com
```

Reservation system -> Jicofo:

```
HTTP/1.1 403 Forbidden
Content-Type: application/json; charset=utf-8
Content-Length: length

{
  'message': 'client1 is not allowed to create the room at this time'
}
```

Jicofo -> Client:

```
<iq from='jicofo.meet.com' to='client1@xmpp.com' type='error'>
  <error type='cancel'>
    <service-unavailable xmlns='urn:ietf:params:xml:ns:xmpp-stanzas' />
    <text xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'>
          client1 is not allowed to create the room at this time
    </text>
    <reservation-error xmlns='http://jitsi.org/protocol/focus' error-code='403'/>
  </error>
</iq>
```

Application can use `text` and `reservation-error` elements to
provide meaningful information to the user.

##### Reading conference info

In case of `409` response to `HTTP POST` request Jicofo will try
to read information about conflicting conference using `HTTP GET`
request to '/conference/{conflict_id}' endpoint. The response should provide all
information about the conference stored in the reservation system:

* `'id'`: conference identifier assigned by the reservation system
* `'name'`: conference room name
* `'mail_owner'`: identity of the user who has created the conference
* `'start_time'`: conference start date and time
* `'duration'`: scheduled conference duration in seconds

Sample response JSON body(contains the same info as `200 OK` to
`HTTP POST`):

```
{
  'id': 364758328,
  'name': 'conference1234',
  'mail_owner': 'user@server.com',
  'start_time': '2048-04-20T17:55:12.000Z',
  'duration': 900000
}
```

##### Deleting conference

Jicofo deletes conferences in the reservation system in two cases. First when
all users leave XMPP Multi User Chat room. Second when conference duration limit
is exceeded. In the latter case Jicofo will destroy XMPP MUC room and expire all
Colibri channels on the videobridge which will result in conference termination.
After MUC room is destroyed Jicofo sends `HTTP DELETE` request to
`'/conference/{id}'` endpoint where `{id}` is replaced with
conference identifier assigned by the reservation system.

```
DELETE /conference/364758328 HTTP/1.1
host: http://reservation.example.com
...
```
