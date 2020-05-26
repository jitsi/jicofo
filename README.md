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

NB: SECRET and PASSWORD can alternatively be set via the environment variables JICOFO_SECRET and JICOFO_AUTH_PASSWORD respectively, which prevents them showing up in a process listing.

## Secure domain

This section has been moved to [The Handbook](https://jitsi.github.io/handbook/docs/devops-guide/secure-domain).


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

