package me.fix.susac;

import java.util.HashMap;
import java.util.Map;

/** How much each check is worth when building a player's risk score (1 = weak signal, 5 = near-certain). */
public final class Severity {
    private static final Map<String, Integer> W = new HashMap<>();

    private static void w(int v, String... checks) {
        for (String c : checks) W.put(c, v);
    }

    static {
        w(5, "SilentAim", "ClientBrand", "CheatClientAd");
        w(4, "Fly", "Timer", "AirStuck", "Reach", "MultiAura", "CrystalAura", "AnchorMacro", "AutoTotem",
                "BoatFly", "BoatNoclip", "EntityControl", "XRay", "XRayPath", "InvAction");
        w(3, "Speed", "NoFall", "Jesus", "NoSlow", "InvMove", "ElytraFly", "VehicleSpeed", "HitRay", "NoSwing",
                "AutoSwitch", "Velocity", "WallHit", "Nuker", "BreakReach", "MenuMacro", "WallTrack", "AutoWalk");
        w(2, "Step", "FakeLag", "AimAssist", "AutoClicker", "MacroClick", "PlaceRay", "FastUse", "FastPlace",
                "FastEat", "OmniSprint", "InvSpam", "AltLimit");
        w(1, "ChatSpam", "ChatFlood", "ChatCaps", "Advertising", "Doxing", "DeathThreat", "HateSpeech", "Profanity");
    }

    private Severity() {}

    public static int of(String check) {
        return W.getOrDefault(check, 2);
    }

    public static String label(double risk) {
        if (risk >= 90) return "&4&lCRITICAL";
        if (risk >= 40) return "&cHIGH";
        if (risk >= 15) return "&6MEDIUM";
        if (risk > 0) return "&eLOW";
        return "&7none";
    }
}
