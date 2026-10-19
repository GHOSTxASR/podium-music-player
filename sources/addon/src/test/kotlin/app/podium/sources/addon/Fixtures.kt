package app.podium.sources.addon

/** Answers shaped like a real BitChord addon's (field names and types as served), with made-up content. */
internal object Fixtures {
    const val BASE = "https://addon.example.com"

    const val MANIFEST = """{
        "id": "com.example.lossless",
        "name": "Lossless test",
        "version": "1.0.0",
        "resources": ["search", "stream"],
        "allowDownloads": 0,
        "settings": [
            {"key": "quality", "type": "select", "default": "lossless", "options": [
                {"label": "Lossless", "value": "lossless"},
                {"label": "High", "value": "high"},
                {"label": "Low", "value": "low"}
            ]}
        ]
    }"""

    const val SEARCH = """{"tracks": [
        {"id": "lt_001", "title": "One More Time", "artist": "Daft Punk", "album": "Discovery", "duration": 320, "artworkURL": "https://img.example.com/1.jpg"},
        {"id": "lt_002", "title": "One More Time (Radio Edit)", "artist": "Daft Punk", "album": "Musique, Vol. 1", "duration": 235, "artworkURL": "https://img.example.com/2.jpg"},
        {"id": "lt_003", "title": "One More Time (12 Mix)", "artist": "Daft Punk", "album": "One More Time", "duration": 480, "artworkURL": "https://img.example.com/3.jpg"}
    ]}"""

    const val STREAM_DASH = """{"url": "https://cdn.example.com/manifest/abc.mpd", "format": "dash",
        "quality": "Hi-Res Lossless · 24-bit / 44.1 kHz", "manifest": "dash", "encrypted": false}"""
}
