Create Train Next
=======

An addon for [Create](https://github.com/Creators-of-Create/Create) that lets
train track run at arbitrary curve angles, instead of the 45 and 90 degree
steps Create's own track placement tools allow.

Targets Create 6.0.11 on Minecraft 1.21.1 / NeoForge 21.1.248, and requires
Create to be installed.

License
-------

Code is MIT; assets are All Rights Reserved. See [LICENSE.md](LICENSE.md) for
the split, and its Third-Party Notice for the handful of files that carry
logic adapted from Create's own MIT-licensed source.

Building from source
=======

This repository was started from the NeoForged MDK template, and the sections
below are unchanged from it.

Once you have your clone, simply open the repository in the IDE of your choice. The usual recommendation for an IDE is either IntelliJ IDEA or Eclipse.

If at any point you are missing libraries in your IDE, or you've run into problems you can
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything 
{this does not affect your code} and then start the process again.

Mapping Names:
============
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields 
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

Additional Resources: 
==========
Community Documentation: https://docs.neoforged.net/  
NeoForged Discord: https://discord.neoforged.net/
