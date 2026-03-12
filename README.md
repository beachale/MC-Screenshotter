# PanShot (Fabric)

This mod detaches rendering from the local player by swapping `MinecraftClient` camera entity to a client-side spectator camera.

It's a mod which auto-captures single screenshot or panorama cubemap separate from the player's pov, so you can keep playing and the screenshots will be taken in a static position at whatever interval you choose.

Generates a local 360 cubemap viewer or single image viewer with optional reference image/cubemap comparison for easy recreation.
Essentially works like n00bbot + cubemap viewer, except it works entirely on the client side.

Supports custom resource packs, render distance, entities, player rendering and cubemap export.

Vibecoded, of course, but works fine.
More features may come in the future.


Usage

- `/panshot panorama start every 5`

- `/panshot single every 5`

For a panorama recreation setup for e.g. 26.1, use this setup:
Make sure to let go of mouse to prevent misaligning the camera

`/tp @s -255.5281246385249 126 -2006.420387290336 -271.80280706639144 0`

`/panshot panorama resolution 4096`

`/panshot panorama downscale 4 faces box`

`/panshot panorama every 1`
