/*
 * Copyright (c) 2019-2020 GeyserMC. http://geysermc.org
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 *
 *  @author GeyserMC
 *  @link https://github.com/GeyserMC/PackConverter
 *
 */

package org.geysermc.packconverter.api.converters;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Getter;
import org.geysermc.packconverter.api.PackConverter;
import org.geysermc.packconverter.api.utils.CustomModelDataHandler;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CustomModelDataConverter extends AbstractConverter {

    @Getter
    public static final List<Object[]> defaultData = new ArrayList<>();

    static {
        defaultData.add(new String[] {"assets/minecraft/models/item", "textures/item_texture.json"});
    }

    public CustomModelDataConverter(PackConverter packConverter, Path storage, Object[] data) {
        super(packConverter, storage, data);
    }

    @Override
    public List<AbstractConverter> convert() {
        packConverter.log("Checking for custom model data");

        String from = (String) this.data[0];
        String to = (String) this.data[1];

        ObjectMapper mapper = new ObjectMapper();

        // Create the texture_data file that will map all textures
        ObjectNode textureData = mapper.createObjectNode();
        textureData.put("resource_pack_name", "geysercmd");
        textureData.put("texture_name", "atlas.items");
        ObjectNode allTextures = mapper.createObjectNode();

        // Create the item mappings file
        ObjectNode itemMappings = mapper.createObjectNode();

        List<File> allFiles;
        try {
            allFiles = Files.walk(storage.resolve(from))
                    .filter(Files::isRegularFile)
                    .map(path -> path.toFile())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            packConverter.log("Unable to list all the model files to make custom model data mappings: " + e.getMessage());
            return new ArrayList<>();
        }

        for (File file : allFiles) {
            JsonNode node;
            try {
                InputStream stream = new FileInputStream(file);
                node = mapper.readTree(stream);
            } catch (IOException e) {
                packConverter.log("Unable to read the model file at " + file.getAbsolutePath() + ": " + e.getMessage());
                continue;
            }

            if (node.has("overrides")) {
                String javaItem = file.getName().replace(".json", "");
                List<ObjectNode> tmpMappings = new ArrayList<>();

                for (JsonNode override : node.get("overrides")) {
                    JsonNode predicate = override.get("predicate");
                    // This is where the custom model data happens - each one is registered here under "predicate"
                    if (predicate.has("custom_model_data")) {
                        // The "ID" of the CustomModelData. If the ID is 1, then to get the custom model data
                        // You need to run in Java `/give @s stick{CustomModelData:1}`
                        int id = predicate.get("custom_model_data").asInt();
                        // Get the identifier that we'll register the item with on Bedrock, and create the mappings data
                        String cleanIdentifier = override.get("model").asText();
                        cleanIdentifier = cleanIdentifier.substring(cleanIdentifier.lastIndexOf("/") + 1);
                        String identifier = "geysercmd:" + cleanIdentifier;

                        // See if we have registered the vanilla item already
                        Int2ObjectMap<String> data = packConverter.getCustomModelData().getOrDefault(javaItem, null);
                        if (data == null) {
                            // Create a fresh map of Java CustomModelData IDs to Bedrock string identifiers
                            Int2ObjectMap<String> map = new Int2ObjectOpenHashMap<>();
                            map.put(id, identifier);
                            // Put the vanilla item (stick) and the initialized map in the custom model data table
                            packConverter.getCustomModelData().put(javaItem, map);
                        } else {
                            // Map exists, add the new CustomModelData ID and Bedrock string identifier
                            data.put(id, identifier);
                        }

                        // Create the texture information
                        String modelPath = override.get("model").asText();
                        if (modelPath.contains(":")) {
                            modelPath = "assets/" + modelPath.substring(0, modelPath.indexOf(":")) + "/models/" + modelPath.substring(modelPath.indexOf(":") + 1) + ".json";
                        } else {
                            modelPath = "assets/minecraft/models/" + modelPath + ".json";
                        }

                        File itemModel = storage.resolve(modelPath).toFile();
                        if (!itemModel.exists()) {
                            packConverter.log("Could not find model for " + javaItem + " -> " + cleanIdentifier);
                            continue;
                        }

                        String texturePath = CustomModelDataHandler.handleItemTexture(packConverter, mapper, storage, itemModel);
                        File textureFile = null;
                        if (texturePath != null) {
                            textureFile = storage.resolve("assets/minecraft/" + texturePath + ".png").toFile();

                            ObjectNode textureInfo = mapper.createObjectNode();
                            ObjectNode textureName = mapper.createObjectNode();
                            // Make JSON data for Bedrock pointing to where texture data for this item is stored
                            textureName.put("textures", texturePath);
                            // Have the identifier point to that texture data
                            textureInfo.set(cleanIdentifier, textureName);
                            // If texture was created, add it to the file where Bedrock will read all textures
                            allTextures.setAll(textureInfo);
                        }

                        // Create the mapping file data
                        ObjectNode mapping = mapper.createObjectNode();
                        mapping.put("name", cleanIdentifier);
                        mapping.put("custom_model_data", id);

                        if (textureFile != null && textureFile.exists()) {
                            try {
                                BufferedImage image = ImageIO.read(textureFile);
                                if (image.getWidth() != 16) {
                                    mapping.put("texture_size", image.getWidth());
                                }
                            } catch (IOException e) {
                                packConverter.log("Could not read texture for " + javaItem + " -> " + cleanIdentifier + ": " + e.getMessage());
                            }
                        }

                        try {
                            JsonNode model = mapper.readTree(itemModel);
                            if (model.has("parent")) {
                                mapping.put("is_tool", model.get("parent").asText().endsWith("item/handheld"));
                            }
                        } catch (IOException e) {
                            packConverter.log("Could not read model for " + javaItem + " -> " + cleanIdentifier + ": " + e.getMessage());
                        }

                        mapping.put("allow_offhand", true);

                        tmpMappings.add(mapping);
                    }
                }

                // Add the mappings to the item mappings file
                if (!tmpMappings.isEmpty()) {
                    itemMappings.set("minecraft:" + javaItem, mapper.valueToTree(tmpMappings));
                }
            }
        }

        if (!itemMappings.isEmpty()) {
            ObjectNode mappingFile = mapper.createObjectNode();
            mappingFile.put("format_version", "1.0.0");
            mappingFile.set("items", itemMappings);

            Path mappingsFile = storage.getParent().resolve("item_mappings.json");
            packConverter.log("Writing item mappings to " + mappingsFile.toAbsolutePath());

            try {
                OutputStream outputStream = Files.newOutputStream(mappingsFile, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
                mapper.writer(new DefaultPrettyPrinter()).writeValue(outputStream, mappingFile);
            } catch (IOException e) {
                packConverter.log("Failed to write item mappings to " + mappingsFile.toAbsolutePath() + ": " + e.getMessage());
            }
        } else {
            packConverter.log("No custom items found");
        }

        textureData.set("texture_data", allTextures);

        if (!packConverter.getCustomModelData().isEmpty()) {
            Path itemTextures = storage.resolve(to);
            try {
                // We have custom model data, so let's write the textures
                OutputStream outputStream = Files.newOutputStream(itemTextures, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
                mapper.writer(new DefaultPrettyPrinter()).writeValue(outputStream, textureData);
            } catch (IOException e) {
                packConverter.log("Failed to write item textures to " + itemTextures.toAbsolutePath() + ": " + e.getMessage());
            }
        }
        packConverter.log(String.format("Converted models %s", from));

        return new ArrayList<>();
    }
}
