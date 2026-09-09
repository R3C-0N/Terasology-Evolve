// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.world.block.loader;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.world.block.DefaultColorSource;
import org.terasology.gestalt.assets.Asset;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.engine.entitySystem.prefab.Prefab;
import org.terasology.engine.registry.CoreRegistry;
import org.terasology.engine.world.block.shapes.BlockShape;
import org.terasology.engine.world.block.sounds.BlockSounds;
import org.terasology.engine.utilities.gson.CaseInsensitiveEnumTypeAdapterFactory;
import org.terasology.engine.utilities.gson.Vector3fTypeAdapter;
import org.terasology.engine.utilities.gson.Vector4fTypeAdapter;
import org.terasology.engine.world.block.BlockPart;
import org.terasology.engine.world.block.family.BlockFamily;
import org.terasology.engine.world.block.family.BlockFamilyLibrary;
import org.terasology.engine.world.block.family.FreeformFamily;
import org.terasology.engine.world.block.family.HorizontalFamily;
import org.terasology.engine.world.block.family.MultiSection;
import org.terasology.engine.world.block.family.SymmetricFamily;
import org.terasology.engine.world.block.tiles.BlockTile;
import org.terasology.gestalt.assets.format.AbstractAssetFileFormat;
import org.terasology.gestalt.assets.format.AssetDataFile;
import org.terasology.gestalt.assets.management.AssetManager;
import org.terasology.gestalt.naming.Name;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Defines the format used to parse .block files.
 */
public class BlockFamilyDefinitionFormat extends AbstractAssetFileFormat<BlockFamilyDefinitionData> {

    private static final Logger logger = LoggerFactory.getLogger(BlockFamilyDefinitionFormat.class);

    private static final ResourceUrn DEFAULT_SOUNDS = new ResourceUrn("engine", "default");

    /**
     * Suffixes appended to a block definition's own resource name to discover a tile for particular faces.
     * A definition {@code CoreAssets:Chest} picks up {@code ChestFront} for the front, {@code ChestSides}
     * for the four horizontal sides and {@code ChestTopBottom} for top and bottom, with no {@code tiles}
     * object in the .block file.
     * <p>
     * Iteration order IS the precedence: the first suffix to resolve claims its parts, and later, coarser
     * entries only fill parts that are still unclaimed. This mirrors {@link #readBlockPartMap}, where the
     * per-face keys are read after {@code sides}/{@code topBottom} and therefore win.
     * <p>
     * RESERVED: "Normal", "Height" and "Gloss" name the supplementary maps consumed by
     * {@link org.terasology.engine.world.block.tiles.WorldAtlasImpl}, and must never appear here - a
     * normal map bound as a colour face would be a silent corruption.
     */
    private static final List<Map.Entry<String, List<BlockPart>>> TILE_NAME_SUFFIXES = ImmutableList.of(
            Maps.immutableEntry("Top", ImmutableList.of(BlockPart.TOP)),
            Maps.immutableEntry("Bottom", ImmutableList.of(BlockPart.BOTTOM)),
            Maps.immutableEntry("Front", ImmutableList.of(BlockPart.FRONT)),
            Maps.immutableEntry("Back", ImmutableList.of(BlockPart.BACK)),
            Maps.immutableEntry("Left", ImmutableList.of(BlockPart.LEFT)),
            Maps.immutableEntry("Right", ImmutableList.of(BlockPart.RIGHT)),
            Maps.immutableEntry("Sides", BlockPart.allHorizontalParts()),
            Maps.immutableEntry("TopBottom", ImmutableList.of(BlockPart.TOP, BlockPart.BOTTOM)));

    /**
     * Accepted only to warn about it. "Sides" is the spelling used by the {@code tiles} object and by
     * {@link #TILE_NAME_SUFFIXES}; art imported from elsewhere often uses the singular, and losing its
     * side faces in silence is worse than one line of log.
     */
    private static final String REJECTED_SIDE_SUFFIX = "Side";

    private final AssetManager assetManager;
    private final Gson gson;

    public BlockFamilyDefinitionFormat(AssetManager assetManager) {
        this(assetManager, () -> CoreRegistry.get(BlockFamilyLibrary.class));
    }

    /**
     * @param blockFamilyLibrarySupplier resolves the {@link BlockFamilyLibrary} to use for
     *         {@code basedOn}/family lookups while parsing. Prefer this over the single-arg
     *         constructor when a specific engine instance's own context is available: the
     *         single-arg constructor falls back to {@link CoreRegistry}, which is a single
     *         process-wide static - in the integration test harness, host and client engines
     *         run in the same JVM and repeatedly overwrite it with their own context on every
     *         tick, so a deserialization racing against that can observe the wrong engine's (or
     *         a momentarily null) library. Supplying this engine's own current-state context
     *         instead of going through CoreRegistry sidesteps that race entirely.
     */
    public BlockFamilyDefinitionFormat(AssetManager assetManager, Supplier<BlockFamilyLibrary> blockFamilyLibrarySupplier) {
        super("block");
        this.assetManager = assetManager;
        gson = new GsonBuilder()
                .registerTypeAdapterFactory(new CaseInsensitiveEnumTypeAdapterFactory())
                .registerTypeAdapterFactory(new AssetTypeAdapterFactory(assetManager))
                .registerTypeAdapter(BlockFamilyDefinitionData.class, new BlockFamilyDefinitionDataHandler())
                .registerTypeAdapter(Vector3f.class, new Vector3fTypeAdapter())
                .registerTypeAdapter(Vector4f.class, new Vector4fTypeAdapter())
                .registerTypeAdapter(Class.class, new BlockFamilyHandler(blockFamilyLibrarySupplier))
                .create();
    }

    @Override
    public BlockFamilyDefinitionData load(ResourceUrn resourceUrn, List<AssetDataFile> input) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input.get(0).openStream(), Charsets.UTF_8))) {
            BlockFamilyDefinitionData data = gson.fromJson(reader, BlockFamilyDefinitionData.class);

            applyDefaults(resourceUrn, data, data.getBaseSection());
            data.getSections().values().forEach(section -> applyDefaults(resourceUrn, data, section));
            if (!data.isTemplate()) {
                if (data.getBlockFamily() == null && data.getBaseSection().getShape() != null) {
                    if (data.getBaseSection().getShape().isCollisionYawSymmetric()) {
                        data.setBlockFamily(SymmetricFamily.class);
                    } else {
                        data.setBlockFamily(HorizontalFamily.class);
                    }
                } else if (data.getBlockFamily() == null) {
                    data.setBlockFamily(FreeformFamily.class);
                }
            }

            return data;
        }
    }

    /**
     * Fills any block part that has no tile yet from a tile named after this definition plus a face
     * suffix - see {@link #TILE_NAME_SUFFIXES}.
     * <p>
     * Probing outwards from the definition's own name, rather than parsing tile names to guess which
     * block they belong to, is what makes this safe: a tile family with no .block of its own is never
     * looked at. {@code DoorTop} and {@code DoorBottom} are the two halves of a door - two separate
     * blocks, not two faces - and are left alone precisely because no {@code Door} definition exists.
     * <p>
     * Only ever fills holes, so a tile set explicitly by the .block file, or inherited through
     * {@code basedOn}, always wins. Probing for a tile that does not exist is a lookup in the asset
     * file index; it decodes no image.
     */
    private void applyTileNameConventions(ResourceUrn resourceUrn, SectionDefinitionData section) {
        if (!resourceUrn.getFragmentName().isEmpty()) {
            return;
        }
        EnumMap<BlockPart, BlockTile> tiles = section.getBlockTiles();
        if (BlockPart.allParts().stream().noneMatch(part -> tiles.get(part) == null)) {
            // Every face already has a tile - a 'tile' or 'all' entry, or an inherited map. Nothing
            // to infer, and nothing to warn about: probe not at all.
            return;
        }
        for (Map.Entry<String, List<BlockPart>> candidate : TILE_NAME_SUFFIXES) {
            if (candidate.getValue().stream().noneMatch(part -> tiles.get(part) == null)) {
                continue;
            }
            Optional<BlockTile> tile = findSuffixedTile(resourceUrn, candidate.getKey());
            if (tile.isPresent()) {
                for (BlockPart part : candidate.getValue()) {
                    tiles.putIfAbsent(part, tile.get());
                }
            }
        }
        warnOnSingularSideSuffix(resourceUrn);
    }

    private Optional<BlockTile> findSuffixedTile(ResourceUrn resourceUrn, String suffix) {
        ResourceUrn tileUrn = new ResourceUrn(resourceUrn.getModuleName(),
                new Name(resourceUrn.getResourceName().toString() + suffix));
        return assetManager.getAsset(tileUrn, BlockTile.class);
    }

    private void warnOnSingularSideSuffix(ResourceUrn resourceUrn) {
        if (findSuffixedTile(resourceUrn, "Sides").isEmpty()
                && findSuffixedTile(resourceUrn, REJECTED_SIDE_SUFFIX).isPresent()) {
            logger.warn("Tile {}{} is ignored: the face suffix for the four horizontal sides is "
                            + "'Sides', not '{}'. Rename the tile, or name it in a 'tiles' object.",
                    resourceUrn.getResourceName(), REJECTED_SIDE_SUFFIX, REJECTED_SIDE_SUFFIX);
        }
    }

    private void applyDefaults(ResourceUrn resourceUrn, BlockFamilyDefinitionData data,
                               SectionDefinitionData section) {
        // Name-derived per-face tiles come FIRST. Both passes only fill holes, and the generic fill
        // below covers all seven parts, so running it first would leave nothing for the suffixes to
        // fill and make the whole table dead code.
        // Skipped for templates: a template's name is a category noun ("wood", "soil"), and basedOn
        // copies its already-populated tile map wholesale into every child - a stray woodTop.png
        // would silently grow a top face on every wood block in the game.
        if (!data.isTemplate()) {
            applyTileNameConventions(resourceUrn, section);
        }
        Optional<BlockTile> defaultTile = assetManager.getAsset(resourceUrn, BlockTile.class);
        if (defaultTile.isPresent()) {
            for (BlockPart part : BlockPart.values()) {
                if (section.getBlockTiles().get(part) == null) {
                    section.getBlockTiles().put(part, defaultTile.get());
                }
            }
        }
        if (section.getSounds() == null) {
            section.setSounds(assetManager.getAsset(DEFAULT_SOUNDS, BlockSounds.class).get());
        }

    }

    private class BlockFamilyDefinitionDataHandler implements JsonDeserializer<BlockFamilyDefinitionData> {

        private Type listOfStringType = new TypeToken<List<String>>() {
        }.getType();


        @Override
        public BlockFamilyDefinitionData deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject jsonObject = json.getAsJsonObject();
            BlockFamilyDefinitionData base = createBaseData(jsonObject);

            // Deserialize everything
            BlockFamilyDefinitionData result = new BlockFamilyDefinitionData(base);
            setBoolean(result::setTemplate, jsonObject, "template");
            setObject(result::setBlockFamily, jsonObject, "family", Class.class, context);
            setObject(result::setCategories, jsonObject, "categories", listOfStringType, context);

            deserializeSectionDefinitionData(result.getBaseSection(), jsonObject, context);


            if (result.getBlockFamily() != null) {
                for (MultiSection multiSection : BlockFamilyLibrary.getMultiSections(result.getBlockFamily())) {
                    if (jsonObject.has(multiSection.name()) && jsonObject.get(multiSection.name()).isJsonObject()) {
                        JsonObject jsonMultiSection = jsonObject.getAsJsonObject(multiSection.name());
                        for (String section : multiSection.appliesToSections()) {
                            SectionDefinitionData sectionData = result.getSections().get(section);
                            if (sectionData == null) {
                                sectionData = new SectionDefinitionData(base.getSection(section));
                                deserializeSectionDefinitionData(sectionData, jsonObject, context);
                                result.getSections().put(section, sectionData);
                            }
                            deserializeSectionDefinitionData(sectionData, jsonMultiSection, context);
                        }
                    }
                }
                for (String section : BlockFamilyLibrary.getSections(result.getBlockFamily())) {
                    if (jsonObject.has(section) && jsonObject.get(section).isJsonObject()) {
                        SectionDefinitionData sectionData = result.getSections().get(section);
                        if (sectionData == null) {
                            sectionData = new SectionDefinitionData(base.getSection(section));
                            deserializeSectionDefinitionData(sectionData, jsonObject, context);
                            result.getSections().put(section, sectionData);
                        }
                        deserializeSectionDefinitionData(sectionData, jsonObject.getAsJsonObject(section), context);
                    }
                }
            }

            return result;
        }

        private void deserializeSectionDefinitionData(SectionDefinitionData data, JsonObject jsonObject, JsonDeserializationContext context) {
            setString(data::setDisplayName, jsonObject, "displayName");
            setBoolean(data::setLiquid, jsonObject, "liquid");
            setInt(data::setHardness, jsonObject, "hardness");
            setBoolean(data::setAttachmentAllowed, jsonObject, "attachmentAllowed");
            setBoolean(data::setReplacementAllowed, jsonObject, "replacementAllowed");
            setBoolean(data::setSupportRequired, jsonObject, "supportRequired");
            setBoolean(data::setPenetrable, jsonObject, "penetrable");
            setBoolean(data::setTargetable, jsonObject, "targetable");
            setBoolean(data::setClimbable, jsonObject, "climbable");
            setBoolean(data::setInvisible, jsonObject, "invisible");
            setBoolean(data::setTranslucent, jsonObject, "translucent");
            setBoolean(data::setDoubleSided, jsonObject, "doubleSided");
            setBoolean(data::setShadowCasting, jsonObject, "shadowCasting");
            setBoolean(data::setWaving, jsonObject, "waving");
            setObject(data::setSounds, jsonObject, "sounds", BlockSounds.class, context);
            setByte(data::setLuminance, jsonObject, "luminance");
            setObject(data::setTint, jsonObject, "tint", Vector3f.class, context);

            readBlockPartMap(jsonObject, "tile", "tiles", data::getBlockTiles, BlockTile.class, context);
            readBlockPartMap(jsonObject, "colorSource", "colorSources", data::getColorSources, DefaultColorSource.class, context);
            readBlockPartMap(jsonObject, "colorOffset", "colorOffsets", data::getColorOffsets, Vector4f.class, context);

            setFloat(data::setMass, jsonObject, "mass");
            setBoolean(data::setDebrisOnDestroy, jsonObject, "debrisOnDestroy");
            setFloat(data::setFriction, jsonObject, "friction");
            setFloat(data::setRestitution, jsonObject, "restitution");

            if (jsonObject.has("entity") && jsonObject.get("entity").isJsonObject()) {
                JsonObject entityObject = jsonObject.getAsJsonObject("entity");
                setObject(data.getEntity()::setPrefab, entityObject, "prefab", Prefab.class, context);
                setBoolean(data.getEntity()::setKeepActive, entityObject, "keepActive");
            }

            if (jsonObject.has("inventory") && jsonObject.get("inventory").isJsonObject()) {
                JsonObject inventoryObject = jsonObject.getAsJsonObject("inventory");
                setBoolean(data.getInventory()::setDirectPickup, inventoryObject, "directPickup");
                setBoolean(data.getInventory()::setStackable, inventoryObject, "stackable");
            }

            setObject(data::setShape, jsonObject, "shape", BlockShape.class, context);
            setBoolean(data::setWater, jsonObject, "water");
            setBoolean(data::setGrass, jsonObject, "grass");
            setBoolean(data::setIce, jsonObject, "ice");
        }

        private <T> void readBlockPartMap(JsonObject jsonObject, String singleName,
                                          String partsName, Supplier<EnumMap<BlockPart, T>> supplier, Class<T> type,
                                          JsonDeserializationContext context) {
            if (jsonObject.has(singleName)) {
                T value = context.deserialize(jsonObject.get(singleName), type);
                for (BlockPart blockPart : BlockPart.values()) {
                    supplier.get().put(blockPart, value);
                }
            }
            if (jsonObject.has(partsName) && jsonObject.get(partsName).isJsonObject()) {
                JsonObject partsObject = jsonObject.getAsJsonObject(partsName);
                if (partsObject.has("all")) {
                    T value = context.deserialize(partsObject.get("all"), type);
                    for (BlockPart blockPart : BlockPart.values()) {
                        supplier.get().put(blockPart, value);
                    }
                }
                if (partsObject.has("sides")) {
                    T value = context.deserialize(partsObject.get("sides"), type);
                    for (BlockPart blockPart : BlockPart.allHorizontalParts()) {
                        supplier.get().put(blockPart, value);
                    }
                }
                if (partsObject.has("topBottom")) {
                    T value = context.deserialize(partsObject.get("topBottom"), type);
                    supplier.get().put(BlockPart.TOP, value);
                    supplier.get().put(BlockPart.BOTTOM, value);
                }
                for (BlockPart part : BlockPart.values()) {
                    String partName = part.toString().toLowerCase(Locale.ENGLISH);
                    if (partsObject.has(partName)) {
                        T value = context.deserialize(partsObject.get(partName), type);
                        supplier.get().put(part, value);
                    }
                }
            }
        }

        private void setString(Consumer<String> setter, JsonObject jsonObject, String name) {
            JsonPrimitive primitive = jsonObject.getAsJsonPrimitive(name);
            if (primitive != null) {
                setter.accept(primitive.getAsString());
            }
        }

        private void setBoolean(Consumer<Boolean> setter, JsonObject jsonObject, String name) {
            JsonPrimitive primitive = jsonObject.getAsJsonPrimitive(name);
            if (primitive != null && primitive.isBoolean()) {
                setter.accept(primitive.getAsBoolean());
            }
        }

        private void setInt(Consumer<Integer> setter, JsonObject jsonObject, String name) {
            JsonPrimitive primitive = jsonObject.getAsJsonPrimitive(name);
            if (primitive != null && primitive.isNumber()) {
                setter.accept(primitive.getAsInt());
            }
        }

        private void setFloat(Consumer<Float> setter, JsonObject jsonObject, String name) {
            JsonPrimitive primitive = jsonObject.getAsJsonPrimitive(name);
            if (primitive != null && primitive.isNumber()) {
                setter.accept(primitive.getAsFloat());
            }
        }

        private void setByte(Consumer<Byte> setter, JsonObject jsonObject, String name) {
            JsonPrimitive primitive = jsonObject.getAsJsonPrimitive(name);
            if (primitive != null && primitive.isNumber()) {
                setter.accept(primitive.getAsByte());
            }
        }

        @SuppressWarnings("unchecked")
        private <T> void setObject(Consumer<T> setter, JsonObject jsonObject, String name, Type type, JsonDeserializationContext context) {
            JsonElement object = jsonObject.get(name);
            if (object != null) {
                setter.accept(context.deserialize(object, type));
            }
        }

        private BlockFamilyDefinitionData createBaseData(JsonObject jsonObject) {
            JsonPrimitive basedOn = jsonObject.getAsJsonPrimitive("basedOn");
            if (basedOn != null && !basedOn.getAsString().isEmpty()) {
                Optional<BlockFamilyDefinition> baseDef = assetManager.getAsset(basedOn.getAsString(), BlockFamilyDefinition.class);
                if (baseDef.isPresent()) {
                    BlockFamilyDefinitionData data = baseDef.get().getData();
                    if (data.getBlockFamily() == FreeformFamily.class) {
                        data.setBlockFamily(null);
                    }
                    return data;
                } else {
                    throw new JsonParseException("Unable to resolve based block definition '" + basedOn.getAsString() + "'");
                }
            }
            BlockFamilyDefinitionData data = new BlockFamilyDefinitionData();
            data.getBaseSection().setSounds(assetManager.getAsset("engine:default", BlockSounds.class).get());
            return data;
        }
    }

    private static class BlockFamilyHandler implements JsonDeserializer<Class<? extends BlockFamily>> {
        private final Supplier<BlockFamilyLibrary> blockFamilyLibrarySupplier;

        BlockFamilyHandler(Supplier<BlockFamilyLibrary> blockFamilyLibrarySupplier) {
            this.blockFamilyLibrarySupplier = blockFamilyLibrarySupplier;
        }

        @Override
        public Class<? extends BlockFamily> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {

            BlockFamilyLibrary library = blockFamilyLibrarySupplier.get();
            if (library == null) {
                // Reachable via the CoreRegistry-backed fallback supplier, which can be empty
                // mid state-transition. Without this the NPE surfaces as an opaque Gson failure.
                throw new JsonParseException("No BlockFamilyLibrary available while resolving block family '"
                        + json.getAsString() + "'");
            }
            return library.getBlockFamily(json.getAsString());
        }
    }

    private static class AssetTypeAdapterFactory implements TypeAdapterFactory {

        private final AssetManager assetManager;

        AssetTypeAdapterFactory(AssetManager assetManager) {
            this.assetManager = assetManager;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            Class<T> rawType = (Class<T>) type.getRawType();
            if (Asset.class.isAssignableFrom(rawType)) {
                final Class<? extends Asset> assetClass = (Class<? extends Asset>) rawType;
                return (TypeAdapter) new TypeAdapter<Asset>() {
                    @Override
                    public void write(JsonWriter out, Asset value) throws IOException {
                        if (value == null) {
                            out.nullValue();
                        } else {
                            out.value(value.getUrn().toString());
                        }
                    }

                    @Override
                    public Asset read(JsonReader in) throws IOException {
                        if (in.peek() == JsonToken.NULL) {
                            in.nextNull();
                            return null;
                        } else {
                            String value = in.nextString();
                            Optional<? extends Asset> asset = assetManager.getAsset(value, assetClass);
                            if (asset.isPresent()) {
                                return asset.get();
                            }
                        }
                        return null;
                    }
                };
            }
            return null;
        }
    }
}
