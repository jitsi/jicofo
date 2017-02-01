Jicofo
======

JItsi COnference FOcus is a server side focus component used in [Jitsi Meet]
 conferences.

[Jitsi Meet]: https://github.com/jitsi/jitsi-meet

Overview
=====

Conference focus is mandatory component of Jitsi Meet conferencing system next to the videobridge. It is responsible for managing media sessions between each of the participants and the videobridge. Whenever new conference is about to start an IQ is sent to the component to allocate new focus instance. After that special focus participant joins Multi User Chat room. It will be creating Jingle session between Jitsi videobridge and the participant. Although the session in terms of XMPP is between focus user and participant the media will flow between participant and the videobridge. That's because focus user will allocate Colibri channels on the bridge and use them as it's own Jingle transport.

Quick install(from the start)
====

To start quickly with Jicofo it is recomended to install Jitsi Meet using [quick install] instruction which should install and configure 'jicofo' debian package next to 'jitsi-meet'.

[quick install]: https://github.com/jitsi/jitsi-meet/blob/master/doc/quick-install.md

Download
====

You can download Debian/Ubuntu binaries:
* [stable](https://download.jitsi.org/stable/) ([instructions](https://jitsi.org/Main/InstallJicofoDebianStableRepository))
* [testing](https://download.jitsi.org/testing/) ([instructions](https://jitsi.org/Main/InstallJicofoDebianTestingRepository))
* [nightly](https://download.jitsi.org/unstable/) ([instructions](https://jitsi.org/Main/InstallJicofoDebianNightlyRepository))

Old JavaScript client side focus replacement
=====

Migration from old JavaScript focus is the easiest when Jitsi Meet was installed from debian package [quick install]. It should be enough to update 'jitsi-meet' package which will ask you to install also 'jicofo' package:
```
apt-get update
apt-get install jitsi-meet
```

Manual configuration for Prosody
=====

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

Running Jicofo from distribution package
=====

1. Build distributon package using ant target for your OS: "dist.lin", "dist.lin64", "dist.macosx", "dist.win" or "dist.win64"
2. Packge will be placed in 'dist/{os-name}' folder.
3. Extract distribution package to the folder of your choice.
4. Assuming Prosody has been configured using "Manual configuration for Prosody" 'jicofo' run script should be executed with following arguments:
```
    ./jicofo.sh --domain=jitsi.exmaple.com --secret=focus_secret --user_domain=auth.jitsi.example.com --user_name=focus --user_password=focuspassword
```

Run arguments descripton
====
<ul>
<li>
--domain=DOMAIN sets the XMPP domain
</li>
<li>
--host=HOST sets the hostname of the XMPP server (default: --domain, if --domain is set, localhost otherwise)
</li>
<li>
--port=PORT sets the port of the XMPP server (default: 5347)
</li>
<li>
--subdomain=SUBDOMAIN sets the sub-domain used to bind focus XMPP component (default: focus)
</li>
<li>
--secret=SECRET sets the shared secret used to authenticate focus component to the XMPP server
</li>
<li>
--user_domain=DOMAIN specifies the name of XMPP domain used by the focus user to login
</li>
<li>
--user_name=USERNAME specifies the username used by the focus XMPP user to login. (default: focus@user_domain)
</li>
<li>
--user_password=PASSWORD specifies the password used by focus XMPP user to login. If not provided then focus user will use anonymous authentication method
</li>
</ul>

Secure domain
====

It is possible to allow only authenticated users for creating new conference
rooms. Whenever new room is about to be created Jitsi Meet will prompt for
user name and password. After room is created others will be able to join
from anonymous domain. Here's what has to be configured:

1 In Prosody:

 a) Enable authentication on your main domain:<br/>
 ```
 VirtualHost "jitsi-meet.example.com"
     authentication = "internal_plain"
 ```
 b) Add new virtual host with anonymous login method for quests:<br/>
 ```
 VirtualHost guest.jitsi-meet.example.com
     authentication = "anonymous"
 ```
2 In Jitsi Meet config.js configure 'anonymousdomain':<br/>
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

If you have Jicofo installed from the Debian package this should go directly to
<b>/etc/jitsi/jicofo/sip-communicator.properties</b> file:
```
org.jitsi.jicofo.auth.URL=XMPP:jitsi-meet.example.com
```
