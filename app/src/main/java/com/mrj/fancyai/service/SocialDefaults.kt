package com.mrj.fancyai.service

/**
 * Default catalogue of Rebbit subreddits a character may post to. These are identifiers only;
 * the actual post content is generated at runtime by the user's chosen model based on the
 * character's persona/memory and the chosen subreddit. The list mixes general-interest topics
 * (diversity) with the adult ones from the legacy app — the whole Rebbit app is gated behind
 * the global NSFW switch, and users can enable/disable individual subreddits in Settings.
 */
object SocialDefaults {
    val SUBREDDITS: List<String> = listOf(
        // General-interest (diversity)
        "r/selfie", "r/photography", "r/fitness", "r/cosplay", "r/fashion",
        "r/art", "r/food", "r/travel", "r/gaming", "r/aww", "r/makeup",
        "r/outfits", "r/streetwear", "r/hiking", "r/books", "r/music",
        // Adult (only reachable when NSFW is enabled)
        "r/gonewild", "r/realgirls", "r/legs", "r/ass", "r/lingerie",
        "r/bikinis", "r/curvy", "r/fitgirls", "r/feet", "r/stockings",
        "r/mirror", "r/shower", "r/nsfw", "r/petite", "r/thick"
    )
}
