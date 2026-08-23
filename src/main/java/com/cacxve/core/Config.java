package com.cacxve.core;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.registries.ForgeRegistries;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Forge's config APIs
public class Config
{
        private static final ForgeConfigSpec.Builder SERVER_BUILDER = new ForgeConfigSpec.Builder();

        public static final ForgeConfigSpec.BooleanValue ALLOW_PUBLIC_CARAVAN_DOCKS = SERVER_BUILDER
                        .comment("Allow any player to access and edit player-owned caravan docks; village docks remain protected")
                        .define("allowPublicCaravanDocks", false);
        static final ForgeConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

        public static boolean allowPublicCaravanDocks()
        {
                return ALLOW_PUBLIC_CARAVAN_DOCKS.get();
        }

        public static boolean canAccessCaravanDock(UUID owner, UUID player, boolean villageDock, boolean allowPublic)
        {
                if (villageDock)
                        return false;
                if (player == null)
                        return owner == null || player != null && player.equals(owner);
                return owner == null || player.equals(owner) || allowPublic;
        }

    public static List<VillageTypeDefinition> villageTypes = List.of(
            new VillageTypeDefinition("farming", "Farming"),
            new VillageTypeDefinition("forestry", "Forestry"),
            new VillageTypeDefinition("mining", "Mining"),
            new VillageTypeDefinition("merchant", "Merchant"),
            new VillageTypeDefinition("military", "Military")
    );

    public record VillageTypeDefinition(String id, String displayName)
    {
    }

        public static List<TradeDefinition> villageTrades = List.of();
        private static volatile Map<String, List<TradeDefinition>> tradesByType = Map.of();

                                public record TradeDefinition(String id, String villageExportItem, String villageImportItem,
                                              int villageExportQuantity, int villageImportQuantity,
                                              String villageType)
        {
        }

        public static TradeDefinition multiplyTrade(TradeDefinition trade, int multiplier)
        {
                if (multiplier != 1 && multiplier != 2 && multiplier != 3 && multiplier != 5 && multiplier != 10)
                        throw new IllegalArgumentException("Unsupported trade multiplier: " + multiplier);
                return new TradeDefinition(trade.id() + "_" + multiplier + "x", trade.villageExportItem(),
                                trade.villageImportItem(), Math.multiplyExact(trade.villageExportQuantity(), multiplier),
                                Math.multiplyExact(trade.villageImportQuantity(), multiplier), trade.villageType());
        }

        public static void setVillageTrades(List<TradeDefinition> trades)
        {
                villageTrades = List.copyOf(trades);
                Map<String, List<TradeDefinition>> index = new HashMap<>();
                for (TradeDefinition trade : villageTrades)
                        index.computeIfAbsent(trade.villageType().toLowerCase(java.util.Locale.ROOT), ignored -> new java.util.ArrayList<>())
                                        .add(trade);
                Map<String, List<TradeDefinition>> immutable = new HashMap<>();
                index.forEach((type, entries) -> immutable.put(type, List.copyOf(entries)));
                tradesByType = Map.copyOf(immutable);
        }

        public static List<TradeDefinition> tradesForTypes(Set<String> types)
        {
                List<TradeDefinition> candidates = new java.util.ArrayList<>();
                candidates.addAll(tradesByType.getOrDefault("", List.of()));
                for (String type : types)
                        candidates.addAll(tradesByType.getOrDefault(type.toLowerCase(java.util.Locale.ROOT), List.of()));
                Map<String, TradeDefinition> byPair = new HashMap<>();
                for (TradeDefinition trade : candidates)
                            byPair.put(trade.villageExportItem() + "\u0000" + trade.villageImportItem(), trade);
                return List.copyOf(byPair.values());
        }

        public static List<TradeDefinition> expandTradeEconomies(List<TradeDefinition> base)
        {
                List<TradeDefinition> expanded = new java.util.ArrayList<>(base);
                for (TradeDefinition trade : base)
                {
                        int reverseEmerald = Math.max(1, trade.villageImportQuantity() / 2);
                        expanded.add(new TradeDefinition(trade.id() + "_reverse", "minecraft:emerald",
                                trade.villageExportItem(), reverseEmerald, trade.villageExportQuantity(),
                                ""));
                }
                return List.copyOf(expanded);
        }

        public static TradeDefinition computeOffer(String exportItem, String wantedItem, Set<String> types)
        {
                if (exportItem == null || wantedItem == null || exportItem.isBlank() || wantedItem.isBlank()
                        || exportItem.equals(wantedItem))
                        return null;
                TradeDefinition sell = villageTrades.stream()
                        .filter(trade -> trade.villageImportItem().equals(exportItem))
                        .filter(trade -> "minecraft:emerald".equals(trade.villageExportItem()))
                        .findFirst().orElse(null);
                TradeDefinition buy = tradesForTypes(types).stream()
                        .filter(trade -> trade.villageExportItem().equals(wantedItem))
                        .filter(trade -> "minecraft:emerald".equals(trade.villageImportItem()))
                        .findFirst().orElse(null);
                if (sell == null || buy == null)
                        return null;
                long budget = lcm(sell.villageExportQuantity(), buy.villageImportQuantity());
                long exportQuantity = budget / sell.villageExportQuantity() * sell.villageImportQuantity();
                long importQuantity = budget / buy.villageImportQuantity() * buy.villageExportQuantity();
                if (exportQuantity <= 0 || importQuantity <= 0
                        || exportQuantity > Integer.MAX_VALUE || importQuantity > Integer.MAX_VALUE)
                        return null;
                return new TradeDefinition("computed_" + exportItem.replace(":", "_")
                                + "_for_" + wantedItem.replace(":", "_"), wantedItem, exportItem,
                                (int) importQuantity, (int) exportQuantity, "");
        }

        private static long lcm(int a, int b)
        {
                return (long) a / gcd(a, b) * b;
        }

        private static int gcd(int a, int b)
        {
                a = Math.abs(a);
                b = Math.abs(b);
                while (b != 0)
                {
                        int remainder = a % b;
                        a = b;
                        b = remainder;
                }
                return Math.max(1, a);
        }

        private static boolean validateItemName(final Object obj)
    {
                return obj instanceof final String itemName && ForgeRegistries.ITEMS.containsKey(ResourceLocation.tryParse(itemName));
    }

        public static List<TradeDefinition> parseVillageTrades(String json)
        {
                List<TradeDefinition> trades = new java.util.ArrayList<>();
                try
                {
                        JsonArray entries = JsonParser.parseString(json).getAsJsonArray();
                        for (JsonElement entry : entries)
                                addParsedTrade(trades, entry);
                }
                catch (Exception ignored)
                {
                }
                return List.copyOf(trades);
        }

        private static void addParsedTrade(List<TradeDefinition> trades, JsonElement entry)
        {
                JsonObject object = entry.getAsJsonObject();
                String id = object.has("id") ? object.get("id").getAsString() : "trade_" + trades.size();
                String exportItem = object.get("villageExportItem").getAsString();
                String importItem = object.get("villageImportItem").getAsString();
                int exportQuantity = object.get("villageExportQuantity").getAsInt();
                int importQuantity = object.get("villageImportQuantity").getAsInt();
                String villageType = object.has("villageType") ? object.get("villageType").getAsString() : "";
                if (!exportItem.isBlank() && !importItem.isBlank() && exportQuantity > 0 && importQuantity > 0)
                        trades.add(new TradeDefinition(id, exportItem, importItem, exportQuantity, importQuantity, villageType));
        }

        public static List<TradeDefinition> loadTradeCatalog(Path configDirectory)
        {
                List<TradeDefinition> base = new java.util.ArrayList<>();
                List<String> types = List.of("farming", "forestry", "mining", "merchant", "military");
                base.addAll(readTradeFile(configDirectory.resolve("cacxvecompat/trades/generic.json"), ""));
                for (String type : types)
                {
                        base.addAll(readTradeFile(configDirectory.resolve("cacxvecompat/trades/" + type + ".json"), type));
                }
                return expandTradeEconomies(base);
        }

        private static List<TradeDefinition> readTradeFile(Path path, String type)
        {
                try
                {
                        String json;
                        if (Files.isRegularFile(path))
                                json = Files.readString(path);
                        else
                        {
                                var resource = Config.class.getResourceAsStream(
                                        "/config/cacxvecompat/trades/" + path.getFileName());
                                if (resource == null)
                                        return List.of();
                                json = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                        }
                        return parseVillageTrades(json).stream()
                                .map(trade -> new TradeDefinition(trade.id(), trade.villageExportItem(),
                                        trade.villageImportItem(), trade.villageExportQuantity(),
                                        trade.villageImportQuantity(), type))
                                .filter(trade -> validateItemName(trade.villageExportItem())
                                        && validateItemName(trade.villageImportItem()))
                                .toList();
                }
                catch (Exception ignored)
                {
                        return List.of();
                }
        }

                public static List<VillageTypeDefinition> loadVillageTypes(Path path)
                {
                        if (!Files.isRegularFile(path))
                                return villageTypes;
                        try (Reader reader = Files.newBufferedReader(path))
                        {
                                JsonArray entries = JsonParser.parseReader(reader).getAsJsonArray();
                                List<VillageTypeDefinition> types = new java.util.ArrayList<>();
                                for (JsonElement entry : entries)
                                {
                                        JsonObject object = entry.getAsJsonObject();
                                        String id = object.get("id").getAsString();
                                        String displayName = object.has("displayName") ? object.get("displayName").getAsString() : id;
                                        if (!id.isBlank())
                                                types.add(new VillageTypeDefinition(id, displayName));
                                }
                                return types.isEmpty() ? villageTypes : List.copyOf(types);
                        }
                        catch (Exception ignored)
                        {
                                return villageTypes;
                        }
                }
}
