package app.podium.sources.subsonic

import app.podium.core.common.Outcome
import app.podium.sources.api.ConnectResult
import app.podium.sources.api.CredentialStore
import app.podium.sources.api.MusicSource
import app.podium.sources.api.NetworkPolicy
import app.podium.sources.api.SetupField
import app.podium.sources.api.SetupForm
import app.podium.sources.api.SetupProblem
import app.podium.sources.api.SourceFactory
import app.podium.sources.api.SourceProfile
import java.net.URI

/**
 * Adds an (Open)Subsonic music server (D-37). Checks the address against the network policy (D-09:
 * `https`, or `http` on the listener's own network after they confirm), then asks the server with the
 * credentials before anything is kept. The password goes to the credential store, nowhere else.
 */
class SubsonicSourceFactory(
    private val transport: HttpTransport,
) : SourceFactory {

    constructor(userAgent: String) : this(UrlConnectionTransport(userAgent))

    override val form = SetupForm(
        kind = KIND,
        title = "Add a music server",
        fields = listOf(
            SetupField(SubsonicMusicSource.KEY_ADDRESS, "Server address", "https://music.example.com", SetupField.Kind.URL),
            SetupField(SubsonicMusicSource.KEY_USERNAME, "User name"),
            SetupField(SubsonicMusicSource.KEY_PASSWORD, "Password", kind = SetupField.Kind.SECRET),
        ),
    )

    override suspend fun connect(values: Map<String, String>): ConnectResult {
        val username = values[SubsonicMusicSource.KEY_USERNAME]?.trim().orEmpty()
        val password = values[SubsonicMusicSource.KEY_PASSWORD].orEmpty()
        if (username.isEmpty() || password.isEmpty()) return ConnectResult.Refused(SetupProblem.MISSING_FIELD)
        val base = when (val verdict = NetworkPolicy.check(values[SubsonicMusicSource.KEY_ADDRESS].orEmpty())) {
            is NetworkPolicy.Verdict.Secure -> verdict.base
            is NetworkPolicy.Verdict.LocalCleartext ->
                if (values[SetupForm.ALLOW_CLEARTEXT] == "true") verdict.base
                else return ConnectResult.Refused(SetupProblem.NEEDS_CLEARTEXT_CONSENT)
            is NetworkPolicy.Verdict.Refused -> return ConnectResult.Refused(verdict.problem)
        }
        val api = SubsonicApi(base, transport, credentials = { null })
        return when (val r = api.ping(ServerCredentials(username, password))) {
            is Outcome.Success -> ConnectResult.Connected(
                displayName = displayName(r.value, base),
                settings = mapOf(SubsonicMusicSource.KEY_ADDRESS to base, SubsonicMusicSource.KEY_USERNAME to username),
                secrets = mapOf(SubsonicMusicSource.KEY_PASSWORD to password),
            )
            is Outcome.Failure -> ConnectResult.Refused(SubsonicMusicSource.problemOf(r.error))
        }
    }

    override fun create(profile: SourceProfile, credentials: CredentialStore): MusicSource =
        SubsonicMusicSource(profile, credentials, transport)

    companion object {
        const val KIND = "opensubsonic"

        /** "Navidrome (music.home.lan)": what the server says it is, and where. */
        internal fun displayName(identity: ServerIdentity, base: String): String {
            val host = runCatching { URI(base).host }.getOrNull()?.removePrefix("[")?.removeSuffix("]") ?: base
            val type = identity.type?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Music server"
            return "$type ($host)"
        }
    }
}
