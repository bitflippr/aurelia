// Adapted from Apple's WWDC26 "Compose advanced graphics effects with SwiftUI" sample.
// PlayerNoise.png is a reduced-resolution version of its noise texture.
// See AppleSampleLicense.txt for the copyright and permission notice.
#include <metal_stdlib>
#include <SwiftUI/SwiftUI.h>
using namespace metal;

[[stitchable]] half4 playerArtworkWarp(
    float2 position, SwiftUI::Layer layer, float2 size,
    texture2d<half> noiseTexture, float time
) {
    constexpr sampler noiseSampler(address::repeat, filter::linear);
    float2 uv = position / max(size, float2(1.0));
    float2 first = float2(noiseTexture.sample(
        noiseSampler, uv * 0.05 + float2(time * 0.05, time * 0.03)).rg);
    float2 second = float2(noiseTexture.sample(
        noiseSampler, uv * 0.1 + first * 2.0 + float2(time * 0.02, 0.0)).rg);
    // Keep sampling inside the image so the moving field never reveals transparent edges.
    float2 source = clamp((second - 0.5) * 1.2 + 0.5, float2(0.002), float2(0.998));
    return layer.sample(source * size);
}
