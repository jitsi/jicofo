# Audio translation

## Requests come from Prosody

Prosody owns the `audioTranslationRequests` map in the room metadata. `mod_audio_translation_component.lua` (in jitsi-meet) keeps the subscriptions of each receiver. It sends jicofo the union of them: `{ senderId: [lang, ...] }`. When a member leaves, the module removes the subscriptions of that member. A sender stays in the map only while at least one member in the room requests a translation of that sender.

- Do not add a separate record of requesters in jicofo. `ConferenceTranslationManager.setRequests` uses the map as Prosody sends it.
- A non-empty request for a sender means that at least one member uses the translation.
