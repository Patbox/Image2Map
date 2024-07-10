# Image2Map - Zgell's Fork
A fork of the Minecraft mod "[Image2Map](https://github.com/Patbox/Image2Map)" by Patbox.

The motivation for forking this mod was to address an issue with the mod that can arise in what I call "low-trust Minecraft servers".
The original mod is a very fun addition to Minecraft servers, but it suffers from a memory-related issue that can cause server crashes if a player creates an image that is too large.
This is a problem for public Minecraft servers that want to make this mod available to all of its players, as ANY malicious/untrustworthy player could crash the server, or worse.

This is a sloppy hotfix to the original mod that makes one change: all images, regardless of original size, are scaled down to 128x128, the size of a single map item.
This prevents the memory issue as it is related to the multi-map feature.
This does restrict the functionality of the mod considerably, so this version is unlikely to be useful to anyone else but me.

## Command Syntax
- `/image2map create <[dither/none]> <URL>` - Creates **singular map item**, with/without dither, using provided image

This mod maintains the original syntax of the command to keep it familiar with players who are already familiar with the mod.

## Links to the Original Mod:
The original mod can be downloaded at either [Modrinth](https://modrinth.com/mod/image2map) or [Curseforge](https://www.curseforge.com/minecraft/mc-mods/image2map).
