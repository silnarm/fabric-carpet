package carpet.helpers;

import carpet.CarpetServer;
import carpet.fakes.IngredientInterface;
import carpet.fakes.RecipeManagerInterface;
import carpet.utils.WoolTool;
import carpet.utils.Messenger;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MaterialColor;
import org.apache.commons.lang3.StringUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.util.Map.entry;

/**
 * The actual object residing in each hopper counter which makes them count the items and saves them. There is one for each
 * colour in MC.
 */

public class HopperCounter
{
    /**
     * A map of all the {@link HopperCounter} counters.
     */
    public static final Map<DyeColor, HopperCounter> COUNTERS;

    /**
     * The default display colour of each item, which makes them look nicer when printing the counter contents to the chat
     */

    public static final TextColor WHITE = TextColor.fromLegacyFormat(ChatFormatting.WHITE);

    private static final EnumMap<DyeColor, String> dyeColourToPrettyColourNameMap = new EnumMap<DyeColor, String>(DyeColor.class);
    private static final EnumMap<DyeColor, String> dyeColourToPrettyColourTitleMap = new EnumMap<DyeColor, String>(DyeColor.class);
    private static final EnumMap<DyeColor, String> dyeColourToPrettyColourCodeMap = new EnumMap<DyeColor, String>(DyeColor.class);

    static
    {
        EnumMap<DyeColor, HopperCounter> counterMap = new EnumMap<>(DyeColor.class);
        for (DyeColor color : DyeColor.values())
        {
            counterMap.put(color, new HopperCounter(color));
            dyeColourToPrettyColourNameMap.put(color, WoolTool.Material2DyeName.getOrDefault(color.getMaterialColor(),"w ") + color.getName());
            dyeColourToPrettyColourCodeMap.put(color, WoolTool.Material2DyeName.getOrDefault(color.getMaterialColor(),"w "));
            dyeColourToPrettyColourTitleMap.put(color, WoolTool.Material2DyeName.getOrDefault(color.getMaterialColor(),"w ") + snakeCaseToTitle(color.getName()));
        }
        COUNTERS = Collections.unmodifiableMap(counterMap);
    }

    private static String snakeCaseToTitle(String in) {
        String[] bits = in.split("\\_");
        for (int i=0; i < bits.length; ++i) {
            bits[i] = StringUtils.capitalize(bits[i]);
        }
        return String.join(" ", bits);
    }

    /**
     * The counter's colour, determined by the colour of wool it's pointing into
     */
    public final DyeColor color;
    /**
     * The string which is passed into {@link Messenger#m} which makes each counter name be displayed in the colour of
     * that counter.
     */
    private final String prettyColour;
    /**
     * All the items stored within the counter, as a map of {@link Item} mapped to a {@code long} of the amount of items
     * stored thus far of that item type.
     */
    private final Object2LongMap<Item> inputCounter = new Object2LongLinkedOpenHashMap<>();
    private final Object2LongMap<Item> outputCounter = new Object2LongLinkedOpenHashMap<>();

    /**
     * The starting tick of the counter, used to calculate in-game time. Only initialised when the first item enters the
     * counter
     */
    private long startTick;
    /**
     * The starting millisecond of the counter, used to calculate IRl time. Only initialised when the first item enters
     * the counter
     */
    private long startMillis;
    // private PubSubInfoProvider<Long> pubSubProvider;

    private HopperCounter(DyeColor color)
    {
        startTick = -1;
        this.color = color;
        this.prettyColour = WoolTool.Material2DyeName.getOrDefault(color.getMaterialColor(),"w ") + color.getName();
    }

    /**
     * Method used to add items to the counter. Note that this is when the {@link HopperCounter#startTick} and
     * {@link HopperCounter#startMillis} variables are initialised, so you can place the counters and then start the farm
     * after all the collection is sorted out.
     */
    public void add(MinecraftServer server, ItemStack stack)
    {
        _add(server, stack, outputCounter);
    }

    public void addInput(MinecraftServer server, ItemStack stack)
    {
        _add(server, stack, inputCounter);
    }

    private void _add(MinecraftServer server, ItemStack stack, Object2LongMap<Item> map)
    {
        if (startTick < 0) {
            startTick = server.getLevel(Level.OVERWORLD).getGameTime();  //OW
            startMillis = System.currentTimeMillis();
        }
        Item item = stack.getItem();
        map.put(item, map.getLong(item) + stack.getCount());
    }

    /**
     * Resets the counter, clearing its items but keeping the clock running.
     */
    public void reset(MinecraftServer server)
    {
        outputCounter.clear();
        inputCounter.clear();
        startTick = server.getLevel(Level.OVERWORLD).getGameTime();  //OW
        startMillis = System.currentTimeMillis();
        // pubSubProvider.publish();
    }

    /**
     * Resets all counters, clearing their items.
     * @param fresh Whether or not to start the clocks going immediately or later.
     */
    public static void resetAll(MinecraftServer server, boolean fresh)
    {
        for (HopperCounter counter : COUNTERS.values())
        {
            counter.reset(server);
            if (fresh) counter.startTick = -1;
        }
    }

    /**
     * Prints all the counters to chat, nicely formatted, and you can choose whether to display it in game time or IRL time
     */
    public static List<Component> formatAllForChat(MinecraftServer server, boolean realtime)
    {
        List<Component> text = new ArrayList<>();
        var keys = DyeColor.values();
        for (var key : keys) {
            var text2 = COUNTERS.get(key).formatForChat(server, realtime);
            if (text2.isEmpty()) {
                continue;
            }
            text.addAll(text2);
        }
        if (text.isEmpty()) {
            text.add(Messenger.c("w No Input or Output Items for any colour have been counted yet."));
        }
        return text;
    }

    public List<Component> formatForChat(MinecraftServer server, boolean realTime) {
        List<Component> text = new ArrayList<>();
        long inputCount = getTotalInputItems();
        long outputCount = getTotalOutputItems();

        if (inputCount <= 0 && outputCount <= 0) {
            return text;
        }

        String io = inputCount > 0 ? "Input " + (outputCount > 0 ? "& Output" : "") : "Output";
        long ticks = Math.max(realTime ? (System.currentTimeMillis() - startMillis) / 50 : server.getLevel(Level.OVERWORLD).getGameTime() - startTick, 1);  //OW

        text.add(Messenger.c(
                dyeColourToPrettyColourTitleMap.get(color), "w  " + io + " Items (",
                String.format("wb %.2f", ticks*1.0/(20*60)), "w  min"+(realTime?" - real time":"")+") : ",
                "b" + dyeColourToPrettyColourCodeMap.get(color) + "[X]", "^g reset", "!/counter "+color.toString()+" reset"
        ));
        text.addAll(_formatItems(server, realTime));

        return text;
    }

    private List<Component> _formatItems(MinecraftServer server, boolean realTime)
    {
        List<Component> components = _formatItems(server, realTime, "Input", inputCounter);
        components.addAll(_formatItems(server, realTime, "Output", outputCounter));
        return components;
    }

    private List<Component> _formatItems(MinecraftServer server, boolean realTime, String type, Object2LongMap<Item> map) {
        long ticks = Math.max(realTime ? (System.currentTimeMillis() - startMillis) / 50 : server.getLevel(Level.OVERWORLD).getGameTime() - startTick, 1);  //OW
        long total = map.isEmpty() ? 0 : map.values().longStream().sum();
        if (total == 0) {
            return new ArrayList<>();
        }
        long typeCount = map.isEmpty() ? 0 : (long) map.values().size();

        List<Component> items = new ArrayList<>();
        if (typeCount == 1) {
            MutableComponent c = Messenger.c("  ", dyeColourToPrettyColourCodeMap.get(color) + type, "w :").copy();
            var itemKey = map.keySet().toArray()[0];
            c.append(_formatItem(server, (Item)itemKey, map.getLong(itemKey), ticks, false));
            items.add(c);
        } else {
            items.add(Messenger.c("  ", dyeColourToPrettyColourCodeMap.get(color) + type, "w , Total: ", "wb "+total, "w , (",String.format("wb %.1f",total*1.0*(20*60*60)/ticks),"w /h)"));
            items.addAll(map.object2LongEntrySet().stream().sorted((e, f) -> Long.compare(f.getLongValue(), e.getLongValue())).map(e -> {
                return _formatItem(server, e.getKey(), e.getLongValue(), ticks, true);
            }).toList());
        }
        return items;
    }

    private Component _formatItem(MinecraftServer server, Item item, long count, long ticks, boolean dash) {
        MutableComponent itemName = Component.translatable(item.getDescriptionId());
        Style itemStyle = itemName.getStyle();
        TextColor color = guessColor(item, server.registryAccess());
        itemName.setStyle((color != null) ? itemStyle.withColor(color) : itemStyle.withItalic(true));
        return Messenger.c("g  " + (dash ? " - " : ""), itemName,
                "g : ","wb "+count,"g , ",
                String.format("wb %.1f", count * (20.0 * 60.0 * 60.0) / ticks), "w /h"
        );
    }

    public List<Component> formatForHUD(MinecraftServer server) {
        long inputCount = getTotalInputItems();
        long outputCount = getTotalOutputItems();

        if (inputCount <= 0 && outputCount <= 0) {
            return new ArrayList<>();
        }

        long ticks = Math.max(server.getLevel(Level.OVERWORLD).getGameTime() - startTick, 1);  //OW

        MutableComponent c = Messenger.c("b"+prettyColour,"w : ").copy();
        if (inputCount > 0) {
            c.append(Messenger.c("w in: ", "wb " + inputCount, "w , ", "wb " + (inputCount * (20 * 60 * 60) / ticks), "w /h, "));
        }
        if (outputCount > 0) {
            c.append(Messenger.c("wb out: ", "wb " + outputCount, "w , ", "wb " + (outputCount * (20 * 60 * 60) / ticks), "w /h, "));
        }
        c.append(Messenger.c(String.format("wb %.1f ", ticks / (20.0 * 60.0)), "w min"));

        return Collections.singletonList(c);
    }

    /**
     * Converts a colour to have a low brightness and uniform colour, so when it prints the items in different colours
     * it's not too flashy and bright, but enough that it's not dull to look at.
     */
    public static int appropriateColor(int color)
    {
        if (color == 0) return MaterialColor.SNOW.col;
        int r = (color >> 16 & 255);
        int g = (color >> 8 & 255);
        int b = (color & 255);
        if (r < 70) r = 70;
        if (g < 70) g = 70;
        if (b < 70) b = 70;
        return (r << 16) + (g << 8) + b;
    }

    /**
     * Maps items that don't get a good block to reference for colour, or those that colour is wrong to a number of blocks, so we can get their colours easily with the
     * {@link Block#defaultMaterialColor()} method as these items have those same colours.
     */
    private static final Map<Item, Block> DEFAULTS = Map.ofEntries(
            entry(Items.DANDELION, Blocks.YELLOW_WOOL),
            entry(Items.POPPY, Blocks.RED_WOOL),
            entry(Items.BLUE_ORCHID, Blocks.LIGHT_BLUE_WOOL),
            entry(Items.ALLIUM, Blocks.MAGENTA_WOOL),
            entry(Items.AZURE_BLUET, Blocks.SNOW_BLOCK),
            entry(Items.RED_TULIP, Blocks.RED_WOOL),
            entry(Items.ORANGE_TULIP, Blocks.ORANGE_WOOL),
            entry(Items.WHITE_TULIP, Blocks.SNOW_BLOCK),
            entry(Items.PINK_TULIP, Blocks.PINK_WOOL),
            entry(Items.OXEYE_DAISY, Blocks.SNOW_BLOCK),
            entry(Items.CORNFLOWER, Blocks.BLUE_WOOL),
            entry(Items.WITHER_ROSE, Blocks.BLACK_WOOL),
            entry(Items.LILY_OF_THE_VALLEY, Blocks.WHITE_WOOL),
            entry(Items.BROWN_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK),
            entry(Items.RED_MUSHROOM, Blocks.RED_MUSHROOM_BLOCK),
            entry(Items.STICK, Blocks.OAK_PLANKS),
            entry(Items.GOLD_INGOT, Blocks.GOLD_BLOCK),
            entry(Items.IRON_INGOT, Blocks.IRON_BLOCK),
            entry(Items.DIAMOND, Blocks.DIAMOND_BLOCK),
            entry(Items.NETHERITE_INGOT, Blocks.NETHERITE_BLOCK),
            entry(Items.SUNFLOWER, Blocks.YELLOW_WOOL),
            entry(Items.LILAC, Blocks.MAGENTA_WOOL),
            entry(Items.ROSE_BUSH, Blocks.RED_WOOL),
            entry(Items.PEONY, Blocks.PINK_WOOL),
            entry(Items.CARROT, Blocks.ORANGE_WOOL),
            entry(Items.APPLE,Blocks.RED_WOOL),
            entry(Items.WHEAT,Blocks.HAY_BLOCK),
            entry(Items.PORKCHOP, Blocks.PINK_WOOL),
            entry(Items.RABBIT,Blocks.PINK_WOOL),
            entry(Items.CHICKEN,Blocks.WHITE_TERRACOTTA),
            entry(Items.BEEF,Blocks.NETHERRACK),
            entry(Items.ENCHANTED_GOLDEN_APPLE,Blocks.GOLD_BLOCK),
            entry(Items.COD,Blocks.WHITE_TERRACOTTA),
            entry(Items.SALMON,Blocks.ACACIA_PLANKS),
            entry(Items.ROTTEN_FLESH,Blocks.BROWN_WOOL),
            entry(Items.PUFFERFISH,Blocks.YELLOW_TERRACOTTA),
            entry(Items.TROPICAL_FISH,Blocks.ORANGE_WOOL),
            entry(Items.POTATO,Blocks.WHITE_TERRACOTTA),
            entry(Items.MUTTON, Blocks.RED_WOOL),
            entry(Items.BEETROOT,Blocks.NETHERRACK),
            entry(Items.MELON_SLICE,Blocks.MELON),
            entry(Items.POISONOUS_POTATO,Blocks.SLIME_BLOCK),
            entry(Items.SPIDER_EYE,Blocks.NETHERRACK),
            entry(Items.GUNPOWDER,Blocks.GRAY_WOOL),
            entry(Items.SCUTE,Blocks.LIME_WOOL),
            entry(Items.FEATHER,Blocks.WHITE_WOOL),
            entry(Items.FLINT,Blocks.BLACK_WOOL),
            entry(Items.LEATHER,Blocks.SPRUCE_PLANKS),
            entry(Items.GLOWSTONE_DUST,Blocks.GLOWSTONE),
            entry(Items.PAPER,Blocks.WHITE_WOOL),
            entry(Items.BRICK,Blocks.BRICKS),
            entry(Items.INK_SAC,Blocks.BLACK_WOOL),
            entry(Items.SNOWBALL,Blocks.SNOW_BLOCK),
            entry(Items.WATER_BUCKET,Blocks.WATER),
            entry(Items.LAVA_BUCKET,Blocks.LAVA),
            entry(Items.MILK_BUCKET,Blocks.WHITE_WOOL),
            entry(Items.CLAY_BALL, Blocks.CLAY),
            entry(Items.COCOA_BEANS,Blocks.COCOA),
            entry(Items.BONE,Blocks.BONE_BLOCK),
            entry(Items.COD_BUCKET,Blocks.BROWN_TERRACOTTA),
            entry(Items.PUFFERFISH_BUCKET,Blocks.YELLOW_TERRACOTTA),
            entry(Items.SALMON_BUCKET,Blocks.PINK_TERRACOTTA),
            entry(Items.TROPICAL_FISH_BUCKET,Blocks.ORANGE_TERRACOTTA),
            entry(Items.SUGAR,Blocks.WHITE_WOOL),
            entry(Items.BLAZE_POWDER,Blocks.GOLD_BLOCK),
            entry(Items.ENDER_PEARL,Blocks.WARPED_PLANKS),
            entry(Items.NETHER_STAR,Blocks.DIAMOND_BLOCK),
            entry(Items.PRISMARINE_CRYSTALS,Blocks.SEA_LANTERN),
            entry(Items.PRISMARINE_SHARD,Blocks.PRISMARINE),
            entry(Items.RABBIT_HIDE,Blocks.OAK_PLANKS),
            entry(Items.CHORUS_FRUIT,Blocks.PURPUR_BLOCK),
            entry(Items.SHULKER_SHELL,Blocks.SHULKER_BOX),
            entry(Items.NAUTILUS_SHELL,Blocks.BONE_BLOCK),
            entry(Items.HEART_OF_THE_SEA,Blocks.CONDUIT),
            entry(Items.HONEYCOMB,Blocks.HONEYCOMB_BLOCK),
            entry(Items.NAME_TAG,Blocks.BONE_BLOCK),
            entry(Items.TOTEM_OF_UNDYING,Blocks.YELLOW_TERRACOTTA),
            entry(Items.TRIDENT,Blocks.PRISMARINE),
            entry(Items.GHAST_TEAR,Blocks.WHITE_WOOL),
            entry(Items.PHANTOM_MEMBRANE,Blocks.BONE_BLOCK),
            entry(Items.EGG,Blocks.BONE_BLOCK),
            //entry(Items.,Blocks.),
            entry(Items.COPPER_INGOT,Blocks.COPPER_BLOCK),
            entry(Items.AMETHYST_SHARD, Blocks.AMETHYST_BLOCK));

    /**
     * Gets the colour to print an item in when printing its count in a hopper counter.
     */
    public static TextColor fromItem(Item item, RegistryAccess registryAccess)
    {
        if (DEFAULTS.containsKey(item)) return TextColor.fromRgb(appropriateColor(DEFAULTS.get(item).defaultMaterialColor().col));
        if (item instanceof DyeItem dye) return TextColor.fromRgb(appropriateColor(dye.getDyeColor().getMaterialColor().col));
        Block block = null;
        final Registry<Item> itemRegistry = registryAccess.registryOrThrow(Registries.ITEM);
        final Registry<Block> blockRegistry = registryAccess.registryOrThrow(Registries.BLOCK);
        ResourceLocation id = itemRegistry.getKey(item);
        if (item instanceof BlockItem blockItem)
        {
            block = blockItem.getBlock();
        }
        else if (blockRegistry.getOptional(id).isPresent())
        {
            block = blockRegistry.get(id);
        }
        if (block != null)
        {
            if (block instanceof AbstractBannerBlock) return TextColor.fromRgb(appropriateColor(((AbstractBannerBlock) block).getColor().getMaterialColor().col));
            if (block instanceof BeaconBeamBlock) return TextColor.fromRgb(appropriateColor( ((BeaconBeamBlock) block).getColor().getMaterialColor().col));
            return TextColor.fromRgb(appropriateColor( block.defaultMaterialColor().col));
        }
        return null;
    }

    /**
     * Guesses the item's colour from the item itself. It first calls {@link HopperCounter#fromItem} to see if it has a
     * valid colour there, if not just makes a guess, and if that fails just returns null
     */
    public static TextColor guessColor(Item item, RegistryAccess registryAccess)
    {
        TextColor direct = fromItem(item, registryAccess);
        if (direct != null) return direct;
        if (CarpetServer.minecraft_server == null) return WHITE;

        ResourceLocation id = registryAccess.registryOrThrow(Registries.ITEM).getKey(item);
        for (RecipeType<?> type: registryAccess.registryOrThrow(Registries.RECIPE_TYPE))
        {
            for (Recipe<?> r: ((RecipeManagerInterface) CarpetServer.minecraft_server.getRecipeManager()).getAllMatching(type, id, registryAccess))
            {
                for (Ingredient ingredient: r.getIngredients())
                {
                    for (Collection<ItemStack> stacks : ((IngredientInterface) (Object) ingredient).getRecipeStacks())
                    {
                        for (ItemStack iStak : stacks)
                        {
                            TextColor cand = fromItem(iStak.getItem(), registryAccess);
                            if (cand != null)
                                return cand;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns the hopper counter from the colour name, if not null
     */
    public static HopperCounter getCounter(String color)
    {
        try
        {
            DyeColor colorEnum = DyeColor.valueOf(color.toUpperCase(Locale.ROOT));
            return COUNTERS.get(colorEnum);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    /**
     * The total number of items in the counter
     */
    public long getTotalOutputItems()
    {
        return outputCounter.isEmpty()?0:outputCounter.values().longStream().sum();
    }

    public long getTotalInputItems()
    {
        return inputCounter.isEmpty()?0:inputCounter.values().longStream().sum();
    }
}
