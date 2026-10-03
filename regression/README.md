# Placement regression

Run from the repository with Java 25 and the cached project dependencies:

```powershell
.\gradlew.bat -I regression/placement.init.gradle runClient --offline
```

This opt-in harness runs Minecraft 26.2 in `D:\Games\Minecraft\.minecraft\versions\26.2-test`, muted. It starts a temporary vanilla server bound to loopback on a free port, uses generated Litematica fixtures, and stops both test processes when finished. Release builds do not include the test entrypoint.

Active Fabric API, Carpet and MiniHUD JARs are selected by mod identity from the test instance into a generated mods directory. Litematica and malilib are supplied by the project's Gradle dependencies; the instance's release Simulatica JAR is excluded from development loading. No existing saves or schematic files are edited. A fresh remote world is kept under the test instance's `simulatica/regression` directory.

Checks:

- Multi-region translation in both directions with one destination overlapping another source: live block states, chest inventory, one entity within both region tracking margins, scheduled block/fluid ticks and block events.
- Whole-placement and sub-region rotation/mirror, including unchanged bounding boxes and a combined rotation/translation: simulation bridges rebuild and acquire the transformed block direction; old simulated entities and scheduled ticks are discarded. Sub-region movement and placement/region enable toggles are also covered.
- Enter workshop, discard, edit placement position, disconnect and reconnect: save permission restores and the edited position persists.
- Failed startup, apply return and client shutdown restore save permission; an initially disabled save permission stays disabled.

`build/placement-regression/result.txt` contains PASS/FAIL and individual results. `run.log` (when shell output is redirected there) and `remote.log` provide client/server diagnostics. Exceptions, timeouts, missing results and failed assertions fail the Gradle task. Startup errors explicitly injected by the last checks are expected diagnostics.
