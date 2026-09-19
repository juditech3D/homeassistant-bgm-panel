package com.judit.hapanel.dlna

/**
 * Les descriptions XML que réclame un contrôleur UPnP.
 *
 * La description du périphérique annonce un `MediaRenderer:1` et ses trois services.
 * Les descriptions de services (SCPD) énumèrent les actions : les contrôleurs s'en
 * servent pour savoir ce qu'ils peuvent demander, et certains refusent un lecteur dont
 * le SCPD est absent ou vide.
 *
 * Volontairement réduites au nécessaire : seules figurent les actions réellement
 * implémentées dans [UpnpServer], et les variables d'état qu'elles référencent.
 */
object UpnpXml {

    /** Formats que le panneau accepte. Android 8.1 les décode tous nativement. */
    const val SINK_PROTOCOLS =
        "http-get:*:audio/mpeg:*," +
            "http-get:*:audio/mp3:*," +
            "http-get:*:audio/mp4:*," +
            "http-get:*:audio/aac:*," +
            "http-get:*:audio/x-aac:*," +
            "http-get:*:audio/flac:*," +
            "http-get:*:audio/x-flac:*," +
            "http-get:*:audio/wav:*," +
            "http-get:*:audio/x-wav:*," +
            "http-get:*:audio/ogg:*," +
            "http-get:*:audio/L16:*"

    fun deviceDescription(uuid: String, friendlyName: String): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <dlna:X_DLNADOC xmlns:dlna="urn:schemas-dlna-org:device-1-0">DMR-1.50</dlna:X_DLNADOC>
    <friendlyName>$friendlyName</friendlyName>
    <manufacturer>HA Panel</manufacturer>
    <modelName>Panneau tactile 7 pouces</modelName>
    <modelNumber>1.0</modelNumber>
    <UDN>uuid:$uuid</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/AVTransport.xml</SCPDURL>
        <controlURL>/AVTransport/control</controlURL>
        <eventSubURL>/AVTransport/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/RenderingControl.xml</SCPDURL>
        <controlURL>/RenderingControl/control</controlURL>
        <eventSubURL>/RenderingControl/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/ConnectionManager.xml</SCPDURL>
        <controlURL>/ConnectionManager/control</controlURL>
        <eventSubURL>/ConnectionManager/event</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""

    private fun scpd(actions: String, variables: String) = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>$actions</actionList>
  <serviceStateTable>$variables</serviceStateTable>
</scpd>"""

    private fun action(name: String, args: String = "") =
        "<action><name>$name</name><argumentList>$args</argumentList></action>"

    private fun arg(name: String, direction: String, variable: String) =
        "<argument><name>$name</name><direction>$direction</direction>" +
            "<relatedStateVariable>$variable</relatedStateVariable></argument>"

    private fun variable(name: String, type: String, sendEvents: String = "no") =
        "<stateVariable sendEvents=\"$sendEvents\"><name>$name</name>" +
            "<dataType>$type</dataType></stateVariable>"

    private val INSTANCE = arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")

    val AV_TRANSPORT_SCPD: String = scpd(
        actions = buildString {
            append(action("SetAVTransportURI", INSTANCE +
                arg("CurrentURI", "in", "AVTransportURI") +
                arg("CurrentURIMetaData", "in", "AVTransportURIMetaData")))
            append(action("SetNextAVTransportURI", INSTANCE +
                arg("NextURI", "in", "NextAVTransportURI") +
                arg("NextURIMetaData", "in", "NextAVTransportURIMetaData")))
            append(action("Play", INSTANCE + arg("Speed", "in", "TransportPlaySpeed")))
            append(action("Pause", INSTANCE))
            append(action("Stop", INSTANCE))
            append(action("Seek", INSTANCE +
                arg("Unit", "in", "A_ARG_TYPE_SeekMode") +
                arg("Target", "in", "A_ARG_TYPE_SeekTarget")))
            append(action("GetTransportInfo", INSTANCE +
                arg("CurrentTransportState", "out", "TransportState") +
                arg("CurrentTransportStatus", "out", "TransportStatus") +
                arg("CurrentSpeed", "out", "TransportPlaySpeed")))
            append(action("GetPositionInfo", INSTANCE +
                arg("Track", "out", "CurrentTrack") +
                arg("TrackDuration", "out", "CurrentTrackDuration") +
                arg("TrackMetaData", "out", "CurrentTrackMetaData") +
                arg("TrackURI", "out", "CurrentTrackURI") +
                arg("RelTime", "out", "RelativeTimePosition") +
                arg("AbsTime", "out", "AbsoluteTimePosition") +
                arg("RelCount", "out", "RelativeCounterPosition") +
                arg("AbsCount", "out", "AbsoluteCounterPosition")))
            append(action("GetMediaInfo", INSTANCE +
                arg("NrTracks", "out", "NumberOfTracks") +
                arg("MediaDuration", "out", "CurrentMediaDuration") +
                arg("CurrentURI", "out", "AVTransportURI") +
                arg("CurrentURIMetaData", "out", "AVTransportURIMetaData") +
                arg("NextURI", "out", "NextAVTransportURI") +
                arg("NextURIMetaData", "out", "NextAVTransportURIMetaData") +
                arg("PlayMedium", "out", "PlaybackStorageMedium") +
                arg("RecordMedium", "out", "RecordStorageMedium") +
                arg("WriteStatus", "out", "RecordMediumWriteStatus")))
            append(action("GetTransportSettings", INSTANCE +
                arg("PlayMode", "out", "CurrentPlayMode") +
                arg("RecQualityMode", "out", "CurrentRecordQualityMode")))
            append(action("GetDeviceCapabilities", INSTANCE +
                arg("PlayMedia", "out", "PossiblePlaybackStorageMedia") +
                arg("RecMedia", "out", "PossibleRecordStorageMedia") +
                arg("RecQualityModes", "out", "PossibleRecordQualityModes")))
        },
        variables = buildString {
            append(variable("TransportState", "string", "yes"))
            append(variable("TransportStatus", "string"))
            append(variable("TransportPlaySpeed", "string"))
            append(variable("AVTransportURI", "string"))
            append(variable("AVTransportURIMetaData", "string"))
            append(variable("NextAVTransportURI", "string"))
            append(variable("NextAVTransportURIMetaData", "string"))
            append(variable("CurrentTrack", "ui4"))
            append(variable("CurrentTrackDuration", "string"))
            append(variable("CurrentTrackMetaData", "string"))
            append(variable("CurrentTrackURI", "string"))
            append(variable("RelativeTimePosition", "string"))
            append(variable("AbsoluteTimePosition", "string"))
            append(variable("RelativeCounterPosition", "i4"))
            append(variable("AbsoluteCounterPosition", "i4"))
            append(variable("NumberOfTracks", "ui4"))
            append(variable("CurrentMediaDuration", "string"))
            append(variable("PlaybackStorageMedium", "string"))
            append(variable("RecordStorageMedium", "string"))
            append(variable("RecordMediumWriteStatus", "string"))
            append(variable("CurrentPlayMode", "string"))
            append(variable("CurrentRecordQualityMode", "string"))
            append(variable("PossiblePlaybackStorageMedia", "string"))
            append(variable("PossibleRecordStorageMedia", "string"))
            append(variable("PossibleRecordQualityModes", "string"))
            append(variable("A_ARG_TYPE_InstanceID", "ui4"))
            append(variable("A_ARG_TYPE_SeekMode", "string"))
            append(variable("A_ARG_TYPE_SeekTarget", "string"))
            append(variable("LastChange", "string", "yes"))
        }
    )

    val RENDERING_CONTROL_SCPD: String = scpd(
        actions = buildString {
            append(action("GetVolume", INSTANCE +
                arg("Channel", "in", "A_ARG_TYPE_Channel") +
                arg("CurrentVolume", "out", "Volume")))
            append(action("SetVolume", INSTANCE +
                arg("Channel", "in", "A_ARG_TYPE_Channel") +
                arg("DesiredVolume", "in", "Volume")))
            append(action("GetMute", INSTANCE +
                arg("Channel", "in", "A_ARG_TYPE_Channel") +
                arg("CurrentMute", "out", "Mute")))
            append(action("SetMute", INSTANCE +
                arg("Channel", "in", "A_ARG_TYPE_Channel") +
                arg("DesiredMute", "in", "Mute")))
        },
        variables = buildString {
            append("<stateVariable sendEvents=\"no\"><name>Volume</name><dataType>ui2</dataType>")
            append("<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>")
            append("</stateVariable>")
            append(variable("Mute", "boolean"))
            append(variable("A_ARG_TYPE_Channel", "string"))
            append(variable("A_ARG_TYPE_InstanceID", "ui4"))
            append(variable("LastChange", "string", "yes"))
        }
    )

    val CONNECTION_MANAGER_SCPD: String = scpd(
        actions = buildString {
            append(action("GetProtocolInfo",
                arg("Source", "out", "SourceProtocolInfo") +
                    arg("Sink", "out", "SinkProtocolInfo")))
            append(action("GetCurrentConnectionIDs",
                arg("ConnectionIDs", "out", "CurrentConnectionIDs")))
            append(action("GetCurrentConnectionInfo",
                arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID") +
                    arg("RcsID", "out", "A_ARG_TYPE_RcsID") +
                    arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID") +
                    arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo") +
                    arg("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager") +
                    arg("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID") +
                    arg("Direction", "out", "A_ARG_TYPE_Direction") +
                    arg("Status", "out", "A_ARG_TYPE_ConnectionStatus")))
        },
        variables = buildString {
            append(variable("SourceProtocolInfo", "string", "yes"))
            append(variable("SinkProtocolInfo", "string", "yes"))
            append(variable("CurrentConnectionIDs", "string", "yes"))
            append(variable("A_ARG_TYPE_ConnectionID", "i4"))
            append(variable("A_ARG_TYPE_RcsID", "i4"))
            append(variable("A_ARG_TYPE_AVTransportID", "i4"))
            append(variable("A_ARG_TYPE_ProtocolInfo", "string"))
            append(variable("A_ARG_TYPE_ConnectionManager", "string"))
            append(variable("A_ARG_TYPE_Direction", "string"))
            append(variable("A_ARG_TYPE_ConnectionStatus", "string"))
        }
    )
}
