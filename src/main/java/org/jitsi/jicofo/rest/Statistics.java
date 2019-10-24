package org.jitsi.jicofo.rest;

import org.jitsi.jicofo.*;
import org.jitsi.jicofo.util.*;
import org.json.simple.*;

import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;

@Path("/stats")
public class Statistics
{
    /**
     * The number of buckets to use for conference sizes.
     */
    private static final int CONFERENCE_SIZE_BUCKETS = 22;

    @Inject
    protected FocusManagerProvider focusManagerProvider;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String getStats()
    {
        FocusManager focusManager = focusManagerProvider.get();
        JSONObject json = new JSONObject();

        int conferenceCount = focusManager.getConferenceCount();
        json.put(CONFERENCES, conferenceCount);

        int[] conferenceSizes = new int[CONFERENCE_SIZE_BUCKETS];
        int largestConferenceSize = 0;
        for (JitsiMeetConference conference : focusManager.getConferences())
        {
            int confSize = conference.getParticipantCount();
            // getParticipantCount only includes endpoints with allocated media channels,
            // so if a single participant is waiting in a meeting they wouldn't
            // be counted.  In stats, calling this a conference with size 0
            // would be misleading, so we add 1 in this case to properly show
            // it as a conference of size 1.  (If there really weren't any
            // participants in there at all, the conference wouldn't have
            // existed in the first place).
            if (confSize == 0)
            {
                confSize = 1;
            }
            if (confSize > largestConferenceSize)
            {
                largestConferenceSize = confSize;
            }
            int conferenceSizeIndex = confSize < conferenceSizes.length
                ? confSize
                : conferenceSizes.length - 1;
            conferenceSizes[conferenceSizeIndex]++;
        }

        JSONArray conferenceSizesJson = new JSONArray();
        for (int size : conferenceSizes)
        {
            conferenceSizesJson.add(size);
        }
        json.put(LARGEST_CONFERENCE, largestConferenceSize);
        json.put(CONFERENCE_SIZES, conferenceSizesJson);

        return json.toJSONString();
    }
}
