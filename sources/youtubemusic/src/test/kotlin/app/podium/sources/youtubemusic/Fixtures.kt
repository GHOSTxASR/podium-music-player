package app.podium.sources.youtubemusic

/*
 * Hand-written answers in the shape of the music web client's (YOUTUBE_MUSIC_IMPLEMENTATION_NOTES.md §3).
 * Nothing here was captured from the service: names, ids and numbers are invented, and only the
 * structure parsers depend on is present.
 */
internal object Fixtures {

    private fun browse(id: String, page: String) =
        """{"browseEndpoint":{"browseId":"$id","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$page"}}}}"""

    private fun watch(videoId: String, type: String, list: String? = null) =
        """{"watchEndpoint":{"videoId":"$videoId"${list?.let { ",\"playlistId\":\"$it\"" } ?: ""},"watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$type"}}}}"""

    private fun thumbs(base: String) =
        """{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/$base=w60-h60-l90-rj","width":60,"height":60},{"url":"https://lh3.googleusercontent.com/$base=w120-h120-l90-rj","width":120,"height":120}]}}}"""

    const val SEP = """{"text":" • "}"""
    const val EXPLICIT = """[{"musicInlineBadgeRenderer":{"icon":{"iconType":"MUSIC_EXPLICIT_BADGE"}}}]"""

    /** A song row as search and lists show it: title, then "Song • Artist • Album • 3:45". */
    fun songRow(videoId: String, title: String, artist: String, artistId: String, album: String?, albumId: String?, duration: String, label: Boolean = true, type: String = "MUSIC_VIDEO_TYPE_ATV", explicit: Boolean = false) = """
        {"musicResponsiveListItemRenderer":{
          "thumbnail":${thumbs("song-$videoId")},
          "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":${watch(videoId, type)}}}}},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title","navigationEndpoint":${watch(videoId, type)}}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
              ${if (label) """{"text":"Song"},$SEP,""" else ""}
              {"text":"$artist","navigationEndpoint":${browse(artistId, "MUSIC_PAGE_TYPE_ARTIST")}}
              ${if (album != null) """,$SEP,{"text":"$album","navigationEndpoint":${browse(albumId!!, "MUSIC_PAGE_TYPE_ALBUM")}}""" else ""}
              ,$SEP,{"text":"$duration"}
            ]}}}
          ],
          ${if (explicit) """"badges":$EXPLICIT,""" else ""}
          "playlistItemData":{"videoId":"$videoId"}
        }}"""

    fun videoRow(videoId: String, title: String, channel: String, views: String) = """
        {"musicResponsiveListItemRenderer":{
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Video"},$SEP,{"text":"$channel"},$SEP,{"text":"$views"}]}}}
          ],
          "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":${watch(videoId, "MUSIC_VIDEO_TYPE_UGC")}}}}},
          "playlistItemData":{"videoId":"$videoId"}
        }}"""

    fun episodeRow(videoId: String, title: String) = """
        {"musicResponsiveListItemRenderer":{
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Episode"},$SEP,{"text":"Some show"}]}}}
          ],
          "playlistItemData":{"videoId":"$videoId"}
        }}"""

    fun browseRow(id: String, page: String, title: String, vararg detail: String) = """
        {"musicResponsiveListItemRenderer":{
          "thumbnail":${thumbs("row-$id")},
          "navigationEndpoint":${browse(id, page)},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[${detail.joinToString(",$SEP,") { """{"text":"$it"}""" }}]}}}
          ]
        }}"""

    fun twoRow(id: String, page: String, title: String, vararg detail: String) = """
        {"musicTwoRowItemRenderer":{
          "thumbnailRenderer":${thumbs("tile-$id")},
          "title":{"runs":[{"text":"$title","navigationEndpoint":${browse(id, page)}}]},
          "subtitle":{"runs":[${detail.joinToString(",$SEP,") { """{"text":"$it"}""" }}]},
          "navigationEndpoint":${browse(id, page)}
        }}"""

    fun twoRowSong(videoId: String, title: String, artist: String, artistId: String) = """
        {"musicTwoRowItemRenderer":{
          "thumbnailRenderer":${thumbs("tile-$videoId")},
          "title":{"runs":[{"text":"$title"}]},
          "subtitle":{"runs":[{"text":"Song"},$SEP,{"text":"$artist","navigationEndpoint":${browse(artistId, "MUSIC_PAGE_TYPE_ARTIST")}}]},
          "navigationEndpoint":${watch(videoId, "MUSIC_VIDEO_TYPE_ATV")}
        }}"""

    fun chip(label: String, params: String) =
        """{"chipCloudChipRenderer":{"text":{"runs":[{"text":"$label"}]},"uniqueId":"$label","navigationEndpoint":{"searchEndpoint":{"query":"q","params":"$params"}}}}"""

    /** The newer search layout: a top card, then one item per section; filters as chips. */
    val searchAll = """
        {"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{
          "header":{"chipCloudRenderer":{"chips":[${chip("Songs", "SONGS-PARAMS")},${chip("Albums", "ALBUMS-PARAMS")},${chip("Community playlists", "CP-PARAMS")}]}},
          "contents":[
            {"musicCardShelfRenderer":{
              "title":{"runs":[{"text":"Night Owls","navigationEndpoint":${browse("UCnightowls00000000000", "MUSIC_PAGE_TYPE_ARTIST")}}]},
              "subtitle":{"runs":[{"text":"Artist"},$SEP,{"text":"1.2M monthly audience"}]},
              "thumbnail":${thumbs("card")}
            }},
            {"itemSectionRenderer":{"contents":[${songRow("aaaaaaaaaa1", "First Light", "Night Owls", "UCnightowls00000000000", "Dawn", "MPREb_dawn000", "3:45", explicit = true)}]}},
            {"itemSectionRenderer":{"contents":[${videoRow("vvvvvvvvvv1", "First Light (Live)", "Fan Channel", "12K views")}]}},
            {"itemSectionRenderer":{"contents":[${episodeRow("eeeeeeeeee1", "A talk about owls")}]}},
            {"itemSectionRenderer":{"contents":[${browseRow("MPREb_dawn000", "MUSIC_PAGE_TYPE_ALBUM", "Dawn", "Album", "Night Owls", "2021")}]}},
            {"itemSectionRenderer":{"contents":[${browseRow("VLPLowls0001", "MUSIC_PAGE_TYPE_PLAYLIST", "Owl songs", "Playlist", "Someone", "42 songs")}]}},
            {"itemSectionRenderer":{"contents":[${browseRow("UCprofile000000000000", "MUSIC_PAGE_TYPE_USER_CHANNEL", "owlfan", "Profile", "@owlfan")}]}}
          ]
        }}}}]}}}"""

    /** A songs-only search: the older shelf layout with a continuation. */
    val searchSongs = """
        {"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
          {"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},"contents":[
            ${songRow("aaaaaaaaaa1", "First Light", "Night Owls", "UCnightowls00000000000", "Dawn", "MPREb_dawn000", "3:45", label = false)},
            ${songRow("aaaaaaaaaa2", "Second Wind", "Night Owls", "UCnightowls00000000000", "Dawn", "MPREb_dawn000", "4:01", label = false)}
          ],"continuations":[{"nextContinuationData":{"continuation":"SONGS-TOKEN-1"}}]}}
        ]}}}}]}}}"""

    val searchSongsMore = """
        {"continuationContents":{"musicShelfContinuation":{"contents":[
          ${songRow("aaaaaaaaaa3", "Third Hour", "Night Owls", "UCnightowls00000000000", null, null, "2:59", label = false)}
        ]}}}"""

    val searchNothing = """
        {"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
          {"itemSectionRenderer":{"contents":[{"messageRenderer":{"text":{"runs":[{"text":"No results"}]}}}]}}
        ]}}}}]}}}"""

    val home = """
        {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{
          "contents":[
            {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Quick picks"}]}}},
              "contents":[${songRow("aaaaaaaaaa1", "First Light", "Night Owls", "UCnightowls00000000000", "Dawn", "MPREb_dawn000", "3:45", label = false)}]}},
            {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Albums for you"}]}}},
              "contents":[${twoRow("MPREb_dawn000", "MUSIC_PAGE_TYPE_ALBUM", "Dawn", "Album", "Night Owls")}]}},
            {"musicTastebuilderShelfRenderer":{}}
          ],
          "continuations":[{"nextContinuationData":{"continuation":"HOME-TOKEN"}}]
        }}}}]}}}"""

    val homeMore = """
        {"continuationContents":{"sectionListContinuation":{"contents":[
          {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Recommended artists"}]}}},
            "contents":[${twoRow("UCnightowls00000000000", "MUSIC_PAGE_TYPE_ARTIST", "Night Owls", "Artist")}]}}
        ]}}}"""

    /** The newer two-column album page. */
    val album = """
        {"contents":{"twoColumnBrowseResultsRenderer":{
          "tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicResponsiveHeaderRenderer":{
            "title":{"runs":[{"text":"Dawn"}]},
            "subtitle":{"runs":[{"text":"Album"},$SEP,{"text":"2021"}]},
            "straplineTextOne":{"runs":[{"text":"Night Owls","navigationEndpoint":${browse("UCnightowls00000000000", "MUSIC_PAGE_TYPE_ARTIST")}}]},
            "secondSubtitle":{"runs":[{"text":"2 songs"},$SEP,{"text":"8 minutes"}]},
            "thumbnail":${thumbs("album-dawn")}
          }}]}}}}],
          "secondaryContents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{"index":{"runs":[{"text":"1"}]},
              "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"First Light","navigationEndpoint":${watch("aaaaaaaaaa1", "MUSIC_VIDEO_TYPE_ATV", "OLAK5uy_dawn")}}]}}},
                             {"musicResponsiveListItemFlexColumnRenderer":{"text":{}}}],
              "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"3:45"}]}}}],
              "playlistItemData":{"videoId":"aaaaaaaaaa1"}}},
            {"musicResponsiveListItemRenderer":{"index":{"runs":[{"text":"2"}]},
              "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Second Wind"}]}}}],
              "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"4:01"}]}}}],
              "musicItemRendererDisplayPolicy":"MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT",
              "playlistItemData":{"videoId":"aaaaaaaaaa2"}}}
          ]}}]}}
        }}}"""

    val playlist = """
        {"contents":{"twoColumnBrowseResultsRenderer":{
          "tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicResponsiveHeaderRenderer":{
            "title":{"runs":[{"text":"Owl songs"}]},
            "straplineTextOne":{"runs":[{"text":"Someone"}]},
            "subtitle":{"runs":[{"text":"Playlist"},$SEP,{"text":"2024"}]},
            "secondSubtitle":{"runs":[{"text":"3 songs"}]},
            "thumbnail":${thumbs("pl-owls")}
          }}]}}}}],
          "secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"playlistId":"PLowls0001","contents":[
            ${songRow("aaaaaaaaaa1", "First Light", "Night Owls", "UCnightowls00000000000", "Dawn", "MPREb_dawn000", "3:45", label = false)},
            ${songRow("aaaaaaaaaa2", "Second Wind", "Night Owls", "UCnightowls00000000000", "Dawn", "MPREb_dawn000", "4:01", label = false)},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"PL-TOKEN"}}}}
          ]}}]}}
        }}}"""

    val playlistMore = """
        {"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
          ${songRow("aaaaaaaaaa3", "Third Hour", "Night Owls", "UCnightowls00000000000", null, null, "2:59", label = false)}
        ]}}]}"""

    val artist = """
        {"header":{"musicImmersiveHeaderRenderer":{
            "title":{"runs":[{"text":"Night Owls"}]},
            "thumbnail":${thumbs("artist-owls")},
            "startRadioButton":{"buttonRenderer":{"navigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"RDEMowls"}}}},
            "playButton":{"buttonRenderer":{"navigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"RDAOowls"}}}}
          }},
         "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
          {"musicShelfRenderer":{"title":{"runs":[{"text":"Top songs"}]},
            "contents":[${songRow("aaaaaaaaaa1", "First Light", "Night Owls", "UCnightowls00000000000", "Dawn", "MPREb_dawn000", "3:45", label = false)}],
            "bottomEndpoint":{"browseEndpoint":{"browseId":"VLOLAK5uy_allowls"}}}},
          {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Albums"}]}}},
            "contents":[${twoRow("MPREb_dawn000", "MUSIC_PAGE_TYPE_ALBUM", "Dawn", "Album", "2021")}]}},
          {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Singles & EPs"}]}}},
            "contents":[${twoRow("MPREb_dusk000", "MUSIC_PAGE_TYPE_ALBUM", "Dusk", "Single", "2023")}]}},
          {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Fans might also like"}]}}},
            "contents":[${twoRow("UCmorningbirds0000000", "MUSIC_PAGE_TYPE_ARTIST", "Morning Birds", "Artist")}]}}
        ]}}}}]}}}"""

    /** The watch-next list for a radio: wrapped and plain rows, and a radio continuation. */
    val radio = """
        {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{
          "contents":[
            {"playlistPanelVideoRenderer":{"videoId":"aaaaaaaaaa1","title":{"runs":[{"text":"First Light"}]},
              "longBylineText":{"runs":[{"text":"Night Owls","navigationEndpoint":${browse("UCnightowls00000000000", "MUSIC_PAGE_TYPE_ARTIST")}},$SEP,{"text":"Dawn","navigationEndpoint":${browse("MPREb_dawn000", "MUSIC_PAGE_TYPE_ALBUM")}},$SEP,{"text":"2021"}]},
              "lengthText":{"runs":[{"text":"3:45"}]},"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/r1=w60-h60","width":60}]},
              "navigationEndpoint":${watch("aaaaaaaaaa1", "MUSIC_VIDEO_TYPE_ATV")}}},
            {"playlistPanelVideoWrapperRenderer":{"primaryRenderer":{"playlistPanelVideoRenderer":{"videoId":"bbbbbbbbbb1","title":{"runs":[{"text":"Morning Song"}]},
              "longBylineText":{"runs":[{"text":"Morning Birds","navigationEndpoint":${browse("UCmorningbirds0000000", "MUSIC_PAGE_TYPE_ARTIST")}}]},
              "lengthText":{"runs":[{"text":"3:10"}]},"navigationEndpoint":${watch("bbbbbbbbbb1", "MUSIC_VIDEO_TYPE_OMV")}}}}}
          ],
          "continuations":[{"nextRadioContinuationData":{"continuation":"RADIO-TOKEN"}}]
        }}}}}}]}}}}}"""

    val radioMore = """
        {"continuationContents":{"playlistPanelContinuation":{"contents":[
          {"playlistPanelVideoRenderer":{"videoId":"cccccccccc1","title":{"runs":[{"text":"Evening Song"}]},
            "longBylineText":{"runs":[{"text":"Evening Choir"}]},"lengthText":{"runs":[{"text":"2:30"}]}}}
        ]}}}"""

    val libraryPlaylists = """
        {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
          {"gridRenderer":{"items":[
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"New playlist"}]},"navigationEndpoint":{"createPlaylistEndpoint":{}}}},
            ${twoRow("VLLM", "MUSIC_PAGE_TYPE_PLAYLIST", "Liked Music", "Auto playlist")},
            ${twoRow("VLPLmine0001", "MUSIC_PAGE_TYPE_PLAYLIST", "Road trip", "Playlist", "12 songs")}
          ]}}
        ]}}}}]}}}"""

    val history = """
        {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
          {"musicShelfRenderer":{"title":{"runs":[{"text":"Today"}]},"contents":[${songRow("aaaaaaaaaa1", "First Light", "Night Owls", "UCnightowls00000000000", "Dawn", "MPREb_dawn000", "3:45", label = false)}]}},
          {"musicShelfRenderer":{"title":{"runs":[{"text":"Yesterday"}]},"contents":[${songRow("aaaaaaaaaa2", "Second Wind", "Night Owls", "UCnightowls00000000000", "Dawn", "MPREb_dawn000", "4:01", label = false)}]}}
        ]}}}}]}}}"""

    val accountMenu = """
        {"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"header":{"activeAccountHeaderRenderer":{
          "accountName":{"runs":[{"text":"Listener Name"}]},
          "channelHandle":{"runs":[{"text":"@listener"}]},
          "accountPhoto":{"thumbnails":[{"url":"https://yt3.ggpht.com/photo=s88","width":88}]}
        }}}}}}],"responseContext":{"serviceTrackingParams":[{"service":"GFEEDBACK","params":[{"key":"logged_in","value":"1"}]}]}}"""

    val signedOutAnswer = """{"responseContext":{"serviceTrackingParams":[{"service":"GFEEDBACK","params":[{"key":"logged_in","value":"0"}]}]},"contents":{}}"""

    const val PAGE_HTML = """<html><script>ytcfg.set({"INNERTUBE_CLIENT_NAME":"WEB_REMIX","INNERTUBE_CLIENT_VERSION":"1.20261005.01.00","VISITOR_DATA":"CgtWaXNpdG9yMTIz"});</script></html>"""
}
