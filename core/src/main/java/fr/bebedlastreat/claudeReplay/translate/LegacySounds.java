package fr.bebedlastreat.claudeReplay.translate;

import com.github.retrooper.packetevents.protocol.sound.SoundCategory;

import java.util.HashMap;
import java.util.Map;

/**
 * Names of the 1.8 sounds ("random.click", "mob.zombie.say"...) in the modern sound registry. Covers the sounds
 * heard in most games; the others are dropped.
 */
final class LegacySounds {

    private static final Map<String, String> SOUNDS = new HashMap<>();
    private static final Map<String, String> MOBS = new HashMap<>();
    private static final Map<String, String> MOB_EVENTS = new HashMap<>();

    static {
        String[][] materials = {
                {"cloth", "wool"}, {"grass", "grass"}, {"gravel", "gravel"}, {"sand", "sand"}, {"snow", "snow"},
                {"stone", "stone"}, {"wood", "wood"}, {"glass", "glass"}, {"ladder", "ladder"}
        };
        for (String[] material : materials) {
            SOUNDS.put("step." + material[0], "block." + material[1] + ".step");
            SOUNDS.put("dig." + material[0], "block." + material[1] + ".break");
        }
        String[][] sounds = {
                {"random.click", "block.stone_button.click_on"}, {"random.wood_click", "block.wooden_button.click_on"},
                {"random.bow", "entity.arrow.shoot"}, {"random.bowhit", "entity.arrow.hit"},
                {"random.successful_hit", "entity.arrow.hit_player"}, {"random.break", "entity.item.break"},
                {"random.burp", "entity.player.burp"}, {"random.chestopen", "block.chest.open"},
                {"random.chestclosed", "block.chest.close"}, {"random.door_open", "block.wooden_door.open"},
                {"random.door_close", "block.wooden_door.close"}, {"random.drink", "entity.generic.drink"},
                {"random.eat", "entity.generic.eat"}, {"random.explode", "entity.generic.explode"},
                {"random.fizz", "block.fire.extinguish"}, {"random.fuse", "entity.tnt.primed"},
                {"random.glass", "block.glass.break"}, {"random.levelup", "entity.player.levelup"},
                {"random.orb", "entity.experience_orb.pickup"}, {"random.pop", "entity.item.pickup"},
                {"random.splash", "entity.generic.splash"}, {"random.swim", "entity.generic.swim"},
                {"random.anvil_land", "block.anvil.land"}, {"random.anvil_use", "block.anvil.use"},
                {"random.anvil_break", "block.anvil.destroy"}, {"random.classic_hurt", "entity.player.hurt"},
                {"game.player.hurt", "entity.player.hurt"}, {"game.player.die", "entity.player.death"},
                {"game.player.hurt.fall.big", "entity.player.big_fall"}, {"game.player.hurt.fall.small", "entity.player.small_fall"},
                {"game.player.swim", "entity.player.swim"}, {"game.player.swim.splash", "entity.player.splash"},
                {"game.neutral.hurt", "entity.generic.hurt"}, {"game.neutral.die", "entity.generic.death"},
                {"game.neutral.hurt.fall.big", "entity.generic.big_fall"}, {"game.neutral.hurt.fall.small", "entity.generic.small_fall"},
                {"game.hostile.hurt", "entity.hostile.hurt"}, {"game.hostile.die", "entity.hostile.death"},
                {"game.hostile.hurt.fall.big", "entity.hostile.big_fall"}, {"game.hostile.hurt.fall.small", "entity.hostile.small_fall"},
                {"game.tnt.primed", "entity.tnt.primed"}, {"game.potion.smash", "entity.splash_potion.break"},
                {"damage.hit", "entity.player.hurt"}, {"damage.fallbig", "entity.player.big_fall"},
                {"damage.fallsmall", "entity.player.small_fall"},
                {"fire.fire", "block.fire.ambient"}, {"fire.ignite", "item.flintandsteel.use"},
                {"liquid.water", "block.water.ambient"}, {"liquid.lava", "block.lava.ambient"},
                {"liquid.lavapop", "block.lava.pop"}, {"liquid.splash", "entity.generic.splash"},
                {"liquid.swim", "entity.generic.swim"},
                {"note.harp", "block.note_block.harp"}, {"note.bd", "block.note_block.basedrum"},
                {"note.snare", "block.note_block.snare"}, {"note.hat", "block.note_block.hat"},
                {"note.bassattack", "block.note_block.bass"}, {"note.bass", "block.note_block.bass"},
                {"note.pling", "block.note_block.pling"},
                {"ambient.weather.rain", "weather.rain"}, {"ambient.weather.thunder", "entity.lightning_bolt.thunder"},
                {"ambient.cave.cave", "ambient.cave"}, {"portal.portal", "block.portal.ambient"},
                {"portal.travel", "block.portal.travel"}, {"portal.trigger", "block.portal.trigger"},
                {"tile.piston.in", "block.piston.contract"}, {"tile.piston.out", "block.piston.extend"},
                {"minecart.base", "entity.minecart.riding"}, {"minecart.inside", "entity.minecart.inside"},
                {"mob.endermen.portal", "entity.enderman.teleport"}, {"mob.irongolem.throw", "entity.iron_golem.attack"},
                {"mob.creeper.say", "entity.creeper.hurt"}, {"mob.slime.big", "entity.slime.squish"},
                {"mob.slime.small", "entity.slime.squish_small"}, {"mob.slime.attack", "entity.slime.attack"},
                {"mob.magmacube.big", "entity.magma_cube.squish"}, {"mob.magmacube.small", "entity.magma_cube.squish_small"},
                {"mob.wolf.bark", "entity.wolf.ambient"}, {"mob.wolf.growl", "entity.wolf.growl"},
                {"mob.cat.meow", "entity.cat.ambient"}, {"mob.cat.hitt", "entity.cat.hurt"},
                {"mob.villager.haggle", "entity.villager.trade"}, {"mob.villager.idle", "entity.villager.ambient"},
                {"mob.villager.yes", "entity.villager.yes"}, {"mob.villager.no", "entity.villager.no"},
                {"mob.ghast.moan", "entity.ghast.ambient"}, {"mob.ghast.scream", "entity.ghast.hurt"},
                {"mob.ghast.fireball", "entity.ghast.shoot"}, {"mob.blaze.breathe", "entity.blaze.ambient"},
                {"mob.chicken.plop", "entity.chicken.egg"}, {"mob.sheep.shear", "entity.sheep.shear"},
                {"mob.zombie.wood", "entity.zombie.attack_wooden_door"}, {"mob.zombie.woodbreak", "entity.zombie.break_wooden_door"},
                {"mob.enderdragon.growl", "entity.ender_dragon.growl"}, {"mob.enderdragon.wings", "entity.ender_dragon.flap"},
        };
        for (String[] sound : sounds) {
            SOUNDS.put(sound[0], sound[1]);
        }
        String[][] mobs = {
                {"endermen", "enderman"}, {"irongolem", "iron_golem"}, {"zombiepig", "zombified_piglin"},
                {"magmacube", "magma_cube"}, {"enderdragon", "ender_dragon"}, {"cavespider", "cave_spider"}
        };
        for (String[] mob : mobs) {
            MOBS.put(mob[0], mob[1]);
        }
        String[][] events = {
                {"say", "ambient"}, {"idle", "ambient"}, {"living", "ambient"}, {"hurt", "hurt"}, {"hit", "hurt"},
                {"death", "death"}, {"step", "step"}, {"walk", "step"}
        };
        for (String[] event : events) {
            MOB_EVENTS.put(event[0], event[1]);
        }
    }

    private LegacySounds() {
    }

    /** Modern key of a 1.8 sound, null if unknown. */
    static String modernName(String legacy) {
        String name = legacy.startsWith("minecraft:") ? legacy.substring("minecraft:".length()) : legacy;
        String known = SOUNDS.get(name);
        if (known != null) {
            return known;
        }
        String[] parts = name.split("\\.");
        if (parts.length >= 3 && parts[0].equals("mob")) {
            String event = MOB_EVENTS.get(parts[parts.length - 1]);
            if (event != null) {
                return "entity." + MOBS.getOrDefault(parts[1], parts[1]) + "." + event;
            }
        }
        return null;
    }

    static SoundCategory category(String legacy) {
        if (legacy.startsWith("step.") || legacy.startsWith("dig.") || legacy.startsWith("tile.")) {
            return SoundCategory.BLOCK;
        }
        if (legacy.startsWith("game.player") || legacy.startsWith("damage.")) {
            return SoundCategory.PLAYER;
        }
        if (legacy.startsWith("game.hostile") || legacy.startsWith("mob.")) {
            return SoundCategory.HOSTILE;
        }
        if (legacy.startsWith("game.neutral")) {
            return SoundCategory.NEUTRAL;
        }
        if (legacy.startsWith("note.")) {
            return SoundCategory.RECORD;
        }
        if (legacy.startsWith("ambient.")) {
            return SoundCategory.AMBIENT;
        }
        return SoundCategory.MASTER;
    }
}
