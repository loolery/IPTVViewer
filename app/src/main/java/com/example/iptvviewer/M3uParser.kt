package com.example.iptvviewer
import java.util.regex.Pattern
object M3uParser {
    /**
     * Parst einen M3U-String, der das einfache Format '#EXTINF:-1,<Name>' verwendet.
     */
    fun parse(m3uString: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        // Teilt den String in einzelne Zeilen auf
        val lines = m3uString.lines()

        var currentChannelName: String? = null

        for (line in lines) {
            val trimmedLine = line.trim()

            // Schritt 1: Finde eine Info-Zeile
            if (trimmedLine.startsWith("#EXTINF:")) {
                // Extrahiere alles nach dem ersten Komma als Namen
                currentChannelName = trimmedLine.substringAfter(",").trim()
            }
            // Schritt 2: Prüfe, ob die Zeile eine URL ist und wir einen Namen haben
            else if (currentChannelName != null && trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                // Diese Zeile ist die Stream-URL für den zuvor gefundenen Kanal
                channels.add(
                    Channel(
                        name = currentChannelName,
                        logoUrl = "", // Deine Datei hat keine Logo-URL, daher leer lassen
                        streamUrl = trimmedLine
                    )
                )
                // Wichtig: Namen zurücksetzen, damit wir nicht versehentlich mehrere URLs zu einem Namen hinzufügen
                currentChannelName = null
            }
        }
        return channels
    }
}