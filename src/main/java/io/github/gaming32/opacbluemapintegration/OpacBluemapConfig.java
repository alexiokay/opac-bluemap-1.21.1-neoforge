package io.github.gaming32.opacbluemapintegration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class OpacBluemapConfig {
    private int updateInterval = 6000; // Every 5 minutes
    private float markerMinY = 75f;
    private float markerMaxY = 75f;
    private boolean depthTest = false;

    public void loadFromFile(Path configFile) throws IOException {
        if (Files.exists(configFile)) {
            try {
                String content = Files.readString(configFile);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                
                if (json.has("updateInterval")) {
                    updateInterval = json.get("updateInterval").getAsInt();
                }
                if (json.has("markerMinY")) {
                    markerMinY = json.get("markerMinY").getAsFloat();
                }
                if (json.has("markerMaxY")) {
                    markerMaxY = json.get("markerMaxY").getAsFloat();
                }
                if (json.has("depthTest")) {
                    depthTest = json.get("depthTest").getAsBoolean();
                }
            } catch (Exception e) {
                OpacBluemapIntegration.LOGGER.warn("Failed to parse config file, using defaults", e);
            }
        }
    }

    public void saveToFile(Path configFile) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("updateInterval", updateInterval);
        json.addProperty("markerMinY", markerMinY);
        json.addProperty("markerMaxY", markerMaxY);
        json.addProperty("depthTest", depthTest);
        
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(configFile, gson.toJson(json));
    }

    public int getUpdateInterval() {
        return updateInterval;
    }

    public void setUpdateInterval(int updateInterval) {
        this.updateInterval = updateInterval;
    }

    public float getMarkerMinY() {
        return markerMinY;
    }

    public float getMarkerMaxY() {
        return markerMaxY;
    }

    public boolean isDepthTest() {
        return depthTest;
    }
}
