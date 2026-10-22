import Foundation

public struct M3UChannel: Identifiable, Sendable {
    public let id: String
    public let name: String
    public let group: String
    public let logoUrl: String
    public let streamUrl: String

    public init(id: String, name: String, group: String, logoUrl: String, streamUrl: String) {
        self.id = id
        self.name = name
        self.group = group
        self.logoUrl = logoUrl
        self.streamUrl = streamUrl
    }
}

public actor M3UParserIOS {

    public init() {}

    /// Parst eine M3U-Playlist direkt aus dem Netzwerk über HTTP zeilenweise über den Socket.
    /// Der Speicherverbrauch bleibt konstant bei wenigen Kilobytes, selbst bei 20.000+ Zeilen.
    public func parseRemoteStream(from url: URL) async throws -> [M3UChannel] {
        var channelList: [M3UChannel] = []
        channelList.reserveCapacity(8000)

        let (bytes, response) = try await URLSession.shared.bytes(from: url)

        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            throw URLError(.badServerResponse)
        }

        var channelId = ""
        var channelName = ""
        var channelGroup = "Allgemein"
        var channelLogo = ""
        var isMetadataPending = false

        for try await rawLine in bytes.lines {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }

            if line.hasPrefix("#EXTINF:") {
                channelId = extractAttribute(from: line, key: "tvg-id")
                channelName = extractChannelName(from: line)
                let group = extractAttribute(from: line, key: "group-title")
                channelGroup = group.isEmpty ? "Allgemein" : group
                channelLogo = extractAttribute(from: line, key: "tvg-logo")
                isMetadataPending = true
            } else if !line.hasPrefix("#") && isMetadataPending {
                let channel = M3UChannel(
                    id = channelId,
                    name = channelName.isEmpty ? "Unbenannter Kanal" : channelName,
                    group = channelGroup,
                    logoUrl = channelLogo,
                    streamUrl: line
                )
                channelList.append(channel)

                channelId = ""
                channelName = ""
                channelGroup = "Allgemein"
                channelLogo = ""
                isMetadataPending = false
            }
        }

        return channelList
    }

    /// Parst einen bereits geladenen M3U-String zeilenweise über enumerateLines ohne Array-Duplikation im RAM.
    public func parseString(content: String) async -> [M3UChannel] {
        var channelList: [M3UChannel] = []
        channelList.reserveCapacity(8000)

        var channelId = ""
        var channelName = ""
        var channelGroup = "Allgemein"
        var channelLogo = ""
        var isMetadataPending = false

        content.enumerateLines { line, _ in
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { return }

            if trimmed.hasPrefix("#EXTINF:") {
                channelId = self.extractAttribute(from: trimmed, key: "tvg-id")
                channelName = self.extractChannelName(from: trimmed)
                let group = self.extractAttribute(from: trimmed, key: "group-title")
                channelGroup = group.isEmpty ? "Allgemein" : group
                channelLogo = self.extractAttribute(from: trimmed, key: "tvg-logo")
                isMetadataPending = true
            } else if !trimmed.hasPrefix("#") && isMetadataPending {
                channelList.append(
                    M3UChannel(
                        id = channelId,
                        name = channelName.isEmpty ? "Unbenannter Kanal" : channelName,
                        group = channelGroup,
                        logoUrl = channelLogo,
                        streamUrl: trimmed
                    )
                )

                channelId = ""
                channelName = ""
                channelGroup = "Allgemein"
                channelLogo = ""
                isMetadataPending = false
            }
        }

        return channelList
    }

    /// Substring-Suche ohne Regex für maximale Geschwindigkeit.
    private nonisolated func extractAttribute(from line: String, key: String) -> String {
        let pattern = "\(key)=\""
        guard let range = line.range(of: pattern, options: .caseInsensitive) else {
            return ""
        }
        let remainder = line[range.upperBound...]
        guard let endQuote = remainder.firstIndex(of: "\"") else {
            return ""
        }
        return String(remainder[..<endQuote]).trimmingCharacters(in: .whitespaces)
    }

    private nonisolated func extractChannelName(from line: String) -> String {
        guard let commaIdx = line.lastIndex(of: ",") else {
            return ""
        }
        let afterComma = line[line.index(after: commaIdx)...]
        return String(afterComma).trimmingCharacters(in: .whitespaces)
    }
}
