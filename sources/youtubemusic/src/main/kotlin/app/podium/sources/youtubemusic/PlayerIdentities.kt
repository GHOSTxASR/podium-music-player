package app.podium.sources.youtubemusic

/**
 * One of YouTube's own apps, as the player is asked by it (D-97). Each app gets streams under its
 * own rules: when one app's streams are refused, another's may play. Most come as plain addresses,
 * ready to play; some need the player's script to work out their signature and "n" (D-98).
 *
 * [userAgent] goes with every request for that app's streams, the player's included: a stream is
 * asked for as the app it was handed to.
 */
internal data class PlayerIdentity(
    val key: String,
    val clientId: Int,
    val version: String,
    val userAgent: String,
    /** The app's own description of the device it runs on, sent in the request's client context. */
    val device: Map<String, Any> = emptyMap(),
    /** Asked only when every other app has failed, and never moved up (D-98). */
    val lastResort: Boolean = false,
    /** The name YouTube knows the app by; [key] also tells apart variants of one app (D-98). */
    val clientName: String = key,
    /** Its streams come enciphered: their signature and "n" are solved with the player's script (D-98). */
    val needsPlayerScript: Boolean = false,
    /** Where its player is asked. */
    val host: String = "www.youtube.com",
    /** Asked only as the signed-in listener, with their session; passed over when signed out (D-98). */
    val needsSession: Boolean = false,
    /** Asks (and its streams are asked for) as the browser the listener signed in with. */
    val usesSessionAgent: Boolean = false,
    /** Its streams serve past their first part only with a PoToken (`pot=`) minted by BotGuard (D-98). */
    val needsPoToken: Boolean = false,
    /**
     * Not asked unless a debug build names it ([StreamWalkDebug.potFor]): an app measured not to serve
     * yet, kept to be tried again on a device (D-98).
     */
    val trial: Boolean = false,
)

internal object PlayerIdentities {
    val ANDROID_VR = PlayerIdentity(
        key = "ANDROID_VR",
        clientId = 28,
        version = "1.65.10",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        device = mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"),
    )

    val VISIONOS = PlayerIdentity(
        key = "VISIONOS",
        clientId = 101,
        version = "1.02",
        userAgent = "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)",
        device = mapOf("deviceMake" to "Apple", "deviceModel" to "RealityDevice14,1", "osName" to "visionOS", "osVersion" to "25.6.0.23O471"),
    )

    val IOS = PlayerIdentity(
        key = "IOS",
        clientId = 5,
        version = "21.03.2",
        userAgent = "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)",
        device = mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.7.2.22H124"),
    )

    /**
     * YouTube's TV app, as yt-dlp's `tv_samsung` asks (D-98): a Samsung TV's browser. Its streams are
     * enciphered (the player's script solves them), and they serve only once the pre-roll ad could be
     * skipped (5 s). The TV page's own config (newer client version) is refused ("The page needs to
     * be reloaded"); this one isn't (2026-10-26).
     */
    val TVHTML5 = PlayerIdentity(
        key = "TVHTML5",
        clientId = 7,
        version = "5.20260707",
        userAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
        device = mapOf(
            "deviceMake" to "Samsung", "deviceModel" to "UKS9800", "osName" to "Tizen", "osVersion" to "2.4.0",
            "userAgent" to "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
        ),
        needsPlayerScript = true,
    )

    /** The TV app as the signed-in listener (D-98): the session goes with the player request. */
    val TVHTML5_SIGNED_IN = TVHTML5.copy(key = "TVHTML5_SIGNED_IN", needsSession = true)

    /**
     * YouTube Music's web player, signed out (D-98): enciphered streams that serve only their first
     * part without a PoToken. A trial: on 2026-10-26 its solved streams were refused with web
     * PoTokens too (minted on the phone, and in desktop Chrome), bound to the guest visitor or the
     * video, with or without a player token. Asked only when a debug build names it.
     */
    val WEB_REMIX = PlayerIdentity(
        key = "WEB_REMIX",
        clientId = 67,
        version = "1.20260707.12.00",
        userAgent = BotGuardMinter.USER_AGENT,
        needsPlayerScript = true,
        host = "music.youtube.com",
        needsPoToken = true,
        trial = true,
    )

    /**
     * YouTube Music's web player as the signed-in listener (D-98), from the browser they signed in
     * with. Its streams are enciphered, and serve only their first part unless the account has
     * Premium (web PoTokens didn't change that, measured 2026-10-26; a debug build can still add one).
     */
    val WEB_REMIX_SIGNED_IN = PlayerIdentity(
        key = "WEB_REMIX_SIGNED_IN",
        clientName = "WEB_REMIX",
        clientId = 67,
        version = "1.20260707.12.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
        needsPlayerScript = true,
        host = "music.youtube.com",
        needsSession = true,
        usesSessionAgent = true,
    )

    val ANDROID = PlayerIdentity(
        key = "ANDROID",
        clientId = 3,
        version = "21.03.36",
        userAgent = "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip",
        device = mapOf("androidSdkVersion" to 35, "osName" to "Android", "osVersion" to "15"),
        // Answers with no audio formats at all for most songs (measured 2026-10-26): one request, last.
        lastResort = true,
    )

    /**
     * In the order first tried, until one has served a stream (then that one goes first). VISIONOS
     * first: on a phone, on 2026-10-26, it was the only app whose streams served past their first part
     * (ANDROID_VR's and IOS's stop after about 256 KB without a PoToken, D-98). TVHTML5 next: the one
     * backup measured to serve (with the player's script), then the TV app signed in (as fast, no
     * faster: the same ad). WEB_REMIX signed in comes after ANDROID_VR and IOS: without a PoToken its
     * streams serve only their first part (unless the account has Premium), and its answers can make
     * the walk wait out an ad first. Signed-in apps are asked only when the listener is signed in.
     */
    val ALL = listOf(VISIONOS, TVHTML5, TVHTML5_SIGNED_IN, ANDROID_VR, IOS, WEB_REMIX, WEB_REMIX_SIGNED_IN, ANDROID)

    /** The app a stream address was handed to, from its `c=` parameter (the signed-out one, where both exist). */
    fun of(streamUrl: String): PlayerIdentity? {
        val client = streamUrl.substringAfter('?', "").split('&').firstOrNull { it.startsWith("c=") }?.substringAfter("c=") ?: return null
        return ALL.firstOrNull { it.clientName == client && !it.needsSession } ?: ALL.firstOrNull { it.clientName == client }
    }
}
