# Shibboleth authentication

Jicofo supports [Shibboleth] authentication method which allows to take advantage
of federated identity solution. When this mode is enabled Jicofo will allow only
authenticated users to create new conference rooms. Also 'moderator' role will
be granted to every authenticated user.

[Shibboleth]: https://shibboleth.net/

Whenever room URL is visited, the app will contact Jicofo and ask to create MUC
room. If the room exists user will be allowed to enter the room immediately, but
it will not have 'moderator' role. Otherwise Jicofo will return 'not-authorized'
response and ask the user to authenticate.

In order to authenticate the user is redirected to special 'login location'
which is protected by Shibboleth. It means that valid Shibboleth session is
required in order to visit it. So whenever user tries to visit 'login location'
and there is no valid Shibboleth session it will be redirected to Shibboleth
login page for authentication. After that the user is taken back to Jicofo our
'login location' and is allowed to access it this time.

Under 'login location' there is special authentication servlet which runs inside
of the Jicofo. Because the location provides Shibboleth session, server will
inject into the request additional headers or attributes(depending on deployment
type). This attributes will tell Jicofo which user is logged-in(if any). Jicofo
will generate session-id bound to that user and return in to the user in HTTP
response. This session-id is considered secret and known only to the client and
Jicofo. It is used to authorize all future requests.

Once user has session-id it is redirected again to the room URL. This time it
includes in the request the session-id. Jicofo will authenticate user's
connection JID with Shibboleth user bound to the session. It will create the
MUC room and allow other waiting users to enter it.

Users who have entered without authentication still can login during the
conference. In the toolbar there will be "login" button available which
will open 'login location' in a popup. After successful login user will get
promoted to 'moderator' role and the popup will close. The session will be
valid for future requests until user explicitly logs out using the logout
button. Eventually session will expire after few days of inactivity.

# Configuration

### Glossary

**Nginx** - HTTP server used in our deployment

**Prosody** - XMPP server used in our deplyoment

**Shibboleth SP(Service Provider)** - service integrated with HTTP server in
order to provide Shibboleth authentication method to web applications. We're
going to use it together with Nginx.

**Shibboleth IdP(Identity Provider)** - provides user identity to Shibboleth
SP. That's the place where user enters his username and password. Depending on
exact SP configuration user may be allowed to select from multiple IdPs during
login (federation).

**Authentication servlet** - this is Jetty servlet embedded in Jicofo.
Authenticates users based on Shibboleth attributes provided in HTTP request and
returns the session-id.

**Supervisor** - utility used to integrate Shibboleth SP with Nginx through
fast-cgi

### Install Jitsi-Meet

First step is about installing jitsi-meet using [quick-start guide]. Once we're
done we have basic installation up and running. Now we want to add Shibboleth
authentication to it.

[quick-start guide]: https://github.com/jitsi/jitsi-meet/blob/master/doc/quick-install.md

### Patch Prosody [optional]

In order to have jitsi-meet system secure MUC room creation has to be restricted
to 'admins' in Prosody config. Obviously Jicofo user must have admin permissions
on the server, but this should be already done by jitsi-meet Debian package
install. Depending on Prosody version we might need to fix a [bug], by applying
a patch from the thread.

[bug] https://code.google.com/p/lxmppd/issues/detail?id=458

### Install Shibboleth SP with fast-cgi support [Ubuntu/Debian]

We need to install Shibboleth SP with fast-cgi support and integrate it with
Nginx. This guide is based on original 'nginx-http-shibboleth' module
[description] where you can find lots of useful information.

[description]: https://github.com/nginx-shib/nginx-http-shibboleth/blob/master/CONFIG.rst

Assuming we're running Ubuntu we need to download and install Shibboleth SP
packages manually in the following order:

1. [libmemcached11]
2. [libodbc1]
3. [shibboleth-sp2-common]
4. [libshibsp6]
5. [libshibsp-plugins]
6. [shibboleth-sp2-utils]

[libmemcached11]: https://packages.debian.org/sid/libmemcached11

[libodbc1]: https://packages.debian.org/sid/libodbc1

[shibboleth-sp2-common]: https://packages.debian.org/sid/shibboleth-sp2-common

[libshibsp6]: https://packages.debian.org/sid/libshibsp6

[libshibsp-plugins]: https://packages.debian.org/sid/libshibsp-plugins

[shibboleth-sp2-utils]: https://packages.debian.org/sid/shibboleth-sp2-utils

At the end we should have:

a) **/etc/shibboleth/** directory that contains Shibboleth SP configuration files

b) **shibd** deamon which can be started using 'sudo service shibd start'

c) **/usr/lib/x86_64-linux-gnu/shibboleth/** directory which contains
'shibauthorizer' and 'shibresponder'. Those are fast-cgi executables required
for Nginx integration.

If one of the above is missing it means that something went wrong or this guide
is incorrect :P

### Install and configure Supervisor

Install Supervisor utility by running:

```
sudo apt-get install supervisor
```

Create configuration file:

```
sudo touch /etc/supervisor/conf.d/shib.conf
```

Edit **/etc/supervisor/conf.d/shib.conf** file:

```
[fcgi-program:shibauthorizer]
command=/usr/lib/x86_64-linux-gnu/shibboleth/shibauthorizer
socket=unix:///opt/shibboleth/shibauthorizer.sock
socket_owner=_shibd:_shibd
socket_mode=0666
user=_shibd
stdout_logfile=/var/log/supervisor/shibauthorizer.log
stderr_logfile=/var/log/supervisor/shibauthorizer.error.log
```
```
[fcgi-program:shibresponder]
command=/usr/lib/x86_64-linux-gnu/shibboleth/shibresponder
socket=unix:///opt/shibboleth/shibresponder.sock
socket_owner=_shibd:_shibd
socket_mode=0666
user=_shibd
stdout_logfile=/var/log/supervisor/shibresponder.log
stderr_logfile=/var/log/supervisor/shibresponder.error.log
```

Restart Supervisor:

```
sudo service supervisor restart
```

After restart it should create two UNIX sockets owned by **\_shibd** user:

```
unix:///opt/shibboleth/shibauthorizer.sock
unix:///opt/shibboleth/shibresponder.sock
```

Also error logs mentioned in the config should be empty if everything works ok.

[TODO: add description about making common user group for nginx and shibboleth
workers, so that sockets can be set to 0660 mode]

### Build Nginx from sources with fast-cgi and additional modules

In order to make Nginx work with Shibboleth SP external modules
'nginx-http-shibboleth' and 'headers-more' are required. Unfortunately it's not
possible to add them on runtime, so we need to build Nginx from sources. By
installing it from sources we'll overwrite Debian package installation which
came with jitsi-meet, but this way we can take advantage of
**/etc/init.d/nginx** script and initial configuration.

Download 'nginx-http-shibboleth' external module:

```
git clone https://github.com/nginx-shib/nginx-http-shibboleth
```

Download and unzip 'headers-more' external module:

```
wget https://github.com/openresty/headers-more-nginx-module/archive/v0.25.zip
unzip v0.25.zip
```

Obtain and [build Nginx]

[build Nginx]: http://wiki.nginx.org/Install#Building_Nginx_From_Source

```
wget http://nginx.org/download/nginx-1.6.2.tar.gz
tar -xzvf nginx-1.6.2.tar.gz
```

Here remember to replace *{modules location}* with the path to external modules:

```
cd nginx-1.6.2
./configure --sbin-path=/usr/sbin/nginx \
 --conf-path=/etc/nginx/nginx.conf \
 --pid-path=/run/nginx.pid \
 --error-log-path=/var/log/nginx/error.log \
 --http-log-path=/var/log/nginx/access.log \
 --with-http_ssl_module \
 --with-ipv6 \
 --add-module=/{modules location}/nginx-http-shibboleth \
 --add-module=/{modules location}/headers-more-nginx-module-0.25
make
sudo make install
```

### Configure Nginx

Open config for our jitsi-meet host
**/etc/nginx/sites-available/{our_host}.conf**. After *BOSH* config append
Shibboleth configuration:

```
# Shibboleth

location = /shibauthorizer {
  internal;
  include fastcgi_params;
  fastcgi_pass unix:/opt/shibboleth/shibauthorizer.sock;
}

location /Shibboleth.sso {
  include fastcgi_params;
  fastcgi_pass unix:/opt/shibboleth/shibresponder.sock;
}

location /shibboleth-sp {
  alias /usr/share/shibboleth/;
}

# Login location where Jicofo servlet is running

location /login {
  more_clear_input_headers 'Variable-*' 'Shib-*' 'Remote-User' 'REMOTE_USER' 'Auth-
Type' 'AUTH_TYPE';
  more_clear_input_headers 'displayName' 'mail' 'persistent-id';
  shib_request /shibauthorizer;
  proxy_pass http://127.0.0.1:8888;
}
```

### Configure Shibboleth SP

[Wiki]: https://wiki.shibboleth.net/confluence/display/SHIB2/NativeSPConfiguration

Before we can use Shibboleth, regular SP configuration is required, but it's out
of the scope for this document. More info can be found on Shibboleth [Wiki].
Assuming that basic SP configuration is working we need to add config for Jicofo
'login location'. In order to do that edit **/etc/shibboleth/shibboleth2.xml**.
Before **\<ApplicationDefaults\>** element append following config(replace
*{our host}* with jitsi-meet hostname):

```
<RequestMapper type="XML">
    <RequestMap>
        <Host name="{our_host}"
              authType="shibboleth"
              requireSession="true">
            <Path name="/login" />
        </Host>
    </RequestMap>
</RequestMapper>
```

### Enable Shibboleth servlet in Jicofo

Edit **/etc/jitsi/jicofo/sip-communicator.properties** file
and add following lines:

```
org.jitsi.jicofo.auth.URL=shibboleth:default
org.jitsi.jicofo.auth.LOGOUT_URL=shibboleth:default
```

Restart services: *shibd*, *jicofo*, *nginx*. After visiting jitsi-meet URL the
user should be asked for authentication.
