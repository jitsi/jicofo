Jicofo
======

JItsi COnference FOcus is a server side focus component used in [Jitsi Meet]
 conferences.

[Jitsi Meet]: https://github.com/jitsi/jitsi-meet

Running Jicofo from distribution package
=====

1. Build distributon package using ant target for your OS: "dist.lin", "dist.lin64", "dist.macosx", "dist.win" or "dist.win64"
2. Packge will be placed in 'dist/{os-name}' folder.
3. Extract distribution package to the folder of your choice.
4. Execute 'jicofo' run script with following command line arguments:
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
