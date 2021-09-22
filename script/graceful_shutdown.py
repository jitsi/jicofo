#!/usr/bin/env python
# -*- coding: utf-8 -*-
from optparse import OptionParser

import sys
import logging
import time

import sleekxmpp
from sleekxmpp import Iq
from sleekxmpp.exceptions import StanzaError
from sleekxmpp.xmlstream import register_stanza_plugin, ElementBase

# Global variable for storing exit code:
# 0 - focus shutdown OK
# 1 - unexpected error occurred
exitCode = 0

# Python versions before 3.0 do not use UTF-8 encoding
# by default. To ensure that Unicode is handled properly
# throughout SleekXMPP, we will set the default encoding
# ourselves to UTF-8.
if sys.version_info < (3, 0):
    from sleekxmpp.util.misc_ops import setdefaultencoding
    setdefaultencoding('utf8')
else:
    raw_input = input


def get_conferences(stats_xml):
    for stat in stats_xml.findall('{http://jitsi.org/protocol/colibri}stat'):
        name = stat.get('name')
        value = stat.get('value')
        if name == 'conferences':
            return value
    return -1


class ColibriStats(ElementBase):

    name = 'stats'

    namespace = 'http://jitsi.org/protocol/colibri'

    plugin_attrib = 'colibri-stats'


class GracefulShutdown(ElementBase):

    name = 'graceful-shutdown'

    namespace = 'http://jitsi.org/protocol/colibri'

    plugin_attrib = 'graceful-shutdown'


class FocusShutdownUserBot(sleekxmpp.ClientXMPP):

    def __init__(self, jid, password, focus):
        sleekxmpp.ClientXMPP.__init__(self, jid, password)

        self.focus = focus

        self.add_event_handler(
            "session_start", self.shutdown_focus, threaded=True)

        register_stanza_plugin(Iq, GracefulShutdown)
        register_stanza_plugin(Iq, ColibriStats)

    def create_request_stats_iq(self):
        statsIq = self.Iq()
        statsIq['to'] = self.focus
        statsIq['type'] = 'get'
        statsIq.set_payload(ColibriStats())
        return statsIq

    def shutdown_focus(self, event):

        shutdown_accepted = False

        iq = self.Iq()
        iq['to'] = self.focus
        iq['type'] = 'set'
        iq.set_payload(GracefulShutdown())

        try:
            resp = iq.send()
            if resp['type'] == 'result':
                logging.info('Shutdown accepted')
                shutdown_accepted = True
                while True:
                    stats_iq = self.create_request_stats_iq()
                    resp = stats_iq.send()
                    stats = resp['colibri-stats']
                    conf_count = get_conferences(stats)
                    logging.info(
                        'There are ' + str(conf_count) + ' conferences in '
                                                      'progress')
                    if conf_count == '0':
                        break
                    else:
                        time.sleep(10)
                logging.info('End of shutdown procedure')
                # The wait=True delays the disconnect until the queue
                # of stanzas to be sent becomes empty.
                self.disconnect(wait=True)
        except StanzaError as error:
            if not shutdown_accepted and\
                            error.etype != 'wait' and\
                            error.condition != 'service-unavailable':
                global exitCode
                exitCode = 1
                logging.error('There was an error sending shutdown request.')
            self.disconnect(wait=True)


if __name__ == '__main__':
    # Setup the command line arguments.
    optp = OptionParser()

    # Output verbosity options.
    optp.add_option('-q', '--quiet', help='set logging to ERROR',
                    action='store_const', dest='loglevel',
                    const=logging.ERROR, default=logging.INFO)
    optp.add_option('-d', '--debug', help='set logging to DEBUG',
                    action='store_const', dest='loglevel',
                    const=logging.DEBUG, default=logging.INFO)
    optp.add_option('-v', '--verbose', help='set logging to COMM',
                    action='store_const', dest='loglevel',
                    const=5, default=logging.INFO)
    # JID and password options.
    optp.add_option("-s", "--server", dest="server",
                    help="JID to use")
    optp.add_option("-j", "--jid", dest="jid",
                    help="JID to use")
    optp.add_option("-p", "--password", dest="password",
                    help="password to use")
    optp.add_option("-f", "--focus", dest="focus",
                    help="JID of the focus component")
    opts, args = optp.parse_args()

    # Setup logging.
    logging.basicConfig(level=opts.loglevel,
                        format='%(levelname)-8s %(message)s')
    if opts.server is None:
        sys.stderr.write("No XMPP server name specified")
        exit(1)
    if opts.jid is None:
        sys.stderr.write("No user JID specified")
        exit(1)
    if opts.password is None:
        sys.stderr.write("No user password specified")
        exit(1)
    if opts.focus is None:
        sys.stderr.write("No focus JID specified")
        exit(1)

    xmpp = FocusShutdownUserBot(opts.jid, opts.password, opts.focus)

    if xmpp.connect(address=(opts.server, 5222)):
        xmpp.process(block=True)
        logging.info("Done")
    else:
        logging.error("Unable to connect.")

    exit(exitCode)
