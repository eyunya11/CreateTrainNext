# License Overview

## Code License (MIT)

This license applies to all files under `src/`, except the resource files
listed under Assets License below.

MIT License

Copyright (c) 2026 eyunya

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---

### Third-Party Notice

This mod is an addon for [Create](https://github.com/Creators-of-Create/Create)
and depends on it at runtime; no Create code or assets are bundled into this
project's build output.

A small number of files carry logic adapted from Create's own MIT-licensed
source rather than written independently — reusing the shape of a check or a
default implementation Create already had, changed only where this mod's
blocks don't fit Create's assumptions. Under the MIT License, adapting that
code carries forward Create's own copyright notice alongside this project's:

Copyright (c) The Create Team / The Creators of Create

The files in question, and what they carry over:

- `src/main/java/com/eyunya/createtrainnext/track/AngledTrackAnchorBlock.java`
  — `getConnected` follows `ITrackBlock`'s default implementation, with the
  `SHAPE`-based up-normal lookup swapped for this block's own.
- `src/main/java/com/eyunya/createtrainnext/track/AnchorShapes.java`
  — the hitbox is built the same way `TrackVoxelShapes` builds Create's,
  generalised from four fixed axes to an arbitrary angle.

## Assets License (All Rights Reserved)

This license applies to any files under `src/main/resources/assets/`.

All Rights Reserved

Copyright (c) 2026 eyunya

No part of these files may be reproduced, distributed, or used to create
derivative works without prior written permission from the copyright holder.
