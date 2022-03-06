/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
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

package org.geysermc.packconverter.api.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.geysermc.packconverter.api.PackConverter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class CustomModelDataHandler {
    public static String handleItemTexture(PackConverter packConverter, ObjectMapper mapper, Path storage, File modelFile) {
        InputStream stream;
        JsonNode textureFile;
        try {
            // Read the model information for the Java CustomModelData
            stream = new FileInputStream(modelFile);
            textureFile = mapper.readTree(stream);
        } catch (IOException e) {
            packConverter.log("Failed to read model file at " + modelFile.getAbsolutePath() + ": " + e.getMessage());
            return null;
        }

        // TODO: This is called BSing it. It works but is it correct?
        if (textureFile.has("textures")) {
            if (textureFile.get("textures").has("0") || textureFile.get("textures").has("layer0")) {
                String determine = textureFile.get("textures").has("0") ? "0" : "layer0";

                String path = textureFile.get("textures").get(determine).asText();
                String namespace = "minecraft";
                if (path.contains(":")) {
                    namespace = path.split(":")[0];
                    path = path.substring(namespace.length() + 1);
                }

                Path input = storage.resolve("assets/" + namespace + "/textures/" + path + ".png").toAbsolutePath();
                if (path.startsWith("item/")) {
                    path = "textures/items/" + path.substring(5);
                } else if (path.startsWith("block/")) {
                    path = "textures/blocks/" + path.substring(6);
                }
                if (!path.startsWith("textures/")) {
                    path = "textures/" + path;
                }

                if (!namespace.equals("minecraft")) {
                    Path output = storage.resolve(path + ".png").toAbsolutePath();
                    try {
                        output.getParent().toFile().mkdirs();
                        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        packConverter.log("Failed to copy needed texture for " + modelFile.getAbsolutePath() + ": " + e.getMessage());
                        return null;
                    }
                }
                return path;
            }
        }

        return null;
    }
}
