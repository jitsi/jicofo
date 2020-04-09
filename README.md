# Jicofo

JItsi COnference FOcus is a server side focus component used in [Jitsi Meet]
 conferences.

[Jitsi Meet]: https://github.com/jitsi/jitsi-meet

## Overview

Conference focus is mandatory component of Jitsi Meet conferencing system next to the videobridge. It is responsible for managing media sessions between each of the participants and the videobridge. Whenever new conference is about to start an IQ is sent to the component to allocate new focus instance. After that special focus participant joins Multi User Chat room. It will be creating Jingle session between Jitsi videobridge and the participant. Although the session in terms of XMPP is between focus user and participant the media will flow between participant and the videobridge. That's because focus user will allocate Colibri channels on the bridge and use them as it's own Jingle transport.

## Quick install (from the start)

To start quickly with Jicofo it is recomended to install Jitsi Meet using [quick install] instruction which should install and configure 'jicofo' debian package next to 'jitsi-meet'.

[quick install]: https://github.com/jitsi/jitsi-meet/blob/master/doc/quick-install.md

## Download

You can download Debian/Ubuntu binaries:
* [stable](https://download.jitsi.org/stable/) ([instructions](https://jitsi.org/downloads/ubuntu-debian-installations-instructions/))
* [testing](https://download.jitsi.org/testing/) ([instructions](https://jitsi.org/downloads/ubuntu-debian-installations-instructions-for-testing/))
* [nightly](https://download.jitsi.org/unstable/) ([instructions](https://jitsi.org/downloads/ubuntu-debian-installations-instructions-nightly/))

## Manual Prosody configuration

Jicofo requires special 'owner' permissions in XMPP Multi User Chat to manage user roles. Because of that it needs adminsitrator credentials to start. By default Jitsi Meet uses XMPP domain with anonymous login method(jitsi.example.com), so additional VirtualHost has to be added to Prosody configuration(etc\prosody\prosody.cfg.lua):
```
VirtualHost "auth.jitsi.example.com"
    authentication = "internal_plain"
```
Next step is to create admin user that will be used by Jicofo to log in:
```
sudo prosodyctl register focus auth.jitsi.example.com focuspassword
```
Include focus user as one of server admins:
```
admins = { focus@auth.jitsi.example.com }
```
Add XMPP focus component:
```
Component "focus.jitsi.exmaple.com"
    component_secret="focus_secret"
```
Restart Prosody:
```
sudo prosodyctl restart
```
If we use 'focus.jitsi.example.com' where 'jitsi.example.com' is our main domain we don't need to modify config.js in Jitsi Meet. Application will try to add 'focus' prefix to our domain and find focus component there. To specify different name for focus component you need to modify config.js file in Jitsi Meet. Assuming that we want to use 'special_focus.jitsi.example.com' then config.js should look like following:
```
var config = {
    hosts: {
        domain: 'jitsi.example.com',
        muc: 'conference.jitsi.example.com',
        bridge: 'jitsi-videobridge.jitsi.example.com',
        focus: 'special_focus.jitsi.example.com'
    },
    ...
```

## Running Jicofo from distribution package

1. Build distributon package using ant target for your OS: "dist.lin", "dist.lin64", "dist.macosx", "dist.win" or "dist.win64"
2. Packge will be placed in 'dist/{os-name}' folder.
3. Extract distribution package to the folder of your choice.
4. Assuming Prosody has been configured using "Manual configuration for Prosody" 'jicofo' run script should be executed with following arguments:
```
    ./jicofo.sh --domain=jitsi.exmaple.com --secret=focus_secret --user_domain=auth.jitsi.example.com --user_name=focus --user_password=focuspassword
```

## Run arguments descripton
- --domain=DOMAIN sets the XMPP domain
- --host=HOST sets the hostname of the XMPP server (default: --domain, if --domain is set, localhost otherwise)
- --port=PORT sets the port of the XMPP server (default: 5347)
- --subdomain=SUBDOMAIN sets the sub-domain used to bind focus XMPP component (default: focus)
- --secret=SECRET sets the shared secret used to authenticate focus component to the XMPP server
- --user_domain=DOMAIN specifies the name of XMPP domain used by the focus user to login
- --user_name=USERNAME specifies the username used by the focus XMPP user to login. (default: focus@user_domain)
- --user_password=PASSWORD specifies the password used by focus XMPP user to login. If not provided then focus user will use anonymous authentication method

## Secure domain

It is possible to allow only authenticated users for creating new conference
rooms. Whenever new room is about to be created Jitsi Meet will prompt for
user name and password. After room is created others will be able to join
from anonymous domain. Here's what has to be configured:

1 In Prosody:

(If you have installed jitsi-meet from the Debian package, these changes should be made in /etc/prosody/conf.avail/[your-hostname].cfg.lua)

 a) Enable authentication on your main domain:<br/>
 ```
 VirtualHost "jitsi-meet.example.com"
     authentication = "internal_plain"
 ```
 b) Add new virtual host with anonymous login method for guests:<br/>
 ```
 VirtualHost "guest.jitsi-meet.example.com"
     authentication = "anonymous"
     c2s_require_encryption = false
 ```
(Note that guest.jitsi-meet.example.com is internal to jitsi, and you do not need to (and should not) create a DNS record for it, or generate an SSL/TLS certificate, or do any web server configuration.)

2 In Jitsi Meet config.js configure 'anonymousdomain':<br/>

(If you have installed jitsi-meet from the Debian package, these changes should be made in /etc/jitsi/meet/[your-hostname]-config.js)

```
var config = {
    hosts: {
            domain: 'jitsi-meet.example.com',
            anonymousdomain: 'guest.jitsi-meet.example.com',
            ...
        },
        ...
}
```
3 When running Jicofo specify your main domain in additional configuration
property. Jicofo will accept conference allocation requests only from
authenticated domain.
```
-Dorg.jitsi.jicofo.auth.URL=XMPP:jitsi-meet.example.com
```

If you have Jicofo installed from the Debian package (either explicitly or by installing jitsi-meet) this should go directly to **/etc/jitsi/jicofo/sip-communicator.properties** file:
```
org.jitsi.jicofo.auth.URL=XMPP:jitsi-meet.example.com
```
You will need to restart jicofo.  Try 
```
service jicofo restart
```

4 To create users use the command:
```
prosodyctl register <username> jitsi-meet.example.com <password>
```

5 If you are using jigasi:

a) Set jigasi to authenticate by editing the following lines in sip-communicator.properties.

If you have jigasi installed from the Debian package this should go directly to
**/etc/jitsi/jigasi/sip-communicator.properties**

org.jitsi.jigasi.xmpp.acc.USER_ID=SOME_USER@SOME_DOMAIN
org.jitsi.jigasi.xmpp.acc.PASS=SOME_PASS
org.jitsi.jigasi.xmpp.acc.ANONYMOUS_AUTH=false

The password is the actual plaintext password, not a base64 encoding.

b) If you experience problems with a certificate chain, you may also need to uncomment the following line, also in sip-communicator.properties:

net.java.sip.communicator.service.gui.ALWAYS_TRUST_MODE_ENABLED=true

Note that this should only be used for testing/debugging purposes, or in controlled environments. If you confirm that this is the problem, you should then solve it in another way (e.g. get a signed certificate for prosody, or add the particular certificate to jigasi’s trust store).


## Certificates
Jicofo uses an XMPP user connection (on port 5222 by default), and since the
upgrade to smack4 it verifies the server's certificate. In a default
installation the debian installation scripts take care of generating a
self-signed certificate and adding it to the keystore.

For situations in which the certificate is not trusted you can add it to the
store by:

### On Linux
```
sudo cp cert.pem /usr/local/share/ca-certificates/ 
sudo update-ca-certificates
```

### On MacOS X
On Mac java uses its own keystore, so adding the certificate to the system one
does not work. Add it to the java keystore with:
```
sudo keytool -importcert -file cert.pem -keystore /Library/Java//JavaVirtualMachines/jdk1.8.0_25.jdk/Contents/Home/jre/lib/security/cacerts
```

Note that if the XMPP server you are connecting to is a prosody instance
configured with the jitsi-meet scripts, then you can find the certificate in:
```
/var/lib/prosody/$JICOFO_AUTH_DOMAIN.crt 
```

