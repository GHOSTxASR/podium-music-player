package app.podium.sources.youtubemusic

/**
 * One of YouTube's own apps, as the player is asked by it (D-97). Each app gets streams under its
 * own rules: when one app's streams are refused, another's may play. These are the apps whose
 * streams come as plain addresses, ready to play, with no signature to work out.
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

    val ANDROID = PlayerIdentity(
        key = "ANDROID",
        clientId = 3,
        version = "21.03.36",
        userAgent = "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip",
        device = mapOf("androidSdkVersion" to 35, "osName" to "Android", "osVersion" to "15"),
    )

    /** In the order first tried, until one has served a stream (then that one goes first). */
    val ALL = listOf(ANDROID_VR, VISIONOS, IOS, ANDROID)

    /** The app a stream address was handed to, from its `c=` parameter. */
    fun of(streamUrl: String): PlayerIdentity? {
        val client = streamUrl.substringAfter('?', "").split('&').firstOrNull { it.startsWith("c=") }?.substringAfter("c=") ?: return null
        return ALL.firstOrNull { it.key == client }
    }
}
