package space.essem.image2map.config;

import com.google.gson.JsonObject;

/** Migrates the old schema before deserialization and before any save. */
public final class ConfigMigration {
    private ConfigMigration() { }
    public static void migrate(JsonObject object) {
        if (object.has("version")) return;
        rename(object, "maxSize", "imageMaxWidthHeight");
        rename(object, "allowLocalFiles", "allowServerLocalFiles");
        object.addProperty("version", 2);
    }

    private static void rename(JsonObject object, String oldName, String newName) {
        if (object.has(oldName)) {
            if (!object.has(newName)) object.add(newName, object.get(oldName));
            object.remove(oldName);
        }
    }
}
