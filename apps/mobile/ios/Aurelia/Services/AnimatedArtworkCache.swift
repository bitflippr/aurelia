import AureliaCore
import CryptoKit
import Foundation

/// The plugin URL requires Jellyfin authentication. Download once, verify it, then loop locally.
actor AnimatedArtworkCache {
    static let shared = AnimatedArtworkCache()
    private let limit = 128 * 1024 * 1024

    func localURL(for variant: AnimatedArtworkVariant, token: String) async throws -> URL {
        let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("aurelia-animated-artwork", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent(variant.sha256 + ".mp4")
        if FileManager.default.fileExists(atPath: destination.path) {
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: destination.path)
            return destination
        }
        guard let url = URL(string: variant.url) else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.setValue(token, forHTTPHeaderField: "X-Emby-Token")
        let session = URLSession(configuration: .ephemeral, delegate: ArtworkDownloadDelegate(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (temporary, response) = try await session.download(for: request)
        defer { try? FileManager.default.removeItem(at: temporary) }
        guard let response = response as? HTTPURLResponse, response.statusCode == 200,
            let size = try temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize,
            size > 0, size <= 64 * 1024 * 1024, UInt64(size) == variant.bytes
        else { throw URLError(.badServerResponse) }
        try Task.checkCancellation()
        let checksum = try await Task.detached(priority: .utility) {
            let data = try Data(contentsOf: temporary, options: .mappedIfSafe)
            return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        }.value
        guard checksum == variant.sha256 else { throw URLError(.cannotDecodeContentData) }
        try Task.checkCancellation()
        // Another caller may have finished the same album while this request was suspended.
        if !FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.moveItem(at: temporary, to: destination)
        }
        trim(directory, keeping: destination)
        return destination
    }

    private func trim(_ directory: URL, keeping: URL) {
        let keys: Set<URLResourceKey> = [.fileSizeKey, .contentModificationDateKey]
        guard
            let files = try? FileManager.default.contentsOfDirectory(
                at: directory, includingPropertiesForKeys: Array(keys))
        else { return }
        let entries = files.compactMap { url -> (URL, Int, Date)? in
            guard let values = try? url.resourceValues(forKeys: keys) else { return nil }
            return (url, values.fileSize ?? 0, values.contentModificationDate ?? .distantPast)
        }.sorted { $0.2 < $1.2 }
        var size = entries.reduce(0) { $0 + $1.1 }
        for (file, bytes, _) in entries where size > limit && file != keeping {
            if (try? FileManager.default.removeItem(at: file)) != nil { size -= bytes }
        }
    }
}

private final class ArtworkDownloadDelegate: NSObject, URLSessionTaskDelegate, Sendable {
    nonisolated func urlSession(
        _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        // Plugin media is same-origin and direct; do not forward the session token through redirects.
        completionHandler(nil)
    }
}
