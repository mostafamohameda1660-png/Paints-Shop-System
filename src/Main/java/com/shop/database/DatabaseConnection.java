package com.shop.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.nio.file.Path;

/**
 * @deprecated Use {@link DatabaseManager} instead.
 * This class is kept only for backward compatibility and delegates
 * all calls to {@code DatabaseManager}.
 */
@Deprecated
public final class DatabaseConnection {

    private DatabaseConnection() {}

    /** @deprecated Use {@link DatabaseManager#getConnection()} */
    @Deprecated
    public static Connection getConnection() throws SQLException {
        return DatabaseManager.getConnection();
    }

    /** @deprecated Use {@link DatabaseManager#databasePath()} */
    @Deprecated
    public static Path databasePath() {
        return DatabaseManager.databasePath();
    }

}
