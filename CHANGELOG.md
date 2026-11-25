# SusAC+ 2.0 (patch)

- BoatFly: a boat sitting over a 1x1 hole is no longer flagged. The check now looks at the whole boat hitbox
  (it is wider than the hole), not just the centre point.
- New command `/sus test <player>` (sus.admin): removes a totem from their hand and times how fast one is put
  back, to check for auto totem. Reports only to you, never flags, and always returns the totem.

# SusAC+ 2.0

Upgrade of SusAC 1.5. Same plugin, same `/sus` menu, same flags.yml - it only ALERTS staff, it never punishes.

## New engine
- Movement checks now run per movement packet (PlayerMoveEvent) instead of once per server tick, and use real
  vanilla physics (friction, gravity, collision shapes) instead of one flat speed number.
- Reach is lag-compensated against the target's recent positions (3.35 default, was 3.9).
- Risk score per player (/sus top, shown in /sus <player> and the GUI) + "likely cheat client" alert when one
  player trips 3 different detections in 2 minutes.

## New detections
Movement: Timer / ClickTP / Blink, Speed (ground + air), Fly (gravity), Step, NoFall (ground spoof + no damage),
Jesus, NoSlow, InvMove, AirStuck, ElytraFly, FakeLag, VehicleSpeed, BoatFly, BoatNoclip, EntityControl (no saddle)
Combat: SilentAim (silent rotation snap-back), AimAssist, HitRay, NoSwing, CrystalAura, AnchorMacro, AutoTotem,
AutoSwitch, Velocity (anti-knockback / knockback delay), InvAction
World/Player: XRayPath (+ better XRay), PlaceRay, FastUse, InvSpam, MenuMacro, ClientBrand, CheatClientAd (chat)
Improved: AutoClicker (steady-CPS), MultiAura window, BreakReach default, spam detection catches near-identical text.

New config keys use built-in defaults, so your existing config.yml keeps working. Delete config.yml once to
regenerate it with all the new options documented.

Hopper boost addon removed completely.
