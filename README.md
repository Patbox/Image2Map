# Image2Map
<img src="https://cdn.discordapp.com/attachments/705864145169416313/969720133998239794/fabric_supported.png" width="128">
<img src="https://cdn.discordapp.com/attachments/705864145169416313/969716884482183208/quilt_supported.png" width="128">

A Fabric mod that allows you to render an image onto a map(s), allowing you to display it on your vanilla compatible server!

![Some images](https://raw.githubusercontent.com/TheEssem/Image2Map/master/images.png)
![More images](https://imgur.com/qy8JF5B.png)

## Commands:
- `/image2map create <WIDTH> <HEIGHT> <[dither/none]> <URL>` - Creates map of specified size (in pixels, single map is 128x128), with/without dither, using provided image
- `/image2map create <[dither/none]> <URL>` - Creates map with/without dither, using provided image
- `/image2map preview <URL>` - Creates dynamic preview before saving the map as item

### Commands in preview mode
- `/dither <[dither/none]>` - Changes dither mode
- `/size` - Displays current size
- `/size <WIDTH> <HEIGHT>` - Changes size of map to specified one (in pixels, single map is 128x128)
- `/grid` - Toggles visibility of map grid
- `/save` - Exits preview and saves map as items
- `/exit` - Exits preview without saving

### Multimaps
In case of maps bigger than 128x128 pixels, you will get them in a bundle. 
Clicking with it on top-left corner of item frames will put all maps in correct places.
Works for any item frame on wall, floor or ceiling.

## Downloads:
- Modrinth: https://modrinth.com/mod/image2map
- Curseforge: https://www.curseforge.com/minecraft/mc-mods/image2map


Will you port this to Forge/Bukkit/Paper?  
   ![no lol](https://i.imgur.com/tf5W69k.png)