FROM openjdk:7-alpine
COPY jicofo ./jicofo
CMD ./jicofo/jicofo.sh --host=${XMPP_HOST} --domain=${DOMAIN} --secret=${SECRET} --user_domain=auth.${DOMAIN} --user_name=${USERNAME} --user_password=${SECRET3}
