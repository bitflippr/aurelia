using System.Security.Cryptography;
using Jellyfin.Plugin.AnimatedArtwork;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace AnimatedArtwork.Tests;

public sealed class ArtworkCatalogTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "artwork-sidecar-" + Guid.NewGuid().ToString("N"));
    private readonly FakeReader _reader = new();
    private readonly ArtworkCatalog _catalog;

    public ArtworkCatalogTests()
    {
        Directory.CreateDirectory(_root);
        _catalog = new(_reader, NullLogger<ArtworkCatalog>.Instance);
    }

    [Fact]
    public async Task DiscoversFilesWithoutIndexOrConfiguration()
    {
        await File.WriteAllBytesAsync(Path.Combine(_root, "animated-cover.mp4"), [1, 2, 3]);
        await File.WriteAllBytesAsync(Path.Combine(_root, "animated-cover.gif"), [4, 5]);
        var mp4 = Assert.IsType<LocatedAsset>(await _catalog.LocateAsync(_root, "square"));
        Assert.Equal("video/mp4", mp4.ContentType);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData([1, 2, 3])), mp4.Asset.Sha256);
        Assert.Equal("image/gif", (await _catalog.LocateAsync(_root, "gif"))?.ContentType);
        Assert.Null(await _catalog.LocateAsync(_root, "tall"));
    }

    [Fact]
    public async Task NewFilesAreDiscoveredAndRemovedFilesStopBeingServed()
    {
        Assert.Null(await _catalog.LocateAsync(_root, "square"));
        var file = Path.Combine(_root, "animated-cover.mp4");
        await File.WriteAllBytesAsync(file, [1, 2, 3]);
        Assert.NotNull(await _catalog.LocateAsync(_root, "square"));
        File.Delete(file);
        Assert.Null(await _catalog.LocateAsync(_root, "square"));
    }

    [Fact]
    public async Task ChangedSidecarsInvalidateCachedMetadata()
    {
        var file = Path.Combine(_root, "animated-cover.mp4");
        await File.WriteAllBytesAsync(file, [1, 2, 3]);
        var first = await _catalog.LocateAsync(_root, "square");
        Assert.Same(first, await _catalog.LocateAsync(_root, "square"));
        Assert.Equal(1, _reader.Calls);
        await File.WriteAllBytesAsync(file, [4, 5, 6, 7]);
        var next = await _catalog.LocateAsync(_root, "square");
        Assert.NotEqual(first?.Asset.Sha256, next?.Asset.Sha256);
        Assert.Equal(2, _reader.Calls);
    }

    [Theory]
    [InlineData("../secret")]
    [InlineData("/etc/passwd")]
    [InlineData("square.mp4")]
    [InlineData("unknown")]
    public async Task VariantsCannotSupplyPaths(string variant) => Assert.Null(await _catalog.LocateAsync(_root, variant));

    [Fact]
    public async Task SidecarSymlinksAreNotServed()
    {
        if (OperatingSystem.IsWindows()) return;
        await File.WriteAllBytesAsync(Path.Combine(_root, "original.mp4"), [1]);
        File.CreateSymbolicLink(Path.Combine(_root, "animated-cover.mp4"), Path.Combine(_root, "original.mp4"));
        Assert.Null(await _catalog.LocateAsync(_root, "square"));
        Assert.Equal(0, _reader.Calls);
    }

    [Fact]
    public async Task InvalidMediaFallsBack()
    {
        await File.WriteAllBytesAsync(Path.Combine(_root, "animated-cover.mp4"), [1]);
        _reader.Fail = true;
        Assert.Null(await _catalog.LocateAsync(_root, "square"));
    }

    [Fact]
    public async Task RelativeAndMissingAlbumPathsAreRejected()
    {
        Assert.Null(await _catalog.LocateAsync(null, "square"));
        Assert.Null(await _catalog.LocateAsync("relative/album", "square"));
    }

    public void Dispose() { _catalog.Dispose(); Directory.Delete(_root, recursive: true); }
    private sealed class FakeReader : IArtworkMetadataReader
    {
        public int Calls { get; private set; }
        public bool Fail { get; set; }
        public Task<ArtworkDimensions> ReadAsync(string path, CancellationToken cancellationToken)
        {
            Calls++;
            if (Fail) throw new InvalidDataException("Not a video");
            return Task.FromResult(new ArtworkDimensions(1080, 1080, 3));
        }
    }
}
