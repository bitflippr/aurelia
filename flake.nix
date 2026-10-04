{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    rust-overlay.url = "github:oxalica/rust-overlay";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    nixpkgs,
    rust-overlay,
    flake-utils,
    ...
  }:
    flake-utils.lib.eachDefaultSystem (
      system: let
        inherit (nixpkgs) lib;

        pkgs = import nixpkgs {
          inherit system;
          overlays = [(import rust-overlay)];
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        rustToolchain = pkgs.rust-bin.stable.latest.default.override {
          extensions = ["rust-src" "rustfmt" "clippy"];
          targets =
            [
              "aarch64-linux-android"
              "x86_64-linux-android"
            ]
            ++ lib.optionals pkgs.stdenv.isDarwin [
              "aarch64-apple-ios"
              "aarch64-apple-ios-sim"
              "x86_64-apple-ios"
            ];
        };

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          cmdLineToolsVersion = "13.0";
          platformToolsVersion = "36.0.1";
          buildToolsVersions = ["36.0.0"];
          platformVersions = ["36"];
          abiVersions = ["arm64-v8a" "x86_64"];
          includeNDK = true;
          ndkVersions = ["29.0.14206865"];
        };

        androidSdk = androidComposition.androidsdk;

        desktopLinuxLibraries = with pkgs; [
          alsa-lib
          dbus
          fontconfig
          freetype
          glib
          libdrm
          libgbm
          libglvnd
          libxkbcommon
          vulkan-loader
          wayland
          libx11
          libxcomposite
          libxdamage
          libxext
          libxfixes
          libxrandr
          libxcb
        ];
      in {
        packages.android-emulator-sdk = (pkgs.androidenv.composeAndroidPackages {
          platformVersions = ["36"];
          abiVersions = ["x86_64"];
          includeEmulator = true;
          includeSystemImages = true;
          systemImageTypes = ["google_apis"];
        }).androidsdk;

        devShells.default = pkgs.mkShell {
          packages = with pkgs;
            [
              rustToolchain
              jdk17
              cargo-ndk
              bun
            ]
            ++ lib.optionals stdenv.isLinux ([android-studio pkg-config] ++ desktopLinuxLibraries);

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_NDK_HOME = "${androidSdk}/libexec/android-sdk/ndk/29.0.14206865";
          JAVA_HOME = pkgs.jdk17.home;
          LD_LIBRARY_PATH = lib.optionalString pkgs.stdenv.isLinux "${
            lib.makeLibraryPath desktopLinuxLibraries
          }:/run/opengl-driver/lib";

          shellHook = ''
            export PATH="${androidSdk}/libexec/android-sdk/platform-tools:$PATH"
            ${lib.optionalString pkgs.stdenv.isDarwin ''
              unset SDKROOT
              unset DEVELOPER_DIR
              export SOURCEKIT_TOOLCHAIN_PATH="/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain"
            ''}
          '';
        };
      }
    );
}
