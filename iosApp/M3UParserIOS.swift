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
    
    public func parseStream(from url: URL) async throws -> [M3UChannel] {
        var channels: [M3UChannel] = []
        channels.reserveCapacity(5000)
        
        let (asyncBytes, response) = try await URLSession.shared.bytes(from: url)
        
        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            throw URLError(.badServerResponse)
        }
        
        var currentId = ""
        var currentName = ""
        var currentGroup = "Allgemein"
        var currentLogo = ""
        var isInsideExtInf = false
        
        for try await line in asyncBytes.lines {
            let trimmedLine = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmedLine.isEmpty { continue }
            
            if trimmedLine.hasPrefix("#EXTINF:") {
                currentId = self.extractAttribute(from: trimmedLine, key: "tvg-id")
                currentName = self.extractChannelName(from: trimmedLine)
                let parsedGroup = self.extractAttribute(from: trimmedLine, key: "group-title")
                currentGroup = parsedGroup.isEmpty ? "Allgemein" : parsedGroup
                currentLogo = self.extractAttribute(from: trimmedLine, key: "tvg-logo")
                isInsideExtInf = true
            } else if !trimmedLine.hasPrefix("#") && isInsideExtInf {
                channels.append(
                    M3UChannel(
                        id = currentId,
                        name = currentName.isEmpty ? "Unbenannter Kanal" : currentName,
                        group = currentGroup,
                        logoUrl = currentLogo,
                        streamUrl = trimmedLine
                    )
                )
                currentId = ""
                currentName = ""
                currentGroup = "Allgemein"
                currentLogo = ""
                isInsideExtInf = false
            }
        }
        return channels
    }

    public func parseString(content: String) async -> [M3UChannel] {
        var channels: [M3UChannel] = []
        channels.reserveCapacity(5000)
        
        var currentId = ""
        var currentName = ""
        var currentGroup = "Allgemein"
        var currentLogo = ""
        var isInsideExtInf = false
        
        content.enumerateLines { line, _ in
            let trimmedLine = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmedLine.isEmpty { return }
            
            if trimmedLine.hasPrefix("#EXTINF:") {
                currentId = self.extractAttribute(from: trimmedLine, key: "tvg-id")
                currentName = self.extractChannelName(from: trimmedLine)
                let parsedGroup = self.extractAttribute(from: trimmedLine, key: "group-title")
                currentGroup = parsedGroup.isEmpty ? "Allgemein" : parsedGroup
                currentLogo = self.extractAttribute(from: trimmedLine, key: "tvg-logo")
                isInsideExtInf = true
            } else if !trimmedLine.hasPrefix("#") && isInsideExtInf {
                channels.append(
                    M3UChannel(
                        id = currentId,
                        name = currentName.isEmpty ? "Unbenannter Kanal" : currentName,
                        group = currentGroup,
                        logoUrl = currentLogo,
                        streamUrl = trimmedLine
                    )
                )
                currentId = ""
                currentName = ""
                currentGroup = "Allgemein"
                currentLogo = ""
                isInsideExtInf = false
            }
        }
        return channels
    }
    
    private nonisolated func extractAttribute(from line: String, key: String) -> String {
        let prefix = "\(key)=\""
        guard let prefixRange = line.range(of: prefix, options: .caseInsensitive) else { return "" }
        let valueSubstring = line[prefixRange.upperBound...]
        guard let quoteIndex = valueSubstring.firstIndex(of: "\"") else { return "" }
        return String(valueSubstring[..<quoteIndex]).trimmingCharacters(in: .whitespaces)
    }
    
    private nonisolated func extractChannelName(from line: String) -> String {
        guard let lastCommaIndex = line.lastIndex(of: ",") else { return "" }
        let nameSubstring = line[line.index(after: lastCommaIndex)...]
        return String(nameSubstring).trimmingCharacters(in: .whitespaces)
    }
}
