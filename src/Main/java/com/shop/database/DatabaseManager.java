package com.shop.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Centralized manager for all SQLite database connections.
 * Single source of truth for the database URL — no file in the project
 * should construct a {@code jdbc:sqlite:} string directly.
 */
public final class DatabaseManager {

    /**
     * Database path: {working-directory}/data/shop.db
     * The working directory is the directory where the application was launched from
     * (i.e. the installation directory chosen by the user during setup).
     * This ensures the database file stays alongside the installed application.
     */
    private static final Path DB_PATH = Path.of(
        System.getProperty("user.dir"),
        "data",
        "shop.db"
    );
 

    /** Full JDBC URL — the only place in the project where this string is built. */
    private static final String DB_URL = "jdbc:sqlite:" + DB_PATH;

    private DatabaseManager() {}


    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns a connection to the SQLite database.
     * Creates the parent directory and database file automatically if
     * they do not exist (first-run scenario).
     */
    public static Connection getConnection() throws SQLException {
        ensureDirectoryExists();
        Connection connection = DriverManager.getConnection(DB_URL);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    /** Returns the JDBC URL string (e.g. {@code jdbc:sqlite:C:\Users\...\shop.db}). */
    public static String getUrl() {
        return DB_URL;
    }

    /** Returns the filesystem path to the database file. */
    public static Path databasePath() {
        return DB_PATH;
    }

    /**
     * Ensures the database directory exists.
     * Safe to call multiple times — no-op if already present.
     */
    public static void ensureDirectoryExists() {
        try {
            Files.createDirectories(DB_PATH.getParent());
        } catch (Exception e) {
            throw new RuntimeException("Cannot create database directory: " + DB_PATH.getParent(), e);
        }
        System.out.println("Database path: " + DB_PATH);
    }
    
    

}
