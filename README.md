# PanShot

PanShot is a client-side Fabric mod that captures single images and panorama cubemaps from a camera detached from the local player. The camera remains at a fixed position while you continue playing.

Captured images are published to viewers on localhost. The viewers support optional reference-image and reference-cubemap comparison for screenshot recreation.

## Features

- Automatic single-image and panorama capture at a configurable interval
- Independent camera position, rotation, field of view, and resolution
- Local single-image and 360-degree cubemap viewers
- Optional browser-side player following that preserves the chosen zoom
- Reference-image comparison and perspective import
- Custom resource packs, render distance, entities, and optional player rendering
- Configurable downscaling and image-compression emulation
- Panorama cubemap export

## Requirements

- Minecraft Java Edition 1.21.10
- Java 21
- Gradle 8.14.3 when building from source
- Fabric Loader 0.17.3 or newer
- Fabric API for Minecraft 1.21.10

## Installation

1. Install Fabric Loader and Fabric API for Minecraft 1.21.10.
2. Place `panshot-1.0.0.jar` in the Minecraft `mods` directory.
3. Start Minecraft using the Fabric profile.

## Building

With Gradle 8.14.3 installed, run from the project directory:

```sh
gradle build
```

The built mod and sources JARs are written to `build/libs`.

## Usage

PanShot commands are client-side commands. Common examples:

```text
/panshot clipboard
/panshot panorama every 5
/panshot single every 5
/panshot single downscale 2 faces box
/panshot single compression 25
/panshot single compression video 70
/panshot single compression yt 70
/panshot panorama downscale 2.0 cubemap bicubic
/panshot panorama resolution 2048
/panshot panorama nudge 0.05
```

`/panshot clipboard` imports compatible perspective data from the clipboard to automate camera setup.

The panorama viewer's **Follow player** option rotates only the browser view toward the player's current position. It preserves the browser's chosen zoom and does not move, attach, or change the perspective of the in-game capture camera.

## Single-image compression

Compression amounts range from 0 to 100. Higher values produce stronger degradation.

| Mode | Command | Behavior |
| --- | --- | --- |
| Off | `/panshot single compression off` | Lossless PNG output |
| JPEG | `/panshot single compression jpeg <amount>` | Configurable JPEG quality; defaults to 25% compression |
| Video | `/panshot single compression video <amount>` | Low-bitrate video-style macroblocking, chroma loss, quantization, and motion-aware temporal smearing; defaults to 70% |
| YT | `/panshot single compression yt <amount>` | Old 360p-era screen-video softness, blocking, banding, chroma bleed, and temporal smearing; defaults to 70% |

`/panshot single compression <amount>` is a shortcut for JPEG mode. Running `/panshot single compression` enables JPEG using the current amount.

Video and YT modes use a self-contained Java filter on the background encoding thread and do not require FFmpeg or another native dependency. Use `compression off` when exact lossless output is required.

The YT filter temporarily reduces detail internally and scales it back to the configured capture dimensions. It does not change the delivered image dimensions. Only the explicit `single downscale` command reduces them.

## Panorama recreation example

Release the mouse before starting capture to avoid moving the camera after alignment.

```text
/tp @s -255.5281246385249 126 -2006.420387290336 -271.80280706639144 0
/panshot panorama resolution 4096
/panshot panorama downscale 4 faces box
/panshot panorama every 1
```

## License

PanShot is available under the [MIT License](LICENSE).

## Optional local Gradle wrapper

The Gradle wrapper files are intentionally excluded from this repository. To create a local wrapper, install Gradle 8.14.3 and run this command from the project directory:

```sh
gradle :wrapper --gradle-version 8.14.3 --distribution-type bin
```

After generation, run Gradle through the wrapper on Windows:

```powershell
.\gradlew.bat build
```

Or on Linux and macOS:

```sh
chmod +x gradlew
./gradlew build
```

To launch the mod in a development client, run `.\gradlew.bat runClient` on Windows or `./gradlew runClient` on Linux and macOS.

The generated wrapper files remain ignored by Git and are only used locally.
