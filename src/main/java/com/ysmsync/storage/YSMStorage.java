package com.ysmsync.storage;

import com.ysmsync.YSMPlugin;

import java.io.File;
import java.sql.*;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * SQLite 持久化存储层。
 * 存储每个玩家的模型选择（modelId + textureId）。
 */
public class YSMStorage {

    private final YSMPlugin plugin;
    private Connection connection;

    public record ModelData(String modelId, String textureId) {}

    public YSMStorage(YSMPlugin plugin) {
        this.plugin = plugin;
    }

    public void open() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            File dbFile = new File(dataFolder, "ysm_models.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTables();
            plugin.getLogger().info("SQLite storage opened: " + dbFile.getAbsolutePath());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to open SQLite storage", e);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to close SQLite storage", e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_models (
                    uuid TEXT PRIMARY KEY,
                    model_id TEXT NOT NULL DEFAULT '',
                    texture_id TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
            """);
        }
    }

    public Optional<ModelData> loadModel(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT model_id, texture_id FROM player_models WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String modelId = rs.getString("model_id");
                String textureId = rs.getString("texture_id");
                return Optional.of(new ModelData(modelId, textureId));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load model for " + uuid, e);
        }
        return Optional.empty();
    }

    public void saveModel(UUID uuid, String modelId, String textureId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO player_models (uuid, model_id, texture_id, updated_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, modelId != null ? modelId : "");
            ps.setString(3, textureId != null ? textureId : "");
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save model for " + uuid, e);
        }
    }

    public void removeModel(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM player_models WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to remove model for " + uuid, e);
        }
    }
}
